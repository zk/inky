(ns instagram.api
  (:require [inky.ajax :refer (ajax)]
            [inky.canvas :refer (page-style!)]
            [dommy.core :as dom])
  (:require-macros [dommy.macros :refer [sel1 node]]))

(defn timeout [f ms]
  (.setTimeout js/window f ms))

(defn clear-timeout [t]
  (.clearTimeout js/window t))

(defn jsonp
  "Make a JSONP request to `url`. 3rd and 4th parameter specify
   timeout and timeout callback respectively."
  [url callback &
   [timeout-ms on-timeout]]
  (let [cb-id (gensym)
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
    (dom/append! (sel1 :head) js-tag)))

(def canvas (sel1 :.canvas))

(page-style!
  :body {:margin "0"
         :padding "0"
         :font-family "'Helvetica Neue', Arial, sans-serif"}
  :img {:width "33.29999%"}
  :.error {:text-align "center"
           :margin-top "50px"
           :font-weight "normal"
           :font-size "24px"})

(jsonp
  ;; Instagram Endpoint
  (str "https://api.instagram.com/v1/media/popular"
       "?client_id=602cd47b69b642dd8b18fe54255f93cd")
  (fn [res]
    (let [urls (->> (js->clj res :keywordize-keys true)
                    :data
                    (map #(hash-map
                            :image (-> % :images :low_resolution :url)
                            :link (:link %)))
                    (filter :image))]
      (dom/append! canvas
        (for [{:keys [image link]} urls]
          (node [:a {:href link :target "_blank"} [:img {:src image}]])))))
  ;; Timeout
  2000
  ;; Error message on timeout
  #(do
     (dom/clear! canvas)
     (dom/append! canvas
       (node
         [:h1.error "We're having a problem contacting the Instagram API."]))))
