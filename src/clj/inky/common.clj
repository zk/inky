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
