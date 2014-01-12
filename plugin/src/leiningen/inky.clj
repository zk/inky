(ns leiningen.inky
  (:require [inky.local :as il]))

(defn ^:no-project-needed inky
  [project]
  (let [cwd (.getAbsolutePath (java.io.File. "."))]
    (il/start-server 4659 cwd))
  (println "Visit http://localhost:4659 to work on your sketch.")
  (while true (Thread/sleep 1000)))
