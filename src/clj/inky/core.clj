(ns inky.core
  (:require [cljs.closure :as cljsc]
            [hiccup.page :as hp]
            [clojure.java.shell :as sh]
            [cljs.analyzer :as ana]
            [clojure.string :as str]))

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
       (->> (partition 2 transform)
            (map compile-transform)
            (apply str))
       "}"))

(defn style-el [& rules]
  [:style {:type "text/css"}
   (->> rules
        (map compile-rule)
        (interpose " ")
        (apply str))])

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

(defn compile-cljs [structure]
  (cljsc/build
    structure
    {:optimizations :advanced}))

(defn canvas [cljs-source]
  (hp/html5
    [:head]
    [:body
     [:div.canvas]
     [:script {:type "text/javascript"}
      cljs-source]]))

(defn format-doc [s]
  (str/replace s #"\n\n" "<br /><br />"))

(defn wrapper-template [cljs-source
                        {:keys [ns doc]} cvs]
  (hp/html5
    [:head
     [:title (str ns "Esper")]
     (style-el
       [:body   [:font-family "'Helvetica Neue', Arial, sans-serif;"
                 :margin-top "30px"]]
       ["h1,h2,h3,h4,h5" [:font-weight "normal"]]
       [:h1 [:font-size "50px"
             :margin-bottom "20px"
             :letter-spacing "1px"]]
       [:p [:line-height "1.5em"
            :font-size "22px"
            :font-weight "300"
            :font-family "Garamond"]]
       [:iframe [:width "100%"
                 :height "500px"
                 :border "solid #eee 1px"]]
       [:.wrapper [:width "760px"
                   :margin "0 auto"]]
       [:section [:margin-bottom "30px"]]
       [:pre [:font-size "15px"]]
       [:.syntaxhighlighter [:font-size "15px"
                             :font-family "monospace"]])
     [:style {:type "text/css"}
      (slurp "syntaxhighlighterclj.css")]]
    [:body
     [:div.wrapper
      [:section
       [:h1 ns]
       [:p (format-doc doc)]]
      [:section
       [:iframe {:srcdoc cvs}]]
      [:section
       [:pre {:class "brush: clojure"} cljs-source]]
      [:script {:type "text/javascript"}
       (str
         (slurp "syntaxhighlighterclj.js") ";"
         "SyntaxHighlighter.defaults.toolbar=false;"
         "SyntaxHighlighter.defaults.gutter=true;"
         "SyntaxHighlighter.all();")]]]))

(defn parse-meta [forms]
  (let [ns (->> forms
                (filter #(and (coll? %) (= 'ns (first %))))
                first)
        doc (if (string? (nth ns 2))
              (nth ns 2))]
    {:ns (second ns)
     :doc doc}))

(do
  (let [cljs-source (slurp "examples/first.cljs")
        read-source (read-string (str "[" cljs-source "]"))
        js-source (compile-cljs read-source)
        cvs (wrapper-template
              cljs-source
              (parse-meta read-source)
              (canvas js-source))
        file-name (str "/tmp/" (md5 cvs) ".html")]
    (spit file-name cvs)
    (sh/sh "open" file-name)))
