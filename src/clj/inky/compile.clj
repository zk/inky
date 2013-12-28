(ns inky.compile
  (:require [cljs.closure :as cljsc]
            [cljs.env :as cljsenv]
            [somnium.congomongo :as mon]
            [inky.util :as util]
            [inky.common :as common]
            [slingshot.slingshot :refer (try+)]
            [clj-http.client :as hcl]
            [inky.env :as env]
            [inky.s3 :as s3]
            [clojure.java.shell :as sh]))

(defn gist-data [gist-id]
  (try+
    (let [resp (hcl/get (str "https://api.github.com/gists/" gist-id)
                 {:query-params {:client_id (env/str :gh-client-id)
                                 :client_secret (env/str :gh-client-secret)}})]
      (let [body (->> resp
                      :body
                      util/from-json)
            {:keys [login avatar_url html_url]} (:user body)]
        {:source (->> body
                      :files
                      (filter #(or (.endsWith (str (first %)) ".cljs")
                                   (.endsWith (str (first %)) ".clj")))
                      first
                      second
                      :content)
         :user {:login login
                :avatar-url avatar_url
                :html-url html_url}
         :success true}))
    (catch [:status 403] _
      ;; rate limit error
      (println "Rate limit hit fetching gist id:" gist-id)
      {:success false :error-cause "Rate limit hit fetching gist."})))

(defn compile-cljs [hash filename]
  (try
    {:success true
     :return (binding [cljsenv/*compiler* (atom {})]
               (cljsc/build
                 filename
                 {:optimizations :advanced
                  :pretty-print false
                  :output-to (str "/tmp/inky/" hash "/code.js")
                  :output-dir (str "/tmp/inky/" hash)
                  :libs [""]}))}
    (catch Exception e
      {:success false
       :error-cause (str e)})))


(defn next-job!
  "Atomically grabs and locks next job by setting the `started` field
  to current time (ms)."
  []
  (let [job (mon/fetch-and-modify
              :compile-jobs
              {:succeeded nil :started nil}
              {:$set {:started (util/now)}}
              :sort {:created -1}
              :return-new? true)]
    job))

(defn compile-next-job! [worker-id]
  (let [{:keys [gist-id] :as job} (next-job!)]
    (when job
      (try
        (let [gist-resp (gist-data gist-id)]
          (if-not (:success gist-resp)
            (mon/update! :compile-jobs
              job
              {:$set {:failed (util/now)
                      :error-cause (:error-cause gist-resp)}})
            (do
              (println worker-id "Compiling" gist-id)
              (let [source (:source gist-resp)
                    dir (str "/tmp/inky/" gist-id)
                    source-dir (str "/tmp/inky/" gist-id)
                    filename (str source-dir "/code.cljs")]
                (when (.exists (java.io.File. dir))
                  (sh/sh "rm" "-rf" dir))
                (.mkdirs (java.io.File. dir))
                (spit filename source)
                (let [compile-res (compile-cljs gist-id filename)]
                  (println worker-id compile-res)
                  (s3/put-string
                    (str gist-id "/meta.edn")
                    (pr-str
                      (merge
                        (common/parse-source-meta source)
                        {:compile-res compile-res}
                        gist-resp
                        {:created (util/now)
                         :inky-version common/inky-version})))
                  (s3/upload-file
                    (str dir "/code.js")
                    (str gist-id "/code.js"))
                  (s3/put-string
                    (str gist-id "/code.html")
                    (common/render-compiled gist-id)
                    {:content-type "text/html;charset=utf-8"}))
                (println worker-id "done compiling" gist-id)
                (mon/update! :compile-jobs
                  job
                  {:$set {:succeeded (util/now)}})))))
        (catch Exception e
          (println worker-id e)
          (.printStackTrace e)
          (mon/update! :compile-jobs
            job
            {:$set {:failed (util/now)
                    :error-cause (str e)}}))))))

(defn run-worker! [worker-id]
  (while true
    (compile-next-job! worker-id)
    (Thread/sleep 100)))

(comment
  (compile-next-job! "WORKERONE")
  (run-worker! "WORKERONE")

  (defn _compiled [job]
    (mon/update! :compile-jobs job {:$set {:succeeded (util/now)}}))

  (defn _print-jobs []
    (let [jobs (mon/fetch :compile-jobs)]
      (doseq [j jobs]
        (println j))))

  (defn _reset-jobs-db []
    (mon/drop-coll! :compile-jobs))

  #_(_reset-jobs-db)

  (defn _clear-jobs []
    (let [jobs (mon/fetch :compile-jobs)]
      (doseq [j jobs]
        (mon/update! :compile-jobs j (dissoc j :succeeded :started)))))

  (defn _ins-job [j]
    (mon/insert! :compile-jobs j))

  (_print-job)
  )
