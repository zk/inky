(ns inky.env-test
  (:require [inky.env :as env]
            [clojure.test :refer :all]))

(deftest test-clj->env
  (are [kw result] (= result (env/clj->env kw))
       :path "PATH"
       :foo-bar "FOO_BAR"))

(deftest test-env
  (is (env/str :path))
  (is (nil? (env/str :some-random-env-var)))
  (is (= "foo" (env/str :some-random-env-var "foo"))))

(deftest test-env-defaults
  (is (not (nil? (env/str :some-random-env-var "foo"))))
  (is (= 10 (env/int :some-random-env-var 10))))
