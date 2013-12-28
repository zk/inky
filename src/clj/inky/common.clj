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
