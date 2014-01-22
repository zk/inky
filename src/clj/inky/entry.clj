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

(defn $getting-started []
  (common/$layout
    {:body-class :getting-started-page
     :content
     [:div
      [:h3 "Getting Started"]
      [:p
       "We'll assume you've got the JDK and "
       [:a {:href "http://leiningen.org"} "Leningen"]
       " installed and available on your "
       [:code "$PATH"]
       ", and a "
       [:a {:href "https://github.com"} "GitHub"]
       " account."]
      [:h4 "Step 1: Fork &amp; Clone"]
      [:p
       "Inky sketches are gist-backed. This is a great thing, in that sketches are "
       "forkable, so you can easily build on existing skeches, and "
       "cloneable, so you can author locally, using your tools."]
      [:p "We're going to do both of these now."]
      [:ol
       [:li
        "Fork "
        [:a {:href "https://gist.github.com/zk/8108564"} "this gist"]
        " and copy the gist id, which looks something like this: " [:code "8065432"] "."]
       [:li
        "Clone your new gists' repo. For example, assuming the gist id above: "
        [:code "git clone git@gist.github.com:8065432.git inkystarter"]
        "."]
       [:li [:code "cd inkystarter"]]]
      [:h4 "Step 2: Add the Inky Leiningen Plugin"]
      [:p "Leiningen allows you globally add functionality, like (surprise) compiling Inky sketches, by adding a file to your home directory."]
      [:ol
       [:li
        "Create or edit "
        [:code "~/.lein/profiles.clj"]]
       [:li
        "Add "
        [:code "[lein-inky " common/inky-version "]"]
        " to the plugins section. Your " [:code "profiles.clj"] " like so:"
        [:br] [:br]
        [:pre
         "# ~/.lein/profiles.clj

 {:user {:plugins [[lein-inky \"" common/inky-version "\"]]}}"]]]
      [:h4 "Step 3: Run the Plugin"]
      [:p
       "Back in your terminal, run "
       [:code "lein inky"]
       ", and visit "
       [:a {:href "http://localhost:4659"} "http://localhost:4659"]
       "."]]}))

(defn $intro [data]
  (common/$layout
    {:body-class :intro-page
     :content
     [:div
      [:section.about
       [:h3 "About"]
       [:p
        "Inky.cc is a place to compile and host short snippets of ClojureScript, &#224; la "
        [:a {:href "http://bl.ocks.org"} "blocks"]
        ", "
        [:a {:href "http://jsfiddle.net/"} "jsfiddle"]
        ", and "
        [:a {:href "http://codepen.io/"} "codepen"]
        "."
        " We'll bring the environment, you bring the code."]]
      [:section.instructions
       [:h3 "How-To: The Short Version"]
       [:p
        "Sketches are "
        [:a {:href "https://gist.github.com"} "gist"]
        "-backed."]
       [:div.instructions-list
        [:ol
         [:li
          "Create a gist that contains a single "
          [:code "cljs"]
          " file, then visit "
          [:code "inky.cc/:gh-login/:gist-id"]
          "."]
         [:li "???"]
         [:li "Profit"]]]
       [:p
        "There's also a slightly longer "
        [:a {:href "/getting-started"}
         "getting started tutorial"]
        "."]]
      [:section.sketch-examples
       [:h3 "Recent Sketches"]
       [:ul
        (map $sketch-preview config/previews)]]
      [:section.local-dev
       [:h3 "Sketching Locally"]
       [:p
        "Compiling a sketch takes a loooooong time, because CPU isn't cheap.
         This works for posting your finished sketches, but is much
         too long for development. We've got a lein plugin which will
         allow you to work locally -- with much faster compile times,
         source-maps, and all your Clojure tooling."]
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
        "Visit "
        [:a {:href "http://localhost:4659"}
         "http://localhost:4659"]
        " and start editing your sketch."]
       [:p
        "When you're done editing, commit, push to GitHub, and visit "
        [:code "http://inky.cc/&lt;github-login&gt;/&lt;gist-id&gt;"]
        " to make your sketch available on the tubes."]]
      [:section.libs
       [:h3 "Libs"]
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
        "."]]
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

  (GET "/getting-started" [] (fn [r]
                               ($getting-started)))

  ;; (?!)
  (GET "/:login/:gist-id" [login gist-id]
    (fn [r]
      (let [ ;; s3 unavailable + missing meta handleded as the same, tease apart
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
