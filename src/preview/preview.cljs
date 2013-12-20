(ns my.preview
  (:require [inky.ajax :refer (ajax)]
            [inky.canvas :refer (page-style!)]
            [dommy.core :as dom]
            [clojure.string :as str])
  (:require-macros [dommy.macros :refer [sel1 node]]))

(page-style!
  :body {:margin "0"
         :padding "0"
         :font-family "'Helvetica Neue', Arial, sans-serif"}
  :img {:max-width "100%"}
  :.flip-container {:perspective "1000px"
                    :-webkit-perspective "1000px"
                    :-moz-perspective "1000px"
                    :display "inline-block"
                    :width "25%"}

  ".flip-container.hover .flipper"
  {:-webkit-transform "rotateY(180deg)"
   :transform "rotateY(180deg)"}

  ".flip-container.hover .back"
  {:z-index "3"}

  ".front, .back"
  {:backface-visiblity "hidden"
   :-webkit-backface-visibility "hidden"
   :-moz-backface-visibility "hidden"
   :-ie-backface-visibility "hidden"
   :cursor "pointer"}

  ".front img, .back img" {:opacity "0"}

  :.front {:z-index "0"}
  :.back {:transform "rotateY(180deg)"
          :-webkit-transform "rotateY(180deg)"
          :-moz-transform "rotateY(180deg)"
          :-ie-transform "rotateY(180deg)"
          :position "absolute"
          :top "0"
          :left "0"
          :right "0"
          :bottom "0"
          :background-color "white"
          :padding "30px"}
  :.flipper {:transition "0.3s ease"
             :transform-style "preserve-3d"
             :-webkit-transform-style "preserve-3d"
             :-moz-transform-style "preserve-3d"
             :-ie-transform-style "preserve-3d"
             :position "relative"}
  :.meta {:font-size "12px"}
  ".meta .user" {:color "#555"}
  :.error {:text-align "center"
           :margin-top "50px"
           :font-weight "normal"
           :font-size "24px"})

(defn ellipsis [n s]
  (cond
    (not s) s
    (<= (count s) n) s
    :else (str (str/trim
                 (->> s
                      (take (- n 3))
                      (apply str)))
               "...")))

(defn timeout [f ms]
  (.setTimeout js/window f ms))

(defn clear-timeout [t]
  (.clearTimeout js/window t))

(defn jsonp
  "Make a JSONP request to `url`. 3rd and 4th parameter specify
   timeout and timeout callback respectively."
  [url callback &
   [timeout-ms on-timeout]]
  (let [cb-id (str (gensym))
        js-tag (node [:script {:type "text/javascript"
                               :src (str url "&callback=" cb-id)}])
        timer (when timeout-ms
                (timeout
                  (fn []
                    (aset js/window cb-id nil)
                    (dom/remove! js-tag)
                    (on-timeout))
                  timeout-ms))]
    ;; set callback fn on window object
    (aset js/window (str cb-id) (fn [res]
                                  (clear-timeout timer)
                                  (callback res)
                                  ;; After callback, clear property on
                                  ;; window and remove script tag
                                  (aset js/window cb-id nil)
                                  (dom/remove! js-tag)))
    (dom/append! (sel1 :body) js-tag)))

(defn $card [{:keys [image link comments-count username caption-text]}]
  (let [$front (node
                 [:div.front
                  {:style (str "background-image: url('" image "')")}
                  [:img {:src image}]])
        $back (node
                [:div.back
                 [:div.meta
                  [:div.caption-text (ellipsis 100 caption-text)]
                  [:br]
                  [:div.user "@" username]]])
        $n (node [:div.flip-container
                  [:div.flipper
                   $front
                   $back]])]
    (dom/listen! $front :click #(dom/toggle-class! $n :hover))
    (dom/listen! $back :click #(dom/toggle-class! $n :hover))
    $n))


(defn results-handler [canvas]
  (fn [res]
    (let [grams (->> (js->clj res :keywordize-keys true)
                     :data
                     (map #(hash-map
                             :caption-text (-> % :caption :text)
                             :username (-> % :user :username)
                             :comments-count (-> % :comments :count)
                             :image (-> % :images :low_resolution :url)
                             :link (:link %)))
                     (filter :image)
                     (take 16))]
      (dom/append! canvas
        (map $card grams)))))

(def canvas (sel1 :.canvas))

(jsonp
  ;; Instagram Endpoint
  (str "https://api.instagram.com/v1/media/popular"
       "?client_id=602cd47b69b642dd8b18fe54255f93cd")
  (results-handler canvas)
  ;; Timeout
  10000
  ;; Error message on timeout
  #(do
     (dom/clear! canvas)
     (dom/append! canvas
       (node
         [:h1.error "We're having a problem contacting the Instagram API."]))))
