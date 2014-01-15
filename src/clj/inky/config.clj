(ns inky.config
  (:require [inky.env :as env]))

(def ga-tracking-id (env/str :ga-tracking-id))
(def ga-tracking-host (env/str :ga-tracking-host))

(def gh-client-id (env/str :gh-client-id))
(def gh-client-secret (env/str :gh-client-secret))

(def mongo-url (env/str :mongo-url "mongodb://localhost:27017/inky"))

(def port (env/int :port 8080))

(def num-workers (env/int :num-workers 2))

(def aws-access-id (env/str :aws-access-id))
(def aws-secret-key (env/str :aws-secret-access-key))
(def aws-s3-bucket (env/str :aws-s3-bucket))
