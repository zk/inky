(ns inky.compile
  (:require [cljs.closure :as cljsc]
            [cljs.env :as env]))

(defn md5
  "Compute the hex MD5 sum of a string."
  [#^String str]
  (when str
    (let [alg (doto (java.security.MessageDigest/getInstance "MD5")
                (.reset)
                (.update (.getBytes str)))]
      (try
        (.toString (new BigInteger 1 (.digest alg)) 16)
        (catch java.security.NoSuchAlgorithmException e
          (throw (new RuntimeException e)))))))

(defn compile-cljs [hash filename]
  (binding [env/*compiler* (atom {})]
    (cljsc/build
      filename
      {:optimizations :advanced
       :pretty-print false
       :output-to (str "/tmp/inky/" hash "/code.js")
       :output-dir (str "/tmp/inky/" hash)
       :libs [""]})))
