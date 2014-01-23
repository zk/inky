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
      [:section
       [:h3 "Getting Started / Overview"]
       [:p
        "We'll assume you've got the JDK and "
        [:a {:href "http://leiningen.org"} "Leningen"]
        " installed and available on your "
        [:code "$PATH"]
        ", and a "
        [:a {:href "https://github.com"} "GitHub"]
        " account."]
       [:p "By end of this tutorial you'll have created and uploaded your first inky sketch."]]
      [:section
       [:h4 "Step 1: Fork &amp; Clone"]
       [:p
        "Inky sketches are gist-backed, which give us two distinct benefits:  "
        "1) sketches are forkable, so you can easily build on existing skeches, and "
        "2) sketches are cloneable, so you can use your tools to author locally. We're going to do both of these now"]
       [:ol
        [:li
         "Fork "
         [:a {:href "https://gist.github.com/zk/8108564"} "this gist"]
         " and note the gist id, which looks something like this: " [:code "8065432"] "."]
        [:li
         "Clone your new gists' repo. For example, assuming the gist id above: "
         [:code "git clone git@gist.github.com:8065432.git inkystarter"]
         "."]
        [:li [:code "cd inkystarter"]]]]
      [:section
       [:h4 "Step 2: Add the Inky Leiningen Plugin"]
       [:p "Leiningen allows you globally add functionality, like (surprise!) the ability to compile inky sketches, by adding a specific file to your home directory. We're going to add the lein-inky plugin to this file, which will allow you to compile sketches locally and view them in the same way they'll appear on the site."]
       [:ol
        [:li
         "Create or edit "
         [:code "~/.lein/profiles.clj"]]
        [:li
         "Add "
         [:code "[lein-inky " common/inky-version "]"]
         " to the plugins section. Your " [:code "profiles.clj"] " should look something like:"
         [:br] [:br]
         [:pre
          "# ~/.lein/profiles.clj

 {:user {:plugins [[lein-inky \"" common/inky-version "\"]]}}"]]]]
      [:section
       [:h4 "Step 3: Run the Plugin"]
       [:p
        "We provide a local editing experience because, on the server, compiles take way too long to support a short feedback loop (cloud CPU ain't cheap). Local recompile happens on reload, and takes around 2 second (as of this writing). Getting that down to cljsbuild levels is a high priority."]
       [:p
        "Back in your terminal, run "
        [:code "lein inky"]
        ", and visit "
        [:a {:href "http://localhost:4659"} "http://localhost:4659"]
        ". "
        "The page will load and your sketch will begin to compile. While the page load immediately, the sketch takes a bit to compile the first time (~15s on my machine). Subsequent compiles are much faster (~2s)."]
       [:p "You should see something like this:"]
       [:img {:src "/img/inkystarter-first-compile.jpg"}]]
      [:section
       [:h4 "Step 4: Edit, Reload, Repeat"]
       [:p "Next, open up " [:code "inkystarter.cljs"] " and change the "
        [:code "\"Hello World\""]
        " text on line 12 to something else. Perhaps "
        [:code "\"Woot!\""]
        "suits your fancy?"]
       [:p "Save your code, and reload the browser. Again, the page loads instantly, but the sketch takes a second or two."]
       [:img {:src "/img/inkystarter-second-compile.jpg"}]
       [:p "The template " [:code "require"] "s the " [:code "inky.sketch"] " namespace, which contains two sketch helper functions:"]
       [:ul
        [:li [:code "inky.sketch/page-style!"] " provides a nice way to apply css rules to your sketch using Clojure data structures, similar to the way HTML is generated using hiccup-style syntax. This function takes a map of selectors -> map of rules."]
        [:li [:code "inky.sketch/content!"] " takes a hiccup-style data structure and appends it to the root div in the sketch, which has class " [:code "sketch"] "."]]
       [:p "You don't have to use any of this, of course, it's perfectly fine to use a different mechanism to interact with the environment."]]
      [:section
       [:h4 "Step 5: Upload and Compile Your Sketch"]
       [:p "Once you're done, commit your changes and push to the server. You can specify a commit message, or not, as they're not as important as with normal GitHub repos."]
       [:p "Next, visit " [:code "http://inky.cc/:gh-login/:gist-id"] ", where " [:code ":gh-login"] " is your username, and " [:code ":gist-id"] " is the gist id from above."]
       [:p "If this is the first time you're compiling your sketch on the server, compilation will start automatically, and you'll se a screen similar to:"]
       [:img {:src "/img/inkystarter-compiling.jpg"}]
       [:p "You'll be redireted to the compiled sketch's page, and now it's ready to be viewed and linked to from READMEs, blogposts, tweets, etc."]]
      [:section
       [:h4 "Recompiling Sketches"]
       [:p "Tack a " [:code "recompile=true"] " on to the end of the sketch URL to force a recompile. Useful when you've updated the gist's code and want to post the new version."]]
      [:section
       [:h4 "Done!"]
       [:p "We hope this tutorial has been helpful in getting you up-and-running, and in explaining a little bit about how inky works. If you have an idea on how to improve this tutorial, please "
        [:a {:href "https://github.com/zk/inky/issues"} "open an issue"]
        " or "
        [:a {:href "https://github.com/zk/inky/pulls"} "send us a pull request"]
        "."]]]}))

(defn $intro [data]
  (common/$layout
    {:body-class :intro-page
     :content
     [:div
      [:section.about
       [:h3 "About"]
       [:p
        "Inky is a place to compile and host short snippets of ClojureScript, &#224; la "
        [:a {:href "http://bl.ocks.org"} "blocks"]
        ", "
        [:a {:href "http://jsfiddle.net/"} "jsfiddle"]
        ", and "
        [:a {:href "http://codepen.io/"} "codepen"]
        "."
        " We'll bring the environment, you bring the code."]
       [:p "We believe that access to example code is an important part of a language's ecosystem. Inky aims to help with that, in some small way, for ClojureScript."]]
      [:section.instructions
       [:h3 "How-To: The Short Version"]
       [:div.instructions-list
        [:ol
         [:li
          "Create a " [:a {:href "https://gist.github.com"} "gist"] " that contains a single "
          [:code "cljs"]
          " file, then visit "
          [:code "inky.cc/:gh-login/:gist-id"]
          "."]
         [:li "???"]
         [:li "Profit"]]]
       [:p
        "Here's a slightly longer "
        [:a {:href "/getting-started"}
         "getting started tutorial"]
        "."]]
      [:section.sketch-examples
       [:h3 "Recent Sketches"]
       [:ul
        (map $sketch-preview config/previews)]]
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
