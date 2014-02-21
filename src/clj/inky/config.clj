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

(def cljs-libs
  [["c2" "0.2.3" "https://github.com/lynaghk/c2"]
   ["cljson" "1.0.6" "https://github.com/tailrecursion/cljson"]
   ["core.async" "0.1.267.0-0d7780-alpha" "https://github.com/clojure/core.async"]
   ["core.logic" "0.8.7" "https://github.com/clojure/core.logic"]
   ["dommy" "0.1.2" "https://github.com/Prismatic/dommy"]
   ["double-check" "0.5.4-SNAPSHOT" "https://github.com/cemerick/double-check"]
   ["javelin" "3.1.0" "https://github.com/tailrecursion/javelin"]
   ["schema" "0.2.1" "https://github.com/Prismatic/schema"]
   ["secretary" "1.0.0" "https://github.com/gf3/secretary"]])

(def previews
  [["almost.haiku"
    "@heyzk"
    "zk/8065432"
    "http://f.inky.cc.s3.amazonaws.com/top-examples/haiku.jpg"]
   ["tenk.k.processes.redux"
    "@heyzk"
    "/zk/7981870"
    "http://f.inky.cc.s3.amazonaws.com/top-examples/tenkredux.jpg"]
   ["instagram.api"
    "@heyzk"
    "/zk/8048938"
    "http://f.inky.cc.s3.amazonaws.com/top-examples/instagram.jpg"]
   ["first"
    "@heyzk"
    "/zk/7981902"
    "http://f.inky.cc.s3.amazonaws.com/top-examples/first.jpg"]])
