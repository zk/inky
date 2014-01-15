(ns inky.common
  (:require [clojure.string :as str]
            [inky.util :as util]
            [inky.config :as config]))

(def cljs-libs
  [["dommy" "0.1.2" "https://github.com/Prismatic/dommy"]
   ["core.async" "0.1.267.0-0d7780-alpha" "https://github.com/clojure/core.async"]
   ["core.logic" "0.8.5" "https://github.com/clojure/core.logic"]
   ["double-check" "0.5.4-SNAPSHOT" "https://github.com/cemerick/double-check"]
   ["javelin" "2.4.0" "https://github.com/tailrecursion/javelin"]
   ["cljson" "1.0.6" "https://github.com/tailrecursion/cljson"]
   ["c2" "0.2.3" "https://github.com/lynaghk/c2"]
   ["secretary" "0.4.0" "https://github.com/gf3/secretary"]])

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

(defn render-dev [ns]
  [:html5
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
     "goog.require(\"" ns "\");"]]])


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

(def link-re #"(([A-Za-z]{3,9}:(?:\/\/)?)(?:[-;:&=\+\$,\w]+@)?[A-Za-z0-9.-]+|(?:www.|[-;:&=\+\$,\w]+@)[A-Za-z0-9.-]+)((?:\/[\+~%\/.\w-_]*)?\??(?:[-\+=&;%@.\w_]*)#?(?:[\w]*))?")

(defn format-doc [s]
  (when s
    (-> s
        (str/replace link-re (fn [[href & rest]]
                               (str "<a href=\"" href "\">" href "</a>")))
        (str/replace #"\n\n" "<br /><br />"))))

(defn sketch-page [{:keys [login gist-id compile-error sketch-url
                           ns doc user source inky-version created
                           gist-url]}]
  (let [main-url (str "/" login "/" gist-id)
        user-url (str "https://github.com/" login)]
    ($layout
      {:body-class :sketch-page
       :content
       [:body
        [:div.wrapper
         [:section
          [:h1 ns]
          [:p (format-doc doc)]]
         (if compile-error
           [:section.compile-failed
            [:h2 "Ruh-Roh, compile failed:"]
            [:p
             [:a {:href (str main-url "?recompile=true")} "Click here"]
             " to recompile. You can also re-run compilation by setting query param "
             [:code "recompile=true"]
             " on this page."]
            [:pre
             "# Compilation result:\n\n"
             (util/pp-str compile-error)]]
           [:section
            [:div.iframe-container
             [:iframe {:src sketch-url :scrolling "no"}]]
            [:div.controls
             [:a {:href gist-url} "fork"]
             " / "
             [:a {:href sketch-url} "full-screen"]]
            [:div.sketch-meta
             [:a {:href user-url}
              [:img.avatar {:src (:avatar-url user)}]]
             [:span.author "By "
              [:a {:href user-url} login]
              ". "]
             [:span.compile-info
              "Compiled "
              "with "
              [:span.version "inky v" (or inky-version "DONNO")]
              " from "
              [:span.gist-id
               "gist "
               [:a {:href gist-url} gist-id]]
              ", "
              [:span.created
               (if (< (util/ms-since created) (* 1000 60 60 24))
                 (str  (util/timeago created) " ago")
                 (str "on " (util/format-ms created "MMM dd, yyyy")))]
              "."]]])
         [:section
          [:pre {:class "brush: clojure"}
           (when source
             (-> source
                 (str/replace #">" "&gt;")
                 (str/replace #"<" "&lt;")))]]
         [:script {:type "text/javascript" :src "/js/syntaxhighlighterclj.js"}]
         [:script {:type "text/javascript"}
          "SyntaxHighlighter.defaults.toolbar=false;"
          "SyntaxHighlighter.defaults.gutter=true;"
          "SyntaxHighlighter.all();"]]]})))
