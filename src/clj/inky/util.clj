(ns inky.util
  (:require [cheshire.core :refer (parse-string)]))

(defn from-json [json-str]
  (parse-string json-str true))
