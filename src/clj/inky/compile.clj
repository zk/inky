(ns inky.compile
  (:require [cljs.closure :as cljsc]
            [cljs.env :as env]))

(defn compile-cljs [hash filename]
  (binding [env/*compiler* (atom {})]
    (cljsc/build
      filename
      {:optimizations :advanced
       :pretty-print false
       :output-to (str "/tmp/inky/" hash "/code.js")
       :output-dir (str "/tmp/inky/" hash)
       :libs [""]})))
