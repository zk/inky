(ns inky.s3
  (:require [inky.config :as config])
  (:import [com.amazonaws.auth BasicAWSCredentials]
           [com.amazonaws.services.s3 AmazonS3Client]
           [com.amazonaws.services.s3.model PutObjectRequest ObjectMetadata]
           [java.io File]))

(defn client []
  (AmazonS3Client.
    (BasicAWSCredentials. config/aws-access-id config/aws-secret-key)))

(def client (memoize client))

(defn upload-hash [hash base-path]
  (doseq [file (->> base-path
                    (File.)
                    file-seq
                    (remove #(.isDirectory %)))]
    (let [name (.getAbsolutePath file)
          key (str hash (.replace name base-path ""))]
      (.putObject (client) (PutObjectRequest. config/aws-s3-bucket key file)))))

(defn upload-file [from to]
  (.putObject (client) (PutObjectRequest. config/aws-s3-bucket to (java.io.File. from))))

(defn put-object [key content]
  (.putObject (client) (PutObjectRequest. config/aws-s3-bucket key content)))

(defn put-string [key s & [{:keys [content-type]}]]
  (let [meta (ObjectMetadata.)]
    (when content-type
      (.setContentType meta content-type))
    (when s
      (.putObject (client)
        (PutObjectRequest.
          config/aws-s3-bucket
          key
          (java.io.ByteArrayInputStream. (.getBytes s "UTF-8"))
          meta)))))
