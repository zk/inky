(ns inky.s3
  (:require [inky.env :as env])
  (:import [com.amazonaws.auth BasicAWSCredentials]
           [com.amazonaws.services.s3 AmazonS3Client]
           [com.amazonaws.services.s3.model PutObjectRequest]
           [java.io File]))

(def access-id (env/str :aws-access-id))
(def secret-key (env/str :aws-secret-access-key))
(def bucket (env/str :aws-bucket))

(def client (AmazonS3Client. (BasicAWSCredentials. access-id secret-key)))

(defn upload-hash [hash base-path]
  (doseq [file (->> base-path
                    (File.)
                    file-seq
                    (remove #(.isDirectory %)))]
    (let [name (.getAbsolutePath file)
          key (str hash (.replace name base-path ""))]
      (.putObject client (PutObjectRequest. bucket key file)))))
