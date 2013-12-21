(ns my.preview
  (:require [inky.ajax :refer (ajax)]
            [inky.canvas :refer (page-style!)]
            [dommy.core :as dom]
            [clojure.string :as str]
            [cljs.core.async :as async
             :refer [<!-- -->! chan put! timeout]])
  (:require-macros [dommy.macros :refer [sel1 node]]
                   [cljs.core.async.macros :refer [go go-loop]]))

(enable-console-print!)

(defn prefix
  "Given a css3 prop and value, return a map with all vendor prefixed
  props."
  [k v]
  (->> ["-webkit" "-moz" "-ie" "-o"]
       (map #(str % "-" (name k)))
       (cons (name k))
       (map #(vector % v))
       (into {})))

(def full-size {:width "100%" :height "100%"})

(page-style!
  "html, body, .canvas" full-size
  :body {:margin "0"
         :padding "0"
         :font-family "'Helvetica Neue', Arial, sans-serif"}
  :.flip-container (merge
                     (prefix :perspective "1000px")
                     {:display "inline-block" :width "25%"})

  ".flip-container.hover .flipper" (prefix :transform "rotateY(180deg)")
  ".flip-container.hover .back" {:z-index "3"}
  ".front, .back" (prefix :backface-visibility "hidden")

  ".front img, .back img" {:width "100%"}

  :.front {:z-index "0"
           :background-size "cover"
           :position "relative"}

  ".image-meta" {:position "absolute"
                 :bottom "0" :left "0" :right "0"
                 :background-color "rgba(0,0,0,0.6)"
                 :z-index "3"
                 :padding "10px"
                 :font-size "12px"
                 :color "white"}
  ".image-meta a" {:color "#428bca"}
  ".image-meta a:hover" {:color "#2a6496"}
  :.back (merge
           (prefix :transform "rotateY(180deg)")
           {:position "absolute"
            :top "0"
            :left "0"
            :right "0"
            :bottom "0"
            :background-color "white"})
  :.flipper (merge
              (prefix :transition "0.3s ease")
              (prefix :transform-style "preserve-3d")
              {:position "relative"})
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

(defn js-timeout [f ms]
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
                (js-timeout
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

(defn $card [chan {:keys [image link comments-count username caption-text]}]
  (let [$front-link (node [:a {:href link :target "_blank"} "@" username])
        $back-link (node [:a {:href link :target "_blank"} "@" username])
        $front-img (node [:img {:src image}])
        $back-img (node [:img {:src image}])
        $front (node
                 [:div.front
                  $front-img
                  [:div.image-meta
                   $front-link]])
        $back (node
                [:div.back
                 $back-img
                 [:div.image-meta
                  $back-link]])
        $n (node [:div.flip-container
                  [:div.flipper
                   $front
                   $back]])
        front (atom true)
        update (fn [{:keys [image link comments-count username caption-text]}]
                 (let [$img (if @front $back-img $front-img)
                       $link (if @front $back-link $front-link)]
                   (dom/set-attr! $img :src image)
                   (dom/set-attr! $link :href link)
                   (dom/set-text! $link (str "@" username)))
                 (swap! front not))]
    (go
      (while true
        (let [ig (<! chan)]
          (update ig)
          (<! (timeout (+ 1000 (rand 2000))))
          (dom/toggle-class! $n :hover))))
    (dom/listen! $front-link :click #(.stopPropagation %))
    (dom/listen! $back-link :click #(.stopPropagation %))
    $n))

(defn parse-ig-res [res]
  (->> (js->clj res :keywordize-keys true)
       :data
       (map #(hash-map
               :caption-text (-> % :caption :text)
               :username (-> % :user :username)
               :comments-count (-> % :comments :count)
               :image (-> % :images :low_resolution :url)
               :link (:link %)))
       (filter :image)))

(defn run-loop [chans]
  (let [ig-res-chan (chan)]
    (go
      (while true
        (<! (timeout 5000))
        (jsonp
          ;; Instagram Endpoint
          (str "https://api.instagram.com/v1/media/popular"
               "?client_id=602cd47b69b642dd8b18fe54255f93cd")
          (fn [res]
            (put! ig-res-chan res))
          10000
          (fn [& args]))
        (let [res (<! ig-res-chan)]
          (doseq [[res chan] (map #(vector %1 %2)
                               (parse-ig-res res)
                               (shuffle chans))]
            (put! chan res)))))))

(defn results-handler [canvas]
  (fn [res]
    (let [n 16
          grams (take n (parse-ig-res res))
          chans (repeatedly n #(chan))]
      (dom/clear! canvas)
      (dom/append! canvas
        (map #($card %1 %2) chans grams))
      (run-loop chans))))

(def canvas (sel1 :.canvas))

(dom/append! canvas [:h1.error "Loading..."])

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
