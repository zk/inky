(ns inky.common
  (:require [clojure.string :as str]
            [inky.util :as util]
            [inky.config :as config]))

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
  [:html5
   [:head]
   [:body
    [:div.sketch]
    [:script {:type "text/javascript"
              :src (str "http://f.inky.cc/" hash "/code.js")}]]])

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

(defn safe-slurp [s]
  "Like slurp, but doesn't throw an exception when file not found."
  (when s
    (try
      (slurp s)
      (catch java.io.FileNotFoundException e nil))))

(def ga-tag
  [:script (str "(function(i,s,o,g,r,a,m){i['GoogleAnalyticsObject']=r;i[r]=i[r]||function(){
  (i[r].q=i[r].q||[]).push(arguments)},i[r].l=1*new Date();a=s.createElement(o),
  m=s.getElementsByTagName(o)[0];a.async=1;a.src=g;m.parentNode.insertBefore(a,m)
  })(window,document,'script','//www.google-analytics.com/analytics.js','ga');
  ga('create', '" config/ga-tracking-id "', '" config/ga-tracking-host "');
  ga('send', 'pageview');")])

(defn $layout [{:keys [content body-class head]}]
  [:html5
   (-> [:head
        [:meta {:name "viewport" :content "width=768px"}]
        [:link {:rel :stylesheet :href "http://fonts.googleapis.com/css?family=PT+Serif" :type "text/css"}]
        [:link {:rel :stylesheet :href "/css/app.css"}]]
       (concat head)
       vec)
   [:body
    (when body-class
      {:class body-class})
    [:div.sticky-footer-wrap
     [:div.container
      [:div.row
       [:div.col-sm-12
        [:div.row
         [:header.navbar
          [:div
           [:a.navbar-brand {:href "/"}
            [:i.icon-rocket] "inky.cc"]
           [:span.navbar-text "/ sketch in cljs"]]]
         [:div.col-sm-12
          content]]]]]]
    [:footer
     [:div.container
      [:div.row
       [:div.col-sm-121
        "inky.cc is brought to you by "
        [:a {:href "https://twitter.com/heyzk"} "@heyzk"]]]]]
    (when ga-tag
      ga-tag)]])
