(ns first
  "Let's start out with something simple."
  (:require [dommy.core :as dom]
            [inky.canvas :as canvas])
  (:use-macros [dommy.macros :only (node sel1)]))

(canvas/page-style!
  :div.box {:position "absolute"
            :top "48%"
            :bottom "48%"
            :right "50px"
            :left "50px"
            :background-color "#c0392b"
            :transition ["top 0.2s"
                         "bottom 0.2s"
                         "border-radius 0.2s"
                         "background-color 0.2s"]}
  :div.over {:top "20%"
             :bottom "20%"
             :background-color "#27ae60"})

;; Save box node for later. Note that `node` returns a native HTML
;; element object
(def box (node [:div.box]))

(dom/append! (sel1 :.canvas) box)

(dom/listen! box :mouseover #(dom/add-class! box :over))

(dom/listen! box :mouseout #(dom/remove-class! box :over))
