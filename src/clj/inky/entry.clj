(ns inky.entry
  (:use [ring.middleware
         file
         file-info
         session
         params
         nested-params
         multipart-params
         keyword-params
         reload]
        [ring.middleware.session.cookie :only (cookie-store)]
        [ring.util.response :only (response content-type redirect)])
  (:require [aleph.http :as ah]
            [inky.env :as env]
            [compojure.core :refer (defroutes GET)]
            [hiccup.page :as hp]
            [clojure.string :as str]
            [clj-http.client :as hcl]
            [inky.compile :as comp]
            [inky.s3 :as s3]
            [inky.util :as util]
            [clojure.java.shell :as sh]
            [clojure.edn :as edn]))

(def current-inky-version
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

(def ga-tag
  [:script (str "(function(i,s,o,g,r,a,m){i['GoogleAnalyticsObject']=r;i[r]=i[r]||function(){
  (i[r].q=i[r].q||[]).push(arguments)},i[r].l=1*new Date();a=s.createElement(o),
  m=s.getElementsByTagName(o)[0];a.async=1;a.src=g;m.parentNode.insertBefore(a,m)
  })(window,document,'script','//www.google-analytics.com/analytics.js','ga');
  ga('create', '" (env/str :ga-tracking-id) "', '" (env/str :ga-tracking-host) "');
  ga('send', 'pageview');")])

(def link-re #"(([A-Za-z]{3,9}:(?:\/\/)?)(?:[-;:&=\+\$,\w]+@)?[A-Za-z0-9.-]+|(?:www.|[-;:&=\+\$,\w]+@)[A-Za-z0-9.-]+)((?:\/[\+~%\/.\w-_]*)?\??(?:[-\+=&;%@.\w_]*)#?(?:[\w]*))?")

(defn safe-slurp [s]
  (when s
    (try
      (slurp s)
      (catch java.io.FileNotFoundException e nil))))

(defn parse-meta [source]
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

(defn guess-gist-ns [root-path]
  (->> (file-seq (java.io.File. root-path))
       (map #(.getAbsolutePath %))
       (filter #(.endsWith % ".cljs"))
       first
       safe-slurp
       parse-meta
       :ns))

(defn format-doc [s]
  (when s
    (-> s
        (str/replace link-re (fn [[href & rest]]
                               (str "<a href=\"" href "\">" href "</a>")))
        (str/replace #"\n\n" "<br /><br />"))))

(defn url-encode [s]
  (when s
    (java.net.URLEncoder/encode s)))

(defn in-s3? [hash]
  (try
    (= 200 (-> (str "http://f.inky.cc/" hash "/meta.edn")
               hcl/head
               :status))
    (catch Exception e false)))

(defn render-compiled [hash]
  (hp/html5
    [:head]
    [:body
     [:div.sketch]
     [:script {:type "text/javascript"
               :src (str "http://f.inky.cc/" hash "/code.js")}]]))

(defn render-dev [ns]
  (hp/html5
    [:head]
    [:body
     [:div.sketch]
     [:script {:type "text/javascript"
               :src "/gists/goog/base.js"}]
     [:script {:type "text/javascript"
               :src "/gists/gists.js"}]
     [:script {:type "text/javascript"}
      "goog.require(\"" ns "\");"]]))

(defn $layout [{:keys [content body-class head]}]
  (hp/html5
    (-> [:head
         [:meta {:name "viewport" :content "initial-scale=1, maximum-scale=1"}]
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
         [:a {:href "https://twitter.com/heyzk"} "@heyzk"]]]]]ga-tag]))

(defn render-compiling []
  ($layout
    {:head [[:meta {:http-equiv "refresh" :content "6"}]]
     :body-class "compiling-page"
     :content [:div
               [:h1 "Compiling!"]
               [:p "This should take less than 30 seconds, so sit tight and we'll load the results, automatically, when they're ready."]
               [:p "Results are cached for subsequent loads."]
               [:div.box.animate]]}))

(def in-progress (atom #{}))

(defn add-in-progress [hash]
  (swap! in-progress conj hash))

(defn remove-in-progress [hash]
  (swap! in-progress disj hash))

(defn compiling? [hash]
  (get @in-progress hash))

(defn html-response [body]
  {:headers {"Content-Type" "text/html; utf-8"}
   :body body})

(defn gist-data [gist-id]
  (let [resp (->> (hcl/get (str "https://api.github.com/gists/" gist-id))
                  :body
                  util/from-json)
        {:keys [login avatar_url html_url]} (:user resp)]
    {:source (->> resp
                  :files
                  (filter #(or (.endsWith (str (first %)) ".cljs")
                               (.endsWith (str (first %)) ".clj")))
                  first
                  second
                  :content)
     :user {:login login
            :avatar-url avatar_url
            :html-url html_url}}))

(def cljs-libs
  [["dommy" "0.1.2" "https://github.com/Prismatic/dommy"]
   ["core.async" "0.1.267.0-0d7780-alpha" "https://github.com/clojure/core.async"]
   ["double-check" "0.5.4-SNAPSHOT" "https://github.com/cemerick/double-check"]
   ["c2" "0.2.3" "https://github.com/lynaghk/c2"]
   ["javelin" "2.4.0" "https://github.com/tailrecursion/javelin"]])

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

(defn $sketch-preview [[name author-twitter inky-path image-url]]
  [:li.sketch-preview
   [:div.sketch-meta
    [:strong name]
    " by "
    [:a {:href (str "https://twitter.com/" author-twitter)} author-twitter]]
   [:a {:href inky-path}
    [:img {:src image-url}]]])

(defn $intro []
  ($layout
    {:body-class :intro-page
     :content
     [:div
      [:section.about
       [:h3 "What?"]
       [:p
        "Inky.cc is a place to compile and host short snippets of ClojureScript, &#224; la "
        [:a {:href "http://bl.ocks.org"} "blocks"]
        ", "
        [:a {:href "http://jsfiddle.net/"} "jsfiddle"]
        ", and "
        [:a {:href "http://codepen.io/"} "codepen"]
        "."
        " We'll bring the environment, you bring the code."]
       [:p
        "We've included several cljs libraries for you to use, including "
        (->> cljs-libs
             (map (fn [[name version href]]
                    [:code [:a {:href href} name] " (" version ")"]))
             (interpose ", ")
             reverse
             ((fn [[x & rest]]
                (concat [x] [" and "] rest)))
             reverse)
        "."]
       [:p
        "Drop me a line at "
        [:a {:href "https://twitter.com/heyzk"} "@heyzk"]
        " if you'd like your library to be available to inky sketches."]]
      [:section.sketch-examples
       [:h3 "Recent Sketches"]
       [:ul
        (map $sketch-preview previews)]]
      [:section.instructions
       [:h3 "How-To"]
       [:div.instructions-list
        [:ol
         [:li
          "Visit "
          [:code "inky.cc/:gh-login/:gist-id"]
          ". This will compile the provided source and redirect you to the resulting sketch."]
         [:li "???"]
         [:li "Profit"]]]
       [:p "Keeping your lines to < 80 characters makes it a bit easier to read your source."]]]}))

(defn squeeze
  "Ellipses the middle of a long string."
  [n s]
  (let [n (max (- n 3) 0)]
    (cond
      (< (count s) n) s
      :else (let [len (count s)
                  half-len (int (/ len 2))
                  to-take-out (- len n)
                  half-take-out (int (/ to-take-out 2))
                  first-half (take half-len s)
                  second-half (drop half-len s)]
              (str (->> first-half
                        (take (- half-len half-take-out))
                        (apply str))
                   "..."
                   (->> second-half
                        (drop half-take-out)
                        (apply str)))))))

(defn sketch-page [login gist-id {:keys [doc ns created url user source inky-version compile-res]}]
  (let [sketch-url (str "/" login "/" gist-id "/sketch")
        user-url (str "https://github.com/" login)]
    ($layout
      {:body-class :sketch-page
       :content
       [:body
        [:div.wrapper
         [:section
          [:h1 ns]
          [:p (format-doc doc)]]
         (if (:success compile-res)
           [:section
            [:iframe {:src sketch-url}]
            [:div.controls
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
               [:a {:href (str "https://gist.github.com/" gist-id)} gist-id]]
              ", "
              [:span.created
               (if (< (util/ms-since created) (* 1000 60 60 24))
                 (str  (util/timeago created) " ago")
                 (str "on " (util/format-ms created "MMM dd, yyyy")))]
              "."]]]
           [:section.compile-failed
            [:h2 "Ruh-Roh, compile failed:"]
            [:p "Rerun compilation by setting query param" [:code "recompile=true"] " on this page."]
            [:pre
             "# Compilation result:\n\n"
             (util/pp-str compile-res)]
            ])
         [:section
          [:pre {:class "brush: clojure"}
           (when source
             (-> source
                 (str/replace #">" "&gt;")
                 (str/replace #"<" "&lt;")))]]
         [:script {:type "text/javascript"}
          (str
            (slurp "syntaxhighlighterclj.js") ";"
            "SyntaxHighlighter.defaults.toolbar=false;"
            "SyntaxHighlighter.defaults.gutter=true;"
            "SyntaxHighlighter.all();")]]]})))

(defroutes _routes
  (GET "/" [] (fn [r]
                (html-response
                  ($intro))))

  (GET "/dev" [] (fn [r]
                   (let [ns (or (-> r :params :ns)
                                (guess-gist-ns "src/gists"))]
                     (html-response
                       (render-dev ns)))))

  (GET "/:login/:gist-id" [login gist-id]
    (fn [r]
      (let [recompile? (-> r :params :recompile)]
        (cond
          (compiling? gist-id) (render-compiling)

          (and (in-s3? gist-id)
               (not recompile?))
          (let [{:keys [ns doc source url created] :as sketch-meta}
                (->> (slurp (str "http://f.inky.cc/" gist-id "/meta.edn"))
                     edn/read-string)]
            (sketch-page login gist-id sketch-meta))

          :else (do
                  (add-in-progress gist-id)
                  (let [url (-> r :params :url)
                        gist-data (gist-data gist-id)
                        source (:source gist-data)
                        hash gist-id
                        dir (str "/tmp/inky/" hash)
                        source-dir (str "/tmp/inky/" hash)
                        filename (str source-dir "/code.cljs")]
                    (future
                      (time
                        (try
                          (println "Compiling" hash url dir)
                          (when (.exists (java.io.File. dir))
                            (sh/sh "rm" "-rf" dir))
                          (.mkdirs (java.io.File. dir))
                          (spit filename source)
                          (let [compile-res (comp/compile-cljs hash filename)]
                            (println compile-res)
                            (s3/put-string
                              (str hash "/meta.edn")
                              (pr-str
                                (merge
                                  (parse-meta source)
                                  {:compile-res compile-res}
                                  gist-data
                                  {:created (util/now)
                                   :inky-version current-inky-version})))
                            (s3/upload-file
                              (str dir "/code.js")
                              (str hash "/code.js"))
                            (s3/put-string
                              (str hash "/code.html")
                              (render-compiled hash)
                              {:content-type "text/html;charset=utf-8"}))
                          #_(s3/upload-hash hash (str "/tmp/inky/" hash))
                          (println "done compiling" hash)
                          (catch Exception e
                            (println e)
                            (.printStackTrace e))
                          (finally (remove-in-progress hash)))))
                    (if recompile?
                      (redirect (str "/" login "/" gist-id))
                      (render-compiling))))))))

  (GET "/:login/:gist-id/sketch" [login gist-id]
    (fn [r]
      (render-compiled gist-id)))

  (GET "/show-compiling" [] (render-compiling))

  (GET "/test-show-sketch" []
    (fn [r]
      (sketch-page
        "zk"
        "8048938"
        {:doc "Let's start with something short. The quick brown fox jumps over the lazy dog."
         :ns "testing.one.two.three"
         :created 0
         :inky-version "0.1.0"
         :user {:login "zk"
                :avatar-url "https://2.gravatar.com/avatar/a27a1085b85807e609f0aa63c6e06c04?d=https%3A%2F%2Fidenticons.github.com%2Fb36ed8a07e3cd80ee37138524690eca1.png&r=x&s=440"
                :html-url ""}
         :source "(defn foo \"bar\")"}))))

(def routes
  (-> _routes
      wrap-keyword-params
      wrap-nested-params
      wrap-params
      wrap-session
      (wrap-file "resources/public" {:allow-symlinks? true})
      wrap-file-info))

(defn start-http-server [entry-point opts]
  (ah/start-http-server
    (ah/wrap-ring-handler
      (fn [r]
        (let [resp (entry-point r)]
          (if (:status resp)
            resp
            (assoc resp :status 200)))))
    opts))

(defn -main []
  (let [port (env/int :port 8080)]
    (start-http-server
      (var routes)
      {:port port :join? false})
    (println (format "Server running on port %d" port))))
