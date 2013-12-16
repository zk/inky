(ns inky.entry
  (:use [ring.middleware
         file
         file-info
         session
         params
         nested-params
         multipart-params
         keyword-params]
        [ring.middleware.session.cookie :only (cookie-store)]
        [ring.util.response :only (response content-type)])
  (:require [ring.adapter.jetty :as jetty]
            [aleph.http :as ah]
            [inky.env :as env]
            [inky.entry :as entry]
            [compojure.core :refer (defroutes GET)]
            [hiccup.page :as hp]
            [clojure.string :as str]
            [clj-http.client :as hcl]
            [inky.compile :as comp]
            [inky.s3 :as s3]))

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

(defn compile-transform [[prop val]]
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
    ";"))

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
       :body {:font-family "'Helvetica Neue', Arial, sans-serif;"
              :margin-top "30px"}
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

(def compile comp/compile-cljs)

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
    (= 200 (-> (str "https://s3.amazonaws.com/f.inky.cc/" hash "/code.cljs")
               hcl/head
               :status))
    (catch Exception e false)))

(defroutes _routes
  (GET "/" [] (fn [r]
                (let [url (-> r :params :url)
                      cljs-source (try (slurp url)
                                       (catch Exception e "Whoops!!!!!!!!!"))]
                  (tpl (merge (parse-meta cljs-source)
                              {:canvas-url (str "/canvas?url=" (url-encode url))
                               :source cljs-source})))))
  (GET "/canvas" [] (fn [r]
                      (let [url (-> r :params :url)
                            source (slurp url)
                            hash (md5 source)
                            dir (str "/tmp/inky/" hash)
                            filename (str dir "/code.cljs")
                            compiled? (in-s3? hash)]
                        (when-not compiled?
                          (.mkdirs (java.io.File. dir))
                          (spit filename source)
                          (compile hash filename)
                          (s3/upload-hash hash (str "/tmp/inky/" hash)))
                        (hp/html5
                          [:head]
                          [:body
                           [:div.canvas]
                           [:script {:type "text/javascript"
                                     :src (str "http://f.inky.cc/" hash "/code.js")}]])))))

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
