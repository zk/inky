(ns inky.worker
  (:require [inky.compile :as comp]
            [inky.config :as config]
            [somnium.congomongo :as mon]))

(defn spawn [num-workers]
  (doseq [id (repeatedly num-workers #(str (java.util.UUID/randomUUID)))]
    (println "Spawning worker" id)
    ;; hack, exceptions thrown from future were swallowed
    (.start
      (Thread.
        (fn []
          (try
            (comp/run-worker! id)
            (catch Exception e
              (println e)
              (.printStackTrace e))))))))

(defn -main []
  (mon/set-connection! (mon/make-connection config/mongo-url))
  (spawn config/num-workers))
