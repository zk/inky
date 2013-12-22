(ns inky.sketch
  (:require [dommy.core :as dom])
  (:use-macros [dommy.macros :only (sel1 node)]))

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
       (->> transform
            (map compile-transform)
            (apply str))
       "}"))

(defn page-style! [rules]
  (dom/append!
    (sel1 :body)
    (node
      [:style {:type "text/css"}
       (->> (partition 2 rules)
            (map compile-rule)
            (interpose " ")
            (apply str))])))

(defn content! [& body]
  (let [s (sel1 :.sketch)]
    (dom/clear! s)
    (dom/append! s)))

(defn prefix
  "Given a css3 prop and value, return a map with all vendor prefixed
  props."
  [k v]
  (->> ["-webkit" "-moz" "-ie" "-o"]
       (map #(str % "-" (name k)))
       (cons (name k))
       (map #(vector % v))
       (into {})))

(def meyer-reset ["html, body, div, span, applet, object, iframe, h1,
  h2, h3, h4, h5, h6, p, blockquote, pre, a, abbr, acronym, address,
  big, cite, code, del, dfn, em, img, ins, kbd, q, s, samp, small,
  strike, strong, sub, sup, tt, var, b, u, i, center, dl, dt, dd, ol,
  ul, li, fieldset, form, label, legend, table, caption, tbody, tfoot,
  thead, tr, th, td, article, aside, canvas, details, embed, figure,
  figcaption, footer, header, hgroup, menu, nav, output, ruby,
  section, summary, time, mark, audio, video"
                  {:margin "0"
                   :padding "0"
                   :border "0"
                   :font-size "100%"
                   :font "inherit"
                   :vertical-align "baseline"}])

(def default-styles
  (concat meyer-reset
    ["*" {:padding "0px"
          :margin "0px"}
     "html, body" {:width "100%"
                   :height "100%"}
     "body" {:font-family "'Helvetica Neue', Arial, sans-serif;"}
     "h1" {:font-size "30px"
           :font-weight "500"}
     "h2" {:font-size "26px"}
     "h3" {:font-size "22px"}
     "h4" {:font-size "18px"}]))
