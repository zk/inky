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

(defn tpl [{:keys [ns doc canvas-url source]}]
  (hp/html5
    [:head
     [:title (str ns " | inky.cc")]
     (style-el
       :body {:font-family "'Helvetica Neue', Arial, sans-serif"
              :margin-top "30px"}
       :a {:color "#428bca"
           :text-decoration "none"}
       :a:hover {:color "#2a6496"}
       "h1,h2,h3,h4,h5" {:font-weight "normal"}
       :h1 {:font-size "50px"
            :margin-bottom "20px"
            :letter-spacing "1px"}
       :p {:line-height "1.5em"
           :font-size "22px"
           :font-weight "300"
           :font-family "Garamond"}
       :iframe {:width "100%"
                :height "500px"
                :border "solid #eee 1px"
                :overflow "auto"}
       :.wrapper {:width "760px"
                  :margin "0 auto"}
       :section {:margin-bottom "30px"}
       :pre {:font-size "15px"
             :border :none}
       :.syntaxhighlighter {:font-size "15px"
                            :font-family "monospace"
                            :white-space "nowrap"
                            :overflow "auto"}
       :.controls {:text-align "right"}
       :pre {:background-color :transparent})
     [:style {:type "text/css"}
      (slurp "syntaxhighlighterclj.css")]]
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
       [:pre {:class "brush: clojure"} source]]
      [:script {:type "text/javascript"}
       (str
         (slurp "syntaxhighlighterclj.js") ";"
         "SyntaxHighlighter.defaults.toolbar=false;"
         "SyntaxHighlighter.defaults.gutter=true;"
         "SyntaxHighlighter.all();")]]]))

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

(defn render-compiling []
  (hp/html5
    [:head
     [:meta {:http-equiv "refresh" :content "6"}]
     (style-el
       :body {:font-family "'Helvetica Neue', Arial, sans-serif"
              :margin "40px"
              :line-height "1.5em"
              :color "black"}
       :.animate {:-webkit-animation-name "bgcolor"
                  :-webkit-animation-duration "6s"
                  :-webkit-animation-iteration-count "infinite"}
       :p {:font-size "20px"
           :font-weight "300"
           :margin-bottom "30px"}
       :h1 {:font-weight "normal"
            :margin-bottom "30px"}
       :.box {:height "230px"}
       "@-webkit-keyframes bgcolor"
       {"0%" {:background-color "#3498db"}
        "20%" {:background-color "#2ecc71"}
        "40%" {:background-color "#f1c40f"}
        "60%" {:background-color "#8e44ad"}
        "80%" {:background-color "#e67e22"}
        "100%" {:background-color "#3498db"}})]
    [:body
     [:h1 "Compiling!"]
     [:p "This should only take a few seconds, so sit tight and we'll load the results, automatically, when they're ready."]
     [:p "Results are cached for subsequent loads."]
     [:div.box.animate]]))

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

(defn $layout [{:keys [content]}]
  (hp/html5
    [:head
     [:link {:rel :stylesheet :href "/css/app.css"}]]
    [:body
     [:header.navbar
      [:div.container
       [:div.row
        [:div.col-md-12
         [:a.navbar-brand {:href "/"}
          "inky.cc"]
         [:span.navbar-text ":: Sketch in ClojureScript"]]]]]
     [:div.container
      [:div.row
       [:div.col-md-12
        content]]]]))

(defn $intro []
  ($layout
    {:content [:div
               [:p "Inky is an easy way to sketch and share your ideas in " [:a {:href "/"} "ClojureScript"] "."]
               [:ol
                [:li
                 "Visit "
                 [:code "http://inky.cc/compile?url=*url-of-cljs-file*"]
                 ". For example: to compile this "
                 [:a {:href "https://gist.github.com/zk/7981902/raw/c5a537e95dcb19cbaf327d069ae04b2524ae80aa/inkyfirst.cljs"} "gist file"]
                 ", visit "
                 [:a {:href "http://inky.cc/compile?url=https%3A%2F%2Fgist.github.com%2Fzk%2F7981902%2Fraw%2Fc5a537e95dcb19cbaf327d069ae04b2524ae80aa%2Finkyfirst.cljs"} "this url"]
                 "."]
                [:li "???"]
                [:li "Profit"]]
               [:section
                [:li ]]]}))

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
                                                       :source source)))
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
      (tpl (merge
             {:ns "ruh.roh"
              :source "not right"}
             (->> (slurp (str "http://f.inky.cc/" sketch-id "/meta.edn"))
                  edn/read-string)
             {:canvas-url (str "http://f.inky.cc/" sketch-id "/code.html")})))))

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
