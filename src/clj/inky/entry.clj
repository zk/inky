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
            [inky.common :as common]
            [compojure.core :refer (defroutes GET)]
            [compojure.route :refer (not-found)]
            [hiccup.page :as hp]
            [clojure.string :as str]
            [clj-http.client :as hcl]
            [inky.compile :as comp]
            [inky.s3 :as s3]
            [inky.util :as util]
            [inky.worker :as worker]
            [clojure.java.shell :as sh]
            [clojure.edn :as edn]
            [somnium.congomongo :as mon]))

(def cljs-libs
  [["dommy" "0.1.2" "https://github.com/Prismatic/dommy"]
   ["core.async" "0.1.267.0-0d7780-alpha" "https://github.com/clojure/core.async"]
   ["core.logic" "0.8.5" "https://github.com/clojure/core.logic"]
   ["double-check" "0.5.4-SNAPSHOT" "https://github.com/cemerick/double-check"]
   ["javelin" "2.4.0" "https://github.com/tailrecursion/javelin"]
   ["cljson" "1.0.6" "https://github.com/tailrecursion/cljson"]
   ["c2" "0.2.3" "https://github.com/lynaghk/c2"]
   ["secretary" "0.4.0" "https://github.com/gf3/secretary"]])

(def ga-tag
  [:script (str "(function(i,s,o,g,r,a,m){i['GoogleAnalyticsObject']=r;i[r]=i[r]||function(){
  (i[r].q=i[r].q||[]).push(arguments)},i[r].l=1*new Date();a=s.createElement(o),
  m=s.getElementsByTagName(o)[0];a.async=1;a.src=g;m.parentNode.insertBefore(a,m)
  })(window,document,'script','//www.google-analytics.com/analytics.js','ga');
  ga('create', '" (env/str :ga-tracking-id) "', '" (env/str :ga-tracking-host) "');
  ga('send', 'pageview');")])

(def link-re #"(([A-Za-z]{3,9}:(?:\/\/)?)(?:[-;:&=\+\$,\w]+@)?[A-Za-z0-9.-]+|(?:www.|[-;:&=\+\$,\w]+@)[A-Za-z0-9.-]+)((?:\/[\+~%\/.\w-_]*)?\??(?:[-\+=&;%@.\w_]*)#?(?:[\w]*))?")

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
       common/parse-source-meta
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

(defn in-progress? [hash]
  (mon/fetch-one :compile-jobs :where {:gist-id hash
                                       :succeeded nil
                                       :failed nil}))

(defn html-response [body & [opts]]
  (merge
    opts
    {:headers {"Content-Type" "text/html; utf-8"}
     :body body}))

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

(defn compiling-job? [job]
  (and (:started job)
       (not (:succeeded job))
       (not (:failed job))))

(defn succeeded-job? [job]
  (and (:started job)
       (:succeeded job)))

(defn failed-job? [job]
  (and (:started job)
       (:failed job)))

(defn compile-duration [{:keys [started succeeded]}]
  (format "%.2f" (/ (- succeeded started) 1000.0)))

(defn $link-gist [gist-id text]
  [:a {:href (str "https://gist.github.com/" gist-id)} text])

(defn $jobs-section [{:keys [jobs]}]
  [:section.jobs
   [:h3 "Work Queue"]
   (if (> (count jobs) 0)
     [:ul
      (for [{:keys [gist-id public-gist] :as job} jobs]
        (cond
          (compiling-job? job)
          [:li.compiling
           [:i.icon-cogs]
           "Gist " (if public-gist
                     ($link-gist gist-id gist-id)
                     "a private gist")
           ", started compiling " (util/timeago (:started job)) " ago."]

          (succeeded-job? job)
          [:li.succeeded
           [:i.icon-ok]
           "Compiled "
           (if public-gist
             ($link-gist gist-id gist-id)
             "a private gist")
           " " (util/timeago (:started job)) " ago."
           " Took " (compile-duration job) " s."]

          (failed-job? job)
          [:li.failed
           [:i.icon-remove]
           "Failed compiling "
           (if public-gist
             ($link-gist gist-id gist-id)
             "a private gist")
           " "(util/timeago (:started job)) " ago."]

          :else
          [:li.waiting
           [:i.icon-time]
           "Gist "
           (if public-gist
             ($link-gist gist-id gist-id)
             "a private gist")
           ", enqueued " (util/timeago (:created job)) " ago."]))]
     [:div.null-state
      "No jobs."])])

(defn $intro [data]
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
        " if you'd like your library to be available to inky sketches, or open a pull request at "
        [:a {:href "https://github.com/zk/inky"} "zk/inky"]
        "."]]
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
       [:p "Keeping your lines to < 80 characters makes it a bit easier to read your source."]]
      ($jobs-section data)]}))

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
        gist-url (str "https://gist.github.com/" login "/" gist-id)
        user-url (str "https://github.com/" login)]
    ($layout
      {:body-class :sketch-page
       :content
       [:body
        [:div.wrapper
         [:section
          [:h1 ns]
          [:p (format-doc doc)]]
         (if-not (:success compile-res)
           [:section.compile-failed
            [:h2 "Ruh-Roh, compile failed:"]
            [:p "Rerun compilation by setting query param " [:code "recompile=true"] " on this page."]
            [:pre
             "# Compilation result:\n\n"
             (util/pp-str compile-res)]]
           [:section
            [:iframe {:src sketch-url}]
            [:div.controls
             [:a {:href gist-url} "fork this sketch"]
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
         [:script {:type "text/javascript"}
          (str
            (slurp "syntaxhighlighterclj.js") ";"
            "SyntaxHighlighter.defaults.toolbar=false;"
            "SyntaxHighlighter.defaults.gutter=true;"
            "SyntaxHighlighter.all();")]]]})))

(defn render-error [& body]
  ($layout
    {:content body}))

(defn request-compile [login gist-id]
  (mon/insert! :compile-jobs {:login login :gist-id gist-id :created (util/now)})
  true)


(def $four-oh-four
  #_($layout
      {:content
       [:div
        [:h1 {:style "font-size: 100px; text-align: center; margin-top: 150px;"} "404"]]})
  (hp/html5
    [:head
     [:title "404 | inky.cc"]
     (util/$style
       [:body {:font-family "'Helvetica Neue', Arial, sans-serif"
               :padding-top "50px"
               :color "#555"}
        :h1 {:font-size "60px"
             :text-align "center"
             :font-weight "300"}
        :a {:color "#428bca"
            :text-decoration "none"}
        :a:hover {:color "#2a6496"}])]
    [:body
     [:h1 "404 | " [:a {:href "/"} "inky.cc"]]]))

(defroutes _routes
  (GET "/" [] (fn [r]
                (html-response
                  ($intro {:jobs (mon/fetch :compile-jobs
                                   :sort {:created -1}
                                   :limit 30)}))))

  (GET "/dev" [] (fn [r]
                   (let [ns (or (-> r :params :ns)
                                (guess-gist-ns "src/gists"))]
                     (html-response
                       (render-dev ns)))))

  ;; (?!)
  (GET "/:login/:gist-id" [login gist-id]
    (fn [r]
      (let [;; s3 unavailable + missing meta handleded as the same, tease apart
            sketch-meta (try
                          (->> (slurp (str "http://f.inky.cc/" gist-id "/meta.edn"))
                               edn/read-string)
                          (catch Exception e
                            (println "Exception reading meta from s3 for gist id:" gist-id)
                            (.printStackTrace e)
                            nil))
            recompile? (-> r :params :recompile)
            compile? (or (not sketch-meta)
                         (-> r :params :recompile))
            compiling? (in-progress? gist-id)]

        (cond
          compiling? (if recompile?
                       (redirect (str "/" login "/" gist-id))
                       (html-response (render-compiling)))
          compile? (do (request-compile login gist-id)
                       (redirect (str "/" login "/" gist-id)))
          :else (html-response (sketch-page login gist-id sketch-meta))))))

  (GET "/:login/:gist-id/sketch" [login gist-id]
    (fn [r]
      (common/render-compiled gist-id)))

  (GET "/show-compiling" [] (render-compiling))

  (not-found $four-oh-four))

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
  (try
    (mon/set-connection! (mon/make-connection (env/str :mongo-url "mongodb://localhost:27017/inky")))
    (let [port (env/int :port 8080)]
      (worker/spawn (env/int :num-workers 2))
      (start-http-server
        (var routes)
        {:port port :join? false})
      (println (format "Server running on port %d" port)))
    (catch Exception e
      (println e)
      (.printStackTrace e)
      (throw e))))
