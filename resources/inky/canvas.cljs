(ns inky.canvas
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

(defn page-style! [& rules]
  (dom/append!
    (sel1 :body)
    (node
      [:style {:type "text/css"}
       (->> (partition 2 rules)
            (map compile-rule)
            (interpose " ")
            (apply str))])))
