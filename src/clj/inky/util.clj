(ns inky.util
  (:require [cheshire.core :refer (parse-string)]
            [clojure.pprint :refer (pprint)]))

(defn from-json [json-str]
  (parse-string json-str true))

(defn now [] (System/currentTimeMillis))

(defn ms-since [ms]
  (- (now) ms))

(defn timeago [ms]
  (when ms
    (let [delta-ms (ms-since ms)
          s (/ delta-ms 1000)
          m (/ s 60)
          h (/ m 60)
          d (/ h 24)
          y (/ d 365.0)]
      (cond
        (< s 60) "less than a minute"
        (< m 2) "1 minute"
        (< h 1) (str (int m) " minutes")
        (< h 2) "1 hour"
        (< d 1) (str (int h) " hours")
        (< d 2) "1 day"
        (< y 1) (str (int d) " days")
        (< y 2) "1 year"
        :else (str (format "%.1f" y) " years")))))

(defn format-ms [ms format]
  (let [d (java.util.Date. ms)]
    (.format (java.text.SimpleDateFormat. format) d)))

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

(defn pp-str [o]
  (let [w (java.io.StringWriter.)]
    (pprint o w)
    (.toString w)))


(defn compile-transform [[prop val]]
  (str
    (name prop)
    ":"
    (cond
      (string? val) val
      (coll? val) (->> val
                       (map name)
                       (interpose ",")
                       (apply str)))
    ";"))

(defn compile-rule [[sel transform]]
  (str (name sel)
       "{"
       (->> transform
            (map compile-transform)
            (apply str))
       "}"))

(defn $style [rules]
  [:style {:type "text/css"}
   (->> (partition 2 rules)
        (map compile-rule)
        (interpose " ")
        (apply str))])

(defn uuid []
  (str (java.util.UUID/randomUUID)))
