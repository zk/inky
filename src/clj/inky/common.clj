(ns inky.common
  (:require [hiccup.page :as hp]))

(def inky-version
  (try
    (let [source (slurp "project.clj")
          forms (read-string (str "[" source "]"))]
      (->> forms
           (filter #(= 'defproject (first %)))
           first
           (drop 2)
           first))
    (catch java.io.FileNotFoundException e
      "UNKNOWN")))

(defn render-compiled [hash]
  (hp/html5
    [:head]
    [:body
     [:div.sketch]
     [:script {:type "text/javascript"
               :src (str "http://f.inky.cc/" hash "/code.js")}]]))

(defn parse-source-meta
  "Parse meta info from source string, such as namespace and ns doc
   string."
  [source]
  (try
    (let [forms (read-string (str "[" source "]"))
          ns (->> forms
                  (filter #(and (coll? %) (= 'ns (first %))))
                  first)
          doc (and (coll? ns)
                   (> (count ns) 2)
                   (string? (nth ns 2))
                   (nth ns 2))]
      {:ns (second ns)
       :doc doc})
    (catch Exception e {})))

(defn render-dev [ns]
  (hp/html5
    [:head]
    [:body
     [:div.sketch]
     [:script {:type "text/javascript"
               :src "/js/react-0.8.0.js"}]
     [:script {:type "text/javascript"
               :src "/gists/goog/base.js"}]
     [:script {:type "text/javascript"
               :src "/gists/gists.js"}]
     [:script {:type "text/javascript"}
      "goog.require(\"" ns "\");"]]))


(defn safe-slurp [s]
  "Like slurp, but doesn't throw an exception when file not found."
  (when s
    (try
      (slurp s)
      (catch java.io.FileNotFoundException e nil))))

(defn guess-gist-ns [root-path]
  (->> (file-seq (java.io.File. root-path))
       (map #(.getAbsolutePath %))
       (filter #(.endsWith % ".cljs"))
       first
       safe-slurp
       parse-source-meta
       :ns))
