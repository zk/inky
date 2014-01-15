(ns inky.sketch
  (:require [clojure.string :as str]
            [inky.util :as util]
            [inky.common :as common]))

(defn $compiling-page []
  (common/$layout
    {:head [[:meta {:http-equiv "refresh" :content "6"}]]
     :body-class "compiling-page"
     :content [:div
               [:h1 "Compiling!"]
               [:p "This should take less than 30 seconds, so sit tight and we'll load the results, automatically, when they're ready."]
               [:p "Results are cached for subsequent loads."]
               [:div.box.animate]]}))

(def link-re #"(([A-Za-z]{3,9}:(?:\/\/)?)(?:[-;:&=\+\$,\w]+@)?[A-Za-z0-9.-]+|(?:www.|[-;:&=\+\$,\w]+@)[A-Za-z0-9.-]+)((?:\/[\+~%\/.\w-_]*)?\??(?:[-\+=&;%@.\w_]*)#?(?:[\w]*))?")

(defn format-doc [s]
  (when s
    (-> s
        (str/replace link-re (fn [[href & rest]]
                               (str "<a href=\"" href "\">" href "</a>")))
        (str/replace #"\n\n" "<br /><br />"))))

(defn $sketch-page [{:keys [login gist-id compile-error sketch-url
                            ns doc user source inky-version created]}]
  (let [main-url (str "/" login "/" gist-id)
        sketch-url (str "/" login "/" gist-id "/sketch")
        gist-url (str "https://gist.github.com/" login "/" gist-id)
        user-url (str "https://github.com/" login)]
    (common/$layout
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
