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
            [inky.config :as config]
            [inky.common :as common]
            [compojure.core :refer (defroutes GET)]
            [compojure.route :refer (not-found)]
            [compojure.response :refer (Renderable render)]
            [hiccup.page :as hp]
            [clojure.string :as str]
            [clj-http.client :as hcl]
            [inky.compile :as comp]
            [inky.worker :as worker]
            [inky.s3 :as s3]
            [inky.util :as util]
            [inky.sketch :as sketch]
            [clojure.java.shell :as sh]
            [clojure.edn :as edn]
            [somnium.congomongo :as mon]))

;; Extend hiccup to support rendering of hiccup vectors
;; Allows (GET "/" [] (fn [req] [:html5 [:body [:h1 "hello world"]]]))
;;
;; Nice for removing hiccup as a dependency from most html-generating code.

(defn hiccup->html-string [body]
  (if-not (vector? body)
    body
    (let [bodys (if (= :html5 (first body))
                  (rest body)
                  [body])]
      (hp/html5 bodys))))

(extend-protocol Renderable
  clojure.lang.PersistentVector
  (render [v request]
    (render (hiccup->html-string v) request))

  clojure.lang.APersistentMap
  (render [resp-map _]
    (if (-> resp-map :body vector?)
      (assoc resp-map :body (-> resp-map :body hiccup->html-string))
      (merge (with-meta (response "") (meta resp-map))
             resp-map))))

(defn in-progress? [hash]
  (mon/fetch-one :compile-jobs
    :where {:gist-id hash
            :succeeded nil
            :failed nil}))

(defn html-response [body & [opts]]
  (merge
    opts
    {:headers {"Content-Type" "text/html; utf-8"}
     :body body}))

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

(defn $link-gist [login gist-id text]
  [:a {:href (str "/" login "/" gist-id)} text])

(defn $jobs-section [{:keys [jobs]}]
  [:section.jobs
   [:h3 "Work Queue"]
   (if (> (count jobs) 0)
     [:ul
      (for [{:keys [login gist-id public-gist] :as job} jobs]
        (cond
          (compiling-job? job)
          [:li.compiling
           [:i.icon-cogs]
           "Started compiling a gist " (util/timeago (:started job)) " ago."]

          (succeeded-job? job)
          [:li.succeeded
           [:i.icon-ok]
           "Compiled "
           (if public-gist
             ($link-gist login gist-id gist-id)
             "a gist")
           " " (util/timeago (:started job)) " ago."
           " Took " (compile-duration job) " s."]

          (failed-job? job)
          [:li.failed
           [:i.icon-remove]
           "Failed compiling "
           (if public-gist
             ($link-gist login gist-id gist-id)
             "a gist")
           " "(util/timeago (:started job)) " ago."]

          :else
          [:li.waiting
           [:i.icon-time]
           "Enqueued a gist " (util/timeago (:created job)) " ago."]))]
     [:div.null-state
      "No jobs."])])

(defn $sketch-preview [[name author-twitter inky-path image-url]]
  [:li.sketch-preview
   [:div.sketch-meta
    [:strong name]
    " by "
    [:a {:href (str "https://twitter.com/" author-twitter)} author-twitter]]
   [:a {:href inky-path}
    [:img {:src image-url}]]])

(defn $intro [data]
  (common/$layout
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
        (->> config/cljs-libs
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
        "."]
       [:p "Getting started instructions below."]]
      [:section.sketch-examples
       [:h3 "Recent Sketches"]
       [:ul
        (map $sketch-preview config/previews)]]
      [:section.instructions
       [:h3 "Compiling Your Sketches"]
       [:p
        "Sketches are "
        [:a {:href "https://gist.github.com"} "gist"]
        "-backed."]
       [:div.instructions-list
        [:ol
         [:li
          "Visit "
          [:code "inky.cc/:gh-login/:gist-id"]
          ". This will compile the provided source and redirect you to the resulting sketch."]
         [:li "???"]
         [:li "Profit"]]]]
      [:section.local-dev
       [:h3 "Sketching Locally"]
       [:p
        "We've got a lein plugin to help you bring your tools to bear on inky sketches."]
       [:ol
        [:li
         "Add "
         [:code "[lein-inky " common/inky-version "]"]
         " to the plugins section of your user profile in "
         [:code "~/.lein/profiles.clj"]]
        [:li
         "Fork "
         [:a {:href "https://gist.github.com/zk/8108564"} "this gist"]]
        [:li
         "Clone that gist locally: "
         [:code "git clone git@gist.github.com/&lt;gist-id&gt;.git"]]
        [:li [:code "cd &lt;gist-id&gt;"]]
        [:li "Run " [:code "lein inky"]]]
       [:p
        "You'll see something like the following output. Visit "
        [:a {:href "http://localhost:4659"} "http://localhost:4659"]
        " and start editing your sketch."]
       [:p
        "When you're done editing, commit, push, and visit "
        [:code "http://inky.cc/&lt;github-login&gt;/&lt;gist-id&gt;"]
        " to make your sketch available online."]]
      ($jobs-section data)]}))

(defn request-compile [login gist-id]
  (mon/insert! :compile-jobs {:login login :gist-id gist-id :created (util/now)})
  true)

(def $four-oh-four
  [:html5
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
    [:h1 "404 | " [:a {:href "/"} "inky.cc"]]]])

(defroutes _routes
  (GET "/" [] (fn [r]
                ($intro {:jobs (mon/fetch :compile-jobs
                                 :sort {:created -1}
                                 :limit 10)})))

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
                       (sketch/$compiling-page))
          compile? (do (request-compile login gist-id)
                       (redirect (str "/" login "/" gist-id)))
          :else (html-response (sketch/$sketch-page sketch-meta))))))

  (GET "/:login/:gist-id/sketch" [login gist-id]
    (fn [r]
      (common/render-compiled gist-id)))

  (GET "/show-compiling" [] (sketch/$compiling-page))

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
    (mon/set-connection! (mon/make-connection config/mongo-url))
    (start-http-server
      (var routes)
      {:port config/port :join? false})
    (println (format "Server running on port %d" config/port))
    (catch Exception e
      (println e)
      (.printStackTrace e)
      (throw e))))

(defn dev []
  (try
    (mon/set-connection! (mon/make-connection config/mongo-url))
    (worker/spawn 1)
    (start-http-server
      (var routes)
      {:port config/port :join? false})
    (println (format "Server running on port %d" config/port))
    (catch Exception e
      (println e)
      (.printStackTrace e)
      (throw e))))
