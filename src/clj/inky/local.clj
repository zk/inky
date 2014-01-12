(ns inky.local
  (:require [aleph.http :as ah]
            [inky.util :as util]
            [inky.common :as common]
            [cljs.closure :as cljsc]
            [cljs.env :as cljsenv]
            [cljsbuild.compiler :as cbc]
            [hiccup.page :as hp]
            [ring.middleware.file-info :refer (wrap-file-info)]
            [ring.middleware.file :refer (wrap-file)]))

(def user-home (System/getProperty "user.home"))

(defn render-dev [ns]
  (hp/html5
    [:head]
    [:body
     [:div.sketch]
     #_[:script {:type "text/javascript"
               :src "/js/react-0.8.0.js"}]
     [:script {:type "text/javascript"
               :src "/goog/base.js"}]
     [:script {:type "text/javascript"
               :src "/code.js"}]
     [:script {:type "text/javascript"}
      "goog.require(\"" ns "\");"]]))

(defn cljs-file-path-in
  "Reads source from the first cljs file found in path."
  [path]
  (->> (file-seq (java.io.File. path))
       (map #(.getAbsolutePath %))
       (filter #(.endsWith % ".cljs"))
       first))

(defn compile-cljs [hash filename]
  (try
    {:success true
     :return (cljsc/build
               filename
               {:optimizations :none
                :output-to (str user-home "/.inky.cc/" hash "/code.js")
                :output-dir (str user-home "/.inky.cc/" hash)
                :source-map true})}
    (catch Exception e
      {:success false
       :error-cause (str e)})))

(defn compile-cljs [hash dir mtimes]
  (try
    (.mkdirs (java.io.File. (str user-home "/.inky.cc/" hash)))
    {:success true
     :return (cbc/run-compiler
               [dir]
               ""
               []
               {:optimizations :none
                :output-to (str user-home "/.inky.cc/" hash "/code.js")
                :output-dir (str user-home "/.inky.cc/" hash)
                :source-map true}
               nil
               true
               false
               mtimes
               true)}
    (catch Exception e
      (.printStackTrace e)
      {:success false
       :error-cause (str e)})))

(defn temp-js-paths-in [dir]
  (->> dir
       (java.io.File.)
       (.listFiles)
       seq
       (map #(.getAbsolutePath %))
       (filter #(.endsWith % ".js"))
       (remove #(.contains % "code.js"))))

(def mtimes (atom {}))

(defn handler [sketch-path]
  (fn [r]
    (let [cljs-path (cljs-file-path-in sketch-path)
          cljs-dir sketch-path
          hash (util/md5 cljs-dir)
          ns (-> cljs-path
                 common/safe-slurp
                 common/parse-source-meta
                 :ns)]
      (if (= "/" (:uri r))
        (do
          (let [compile-res (compile-cljs hash cljs-dir @mtimes)]
            (when (:success compile-res)
              (swap! mtimes merge (:return compile-res))))
          {:headers {"Content-Type" "text/html"}
           :body (render-dev ns)
           :status 200})
        (let [res ((-> (fn [r] {:body ""})
                       (wrap-file (str user-home "/.inky.cc/" hash))
                       wrap-file-info)
                   r)]
          res)))))

(defn start-server [port sketch-path]
  (let [h (handler sketch-path)]
    (ah/start-http-server
      (ah/wrap-ring-handler h)
      {:port port :join? false})))

(comment
(defonce ss (atom nil))

(defn restart []
  (when @ss
    (@ss))
  (reset! ss (start-server 4658 "/Users/zk/napplelabs/tmpinky/8189314")))

(restart)

  )
