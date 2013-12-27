(ns inky.env
  "Shell environment helpers."
  (:require [clojure.string :as str])
  (:refer-clojure :exclude (int str)))

(defn clj->env [sym-or-str]
  (-> sym-or-str
      name
      (str/replace #"-" "_")
      (str/upper-case)))

(defn env
  "Retrieve environment variables by clojure keyword style.
   ex. (env :user) ;=> \"zk\". Called w/o default throws an
  exception."
  [sym & [default]]
  (or (System/getenv (clj->env sym))
      default))

(defn int
  "Retrieve and parse int env var."
  [sym & [default]]
  (if-let [env-var (env sym)]
    (if (integer? env-var)
      env-var
      (Integer/parseInt env-var))
    default))

(defn str
  "Retrieve and parse string env var."
  [sym & [default]]
  (env sym default))

(defn bool
  [sym & [default]]
  (if-let [env-var (env sym default)]
    (Boolean/parseBoolean env-var)
    default))
