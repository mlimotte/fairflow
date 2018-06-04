(ns fair.flow-contrib.test-slack
  (:require
    [clojure.test :refer :all]
    [fair.flow-contrib.slack :refer :all]))

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
