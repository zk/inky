(ns inky.worker
  (:require [inky.compile :as comp]
            [inky.env :as env]
            [somnium.congomongo :as mon]))

(defn spawn-workers [num-workers]
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
  (mon/set-connection! (mon/make-connection (env/str :mongo-url "mongodb://localhost:27017/inky")))
  (spawn-workers (env/int :num-workers 2)))
