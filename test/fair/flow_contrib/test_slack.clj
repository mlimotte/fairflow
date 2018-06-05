(ns fair.flow-contrib.test-slack
  (:require
    [clojure.test :refer :all]
    [fair.flow-contrib.slack :refer :all]
    [camel-snake-kebab.core :as csk]))

(deftest test-button
  (is (= (button "Hello Foo")
         {:type  "button"
          :text  "Hello Foo"
          :name  "hello-foo"}))
  (is (= (button {:text "Hello Foo" :value "A"})
         {:type  "button"
          :text  "Hello Foo"
          :name  "hello-foo"
          :value "A"})))

(deftest test-deep-key-transform
         (is (= (deep-key-kebab
                  csk/->snake_case_keyword
                  {:aA        {"foo-bar" 1},
                   "baz_quux" "other-1"})
                {:a_a      {:foo_bar 1},
                 :baz_quux "other-1"})))
