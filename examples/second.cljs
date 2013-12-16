(ns blog.processes.core.redux
  "Slightly modified version of David Nolen's 10k processes using
   core.async post: http://swannodette.github.io/2013/08/02/100000-processes/"
  (:require [cljs.core.async :as async
             :refer [<! >! chan put! timeout]]
            [goog.dom.classes :as classes]
            [dommy.core :refer [append!]]
            [inky.canvas :refer [page-style!]])
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [dommy.macros :refer [sel1 node]]))

(def group-colors ["#27ae60"
                   "#f1c40f"
                   "#f39c12"
                   "#3498db"
                   "#e67e22"
                   "#d35400"
                   "#7f8c8d"
                   "#2c3e50"])

(def group-styles
  (->> group-colors
       count
       range
       (mapcat #(vector
                  (str ".group" %)
                  {:background-color (str (get group-colors %) " !important")
                   :color "white !important"}))))

(apply page-style!
  (concat
    group-styles
    [:body {:background-color "black"}
     :.cell {:width "20px"
             :height "20px"
             :display "inline-block"
             :text-align "center"
             :line-height "20px"
             :color "black"
             :background-color "black"
             :font-family "'Helvetica Neue', sans-serif"
             :font-size "12px"
             :transition "background-color 0.2s ease"}]))

(def num-cells 10000)

(defn set-html! [el s]
  (when el
    (set! (.-innerHTML el) s)))

(defn set-class! [el name]
  (when el
    (classes/set el (str name " cell"))))

;; Append `num-cells` cells to canvas, which will be updated
;; asynchronously by the render loop.
(defn gen-ui []
  (append! (sel1 :.canvas)
    (for [i (range num-cells)]
      [:div {:id (str "cell-" i) :class "cell"} "0"])))

(gen-ui)

;; State to hold group index to apply to a given cell.
(def group (atom 0))

(defn render! [queue]
  (let [g (str "group" @group)]
    (doseq [[idx v] queue]
      (let [cell (sel1 (str "#cell-" idx))]
        (set-html! cell v)
        (set-class! cell g)))
    (swap! group (fn [g] (mod (inc g) 5)))))

(defn render-loop [rate]
  (let [in (chan 1000)]
    (go (loop [refresh (timeout rate) queue []]
          (let [[v c] (alts! [refresh in])]
            (condp = c
              refresh (do (render! queue)
                        (<! (timeout 0))
                        (recur (timeout rate) []))
              in (recur refresh (conj queue v))))))
    in))

(let [render (render-loop 40)]
  (loop [i 0]
    (when (< i num-cells)
      (go (while true
            (<! (timeout (+ 1000 (rand-int 10000))))
            (>! render [(rand-int 10000) (rand-int 10)])))
      (recur (inc i)))))
