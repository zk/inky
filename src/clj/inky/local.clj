(ns inky.local
  (:require [aleph.http :as ah]
            [inky.util :as util]
            [inky.common :as common]
            [inky.sketch :as sketch]
            [cljs.closure :as cljsc]
            [cljs.env :as cljsenv]
            [cljsbuild.compiler :as cbc]
            [hiccup.page :as hp]
            [ring.middleware.file-info :refer (wrap-file-info file-info-response)]
            [ring.middleware.file :refer (wrap-file)]
            [ring.util.mime-type :refer (ext-mime-type)]
            [clojure.java.io :refer (resource)]))

(def inky-version "0.1.6")

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

(defn wrap-public-resource [h]
  (fn [r]
    (if-let [cpr (resource (str "public" (:uri r)))]
      (let [mime-type (or (ext-mime-type (.getPath cpr))
                          "application/octect-stream")]
        {:body (.openStream cpr)
         :headers {"Content-Type" mime-type}
         :status 200})
      (h r))))

(defn handler [sketch-path]
  (fn [r]
    (try
      (let [cljs-path (cljs-file-path-in sketch-path)
            cljs-dir sketch-path
            hash (util/md5 cljs-dir)
            source (common/safe-slurp cljs-path)
            source-meta (common/parse-source-meta source)
            ns (:ns source-meta)]
        (cond
          (= "/" (:uri r))
          {:body (hp/html5
                   (rest
                     (sketch/$sketch-page
                       (merge
                         {:login "NOUSER"
                          :gist-id "NOGIST"
                          :inky-version inky-version
                          :created (util/now)
                          :user {:avatar-url "https://gravatar.com/avatar/53ff3f6b624b685fd3d5a9ce5630f14e?d=https%3A%2F%2Fidenticons.github.com%2Fb98d115bcae7ad72487811f5f0bce0fb.png&r=x"}
                          :source source}
                         source-meta))))
           :status 200}

          (.endsWith (:uri r) "/sketch")
          (do
            (let [compile-res (compile-cljs hash cljs-dir @mtimes)]
              (when (:success compile-res)
                (swap! mtimes merge (:return compile-res))))
            {:headers {"Content-Type" "text/html"}
             :body (render-dev ns)
             :status 200})

          :else (let [res ((-> (fn [r] {:body "Not Found" :status 404})
                               wrap-public-resource
                               (wrap-file (str user-home "/.inky.cc/" hash))
                               wrap-file-info)
                           r)]
                  res)))
      (catch Exception e
        (.printStackTrace e)
        {:status 500
         :body (str "Ruh-roh!\n\n" (str e))}))))

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
    (reset! ss (start-server 4658 "/Users/zk/napplelabs/tmpinky/dommytest")))

  (restart))
