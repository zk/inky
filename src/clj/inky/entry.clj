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
        [ring.util.response :only (response content-type)])
  (:require [ring.adapter.jetty :as jetty]
            [ring.util.response :as resp]
            [aleph.http :as ah]
            [inky.env :as env]
            [inky.entry :as entry]
            [compojure.core :refer (defroutes GET)]
            [hiccup.page :as hp]
            [clojure.string :as str]
            [clj-http.client :as hcl]
            [inky.compile :as comp]
            [inky.s3 :as s3]
            [inky.util :as util]
            [clojure.java.shell :as sh]
            [clojure.edn :as edn]))

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

(declare compile-rule)

(defn compile-transform [[prop val]]
  (if (map? val)
    (compile-rule [prop val])
    (str
      (name prop)
      ":"
      (cond
        (string? val) val
        (coll? val) (->> val
                         (map name)
                         (interpose ",")
                         (apply str))
        (keyword val) (name val)
        :else val)
      ";")))

(defn compile-rule [[sel transform]]
  (str (name sel)
       "{"
       (->> transform
            (map compile-transform)
            (apply str))
       "}"))

(defn style-el [& rules]
  [:style {:type "text/css"}
   (->> (partition 2 rules)
        (map compile-rule)
        (interpose " ")
        (apply str))])

(def link-re #"(([A-Za-z]{3,9}:(?:\/\/)?)(?:[-;:&=\+\$,\w]+@)?[A-Za-z0-9.-]+|(?:www.|[-;:&=\+\$,\w]+@)[A-Za-z0-9.-]+)((?:\/[\+~%\/.\w-_]*)?\??(?:[-\+=&;%@.\w_]*)#?(?:[\w]*))?")

(defn format-doc [s]
  (when s
    (-> s
        (str/replace link-re (fn [[href & rest]]
                               (str "<a href=\"" href "\">" href "</a>")))
        (str/replace #"\n\n" "<br /><br />"))))

(def compile-cljs comp/compile-cljs)

(defn parse-meta [source]
  (let [forms (read-string (str "[" source "]"))
        ns (->> forms
                (filter #(and (coll? %) (= 'ns (first %))))
                first)
        doc (and (coll? ns)
                 (> (count ns) 2)
                 (string? (nth ns 2))
                 (nth ns 2))]
    {:ns (second ns)
     :doc doc}))

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
     [:div.canvas]
     [:script {:type "text/javascript"
               :src (str "http://f.inky.cc/" hash "/code.js")}]]))

(defn render-dev [hash entry-path]
  (hp/html5
    [:head]
    [:body
     [:div.canvas]
     [:script {:type "text/javascript"
               :src (str "/cljs-compiled/goog/base.js")}]
     [:script {:type "text/javascript"
               :src (str "/cljs-compiled/out.js")}]
     [:script {:type "text/javascript"
               :src (str "/cljs-compiled/" entry-path ".js")}]]))

(defn $layout [{:keys [content body-class head]}]
  (hp/html5
    (-> [:head
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
         [:a {:href "https://twitter.com/heyzk"} "@heyzk"]]]]]]))

(defn render-compiling []
  ($layout
    {:head [[:meta {:http-equiv "refresh" :content "6"}]]
     :content [:div
               (style-el
                 :.animate {:-webkit-animation-name "bgcolor"
                            :-webkit-animation-duration "6s"
                            :-webkit-animation-iteration-count "infinite"}
                 :.box {:height "230px"}
                 "@-webkit-keyframes bgcolor"
                 {"0%" {:background-color "#3498db"}
                  "20%" {:background-color "#2ecc71"}
                  "40%" {:background-color "#f1c40f"}
                  "60%" {:background-color "#8e44ad"}
                  "80%" {:background-color "#e67e22"}
                  "100%" {:background-color "#3498db"}})
               [:h1 "Compiling!"]
               [:p "This should only take a few seconds, so sit tight and we'll load the results, automatically, when they're ready."]
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
  {:headers {"Content-Type" "text/html;utf-8"}
   :body body})

(def cljs-libs
  [["Dommy" "0.1.2" "https://github.com/Prismatic/dommy"]
   ["Javelin" "2.4.0" "https://github.com/tailrecursion/javelin"]
   ["core.async" "0.1.267.0-0d7780-alpha" "https://github.com/clojure/core.async"]])

(defn $intro []
  ($layout
    {:head [[:link {:rel :stylesheet :href "http://fonts.googleapis.com/css?family=PT+Serif" :type "text/css"}]]
     :body-class :intro-page
     :content
     [:div
      [:section.about
       [:h3 "What?"]
       [:p "Inky.cc is a place to compile and host short snippets of ClojureScript. We'll bring the environment, you bring the code."]
       [:p
        "We've included several cljs libraries for you to use, including "
        (->> cljs-libs
             (map (fn [[name version href]]
                    [:span [:a {:href href} name] " (" version ")"]))
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
        [:li.sketch-preview
         [:div.sketch-meta
          [:strong "first"]
          " by "
          [:a {:href "https://twitter.com/heyzk"} "@heyzk"]]
         [:a {:href "/s/dc820ddc03b6e8e1c9ff60572ff873c5"}
          [:img {:src "http://f.cl.ly/items/06430H393A2M2T1H1N3e/Screen%20Shot%202013-12-19%20at%204.01.24%20PM.png"}]]]
        [:li.sketch-preview
         [:div.sketch-meta
          [:strong "ten.k.processes.redux"]
          " by "
          [:a {:href "https://twitter.com/heyzk"} "@heyzk"]]
         [:a {:href "/s/7e2ed9da90a50773bea5b87b73844669"}
          [:img {:src "http://s3.amazonaws.com/f.inky.cc/top-examples/7e2ed9da90a50773bea5b87b73844669.png"}]]]
        [:li.sketch-preview
         [:div.sketch-meta
          [:strong "instagram.api"]
          " by "
          [:a {:href "https://twitter.com/heyzk"} "@heyzk"]]
         [:a {:href "/s/697d0a5189c1f04e66c09e3968c194f4"}
          [:img {:src "http://f.cl.ly/items/002R2X1V2S2v26451E0O/Screen%20Shot%202013-12-20%20at%203.04.04%20PM.png"}]]]
        ]]
      [:section.instructions
       [:h3 "How-To"]
       [:div.instructions-list
        [:ol
         [:li
          "Visit "
          [:code "http://inky.cc/compile?url=url-of-cljs-file"]
          ". This will compile the provided ClojureScript text and redirect you to the resulting sketch. "
          [:br]
          "For example: to compile "
          [:a {:href "https://gist.github.com/zk/7981902/raw/c5a537e95dcb19cbaf327d069ae04b2524ae80aa/inkyfirst.cljs"} "this gist file"]
          ", visit "
          [:a {:href "/compile?url=https%3A%2F%2Fgist.github.com%2Fzk%2F7981902%2Fraw%2Fc5a537e95dcb19cbaf327d069ae04b2524ae80aa%2Finkyfirst.cljs"} "this url"]
          "."]
         [:li "???"]
         [:li "Profit"]]]
       [:p "Keeping your lines to < 80 characters makes it a bit easier to read your source."]]]}))

(defn gist-source [gist-id]
  (->> (hcl/get (str "https://api.github.com/gists/" gist-id))
       :body
       util/from-json
       :files
       (filter #(or (.endsWith (str (first %)) ".cljs")
                    (.endsWith (str (first %)) ".clj")))
       first
       second
       :content))

(defn source-by-id [id]
  (gist-source id))

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

(defroutes _routes
  (GET "/" [] (fn [r]
                (html-response
                  ($intro))))

  (GET "/compile" [] (fn [r]
                       (let [url (-> r :params :url)
                             source (slurp url)
                             hash (md5 source)
                             dir (str "/tmp/inky/" hash)
                             source-dir (str "/tmp/inky/" hash)
                             filename (str source-dir "/code.cljs")]
                         (cond
                           (compiling? hash) (render-compiling)
                           (in-s3? hash) (resp/redirect (str "/s/" hash))

                           :else (do (future
                                       (time
                                         (try
                                           (add-in-progress hash)
                                           (println "Compiling" hash url dir)
                                           (when (.exists (java.io.File. dir))
                                             (sh/sh "rm" "-rf" dir))
                                           (.mkdirs (java.io.File. dir))
                                           (spit filename source)
                                           (compile-cljs hash filename)
                                           (s3/upload-file
                                             (str dir "/code.js")
                                             (str hash "/code.js"))
                                           (s3/put-string
                                             (str hash "/meta.edn")
                                             (pr-str (assoc (parse-meta source)
                                                       :source source
                                                       :url url
                                                       :created (System/currentTimeMillis))))
                                           (s3/put-string
                                             (str hash "/code.html")
                                             (render-compiled hash)
                                             {:content-type "text/html;charset=utf-8"})
                                           #_(s3/upload-hash hash (str "/tmp/inky/" hash))
                                           (println "done compiling" hash)
                                           (catch Exception e
                                             (println e)
                                             (.printStackTrace e))
                                           (finally (remove-in-progress hash)))))
                                     (render-compiling))))))


  (GET "/check-compiled" [] (fn [r]
                              (let [url (-> r :params :url)]
                                )))
  (GET "/dev" [] (fn [r]
                   (let [url (-> r :params :url)
                         source (slurp url)
                         hash (md5 source)
                         dir (str "/tmp/inky/" hash)
                         source-dir (str "/tmp/inky-source/" hash)
                         filename (str source-dir "/first.cljs")
                         start (System/currentTimeMillis)]
                     (try
                       (.mkdirs (java.io.File. source-dir))
                       (comp/compile-cljs-none hash "examples")
                       (println "done compiling" hash (- (System/currentTimeMillis) start))
                       (catch Exception e
                         (println e)
                         (.printStackTrace e)))
                     (render-dev
                       hash
                       (-> source
                           parse-meta
                           :ns
                           str
                           (str/replace #"\." "/"))))))

  (GET "/s/:sketch-id" [sketch-id]
    (fn [r]
      (let [canvas-url (str "/s/" sketch-id "/canvas")
            {:keys [ns doc source url created]}
            (->> (slurp (str "http://f.inky.cc/" sketch-id "/meta.edn"))
                 edn/read-string)]
        ($layout
          {:body-class :sketch-page
           :content
           [:body
            [:div.wrapper
             [:section
              [:h1 ns]
              [:p (format-doc doc)]]
             [:section
              [:iframe {:src canvas-url}]
              [:div.controls
               [:a {:href canvas-url} "full-screen"]]]
             [:section
              [:pre {:class "brush: clojure"}
               (when source
                 (-> source
                     (str/replace #">" "&gt;")
                     (str/replace #"<" "&lt;")))]]
             [:section.sketch-meta
              "Created at "
              (or created "donno")
              " from "
              (if url
                [:a {:href url} (squeeze 60 url)]
                " we have no idea")
              "."]
             [:script {:type "text/javascript"}
              (str
                (slurp "syntaxhighlighterclj.js") ";"
                "SyntaxHighlighter.defaults.toolbar=false;"
                "SyntaxHighlighter.defaults.gutter=true;"
                "SyntaxHighlighter.all();")]]]}))))
  (GET "/s/:sketch-id/canvas" [sketch-id]
    (fn [r]
      (render-compiled sketch-id)))

  (GET "/show-compiling" [] (render-compiling)))

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
