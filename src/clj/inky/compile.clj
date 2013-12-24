(ns inky.compile
  (:require [cljs.closure :as cljsc]
            [cljs.env :as env]))

(defn compile-cljs [hash filename]
  (try
    {:success true
     :return (binding [env/*compiler* (atom {})]
               (cljsc/build
                 filename
                 {:optimizations :advanced
                  :pretty-print false
                  :output-to (str "/tmp/inky/" hash "/code.js")
                  :output-dir (str "/tmp/inky/" hash)
                  :libs [""]}))}
    (catch Exception e
      {:success false
       :exception-message (str e)})))
