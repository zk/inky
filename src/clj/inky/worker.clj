(ns inky.worker
  (:require [inky.compile :as comp]
            [inky.env :as env]))

(defn spawn-workers [num-workers]
  (doseq [id (repeatedly num-workers #(str (java.util.UUID/randomUUID)))]
    (println "Spawning worker" id)
    (future
      (comp/run-worker! id))))

(defn -main []
  (spawn-workers (env/int :num-workers 2)))
