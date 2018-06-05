(ns fair.flow-contrib.test-mustache-render
  (:require
    [clojure.test :refer :all]
    [clojure.string :as string]
    [fair.flow-contrib.mustache-render :refer :all]
    [fair.flow.util.lang :as lang]))

(deftest test-string-values-render
  (are [data-map s expected]
    (= (#'fair.flow-contrib.mustache-render/string-values-render data-map s) expected)
    {:foo 1} "x{{foo}}x" "x1x"
    {:foo-bar 1} "x{{foo-bar}}x" "x1x"
    {:foo 1} nil nil
    {:foo 1} "" ""
    {:foo 1} "foo" "foo"
    {:foo {:bar "baz"}} "x {{foo.bar}}" "x baz"
    {:foo {:bar "baz"}} "x {{ foo.bar }}" "x baz"
    {:foo {:bar "baz"}} "x {{ foo.bar }}" "x baz"
    {:foo {"bar" "baz"}} "x {{ foo.bar }}" "x baz"
    {:foo {"bar" "baz"}} "x {{ foo.bar }}" "x baz"
    {:foo {"bar" "baz"}} 1 1
    {:foo {"bar" "baz"}} {:foo 1} {:foo 1}))

(deftest test-deep-string-values-render
  (are [data-map struc expected]
    (= (deep-string-values-render data-map struc) expected)
    {:foo 1} {:a "x{{foo}}x"} {:a "x1x"}
    {:foo 1} nil nil
    {:foo 1} "" ""
    {:foo 1} {:a "foo"} {:a "foo"}
    ; a more complex structure:
    {:foo {:bar "baz"}}
    {:a [{} {:b "x{{foo.bar}}"}] :c {:d "x{{foo.bar}}"}}
    {:a [{} {:b "xbaz"}] :c {:d "xbaz"}}
    ; Test: non existant key, renders as ""
    {:foo 1} {:a "x{{bar}}x"} {:a "xx"}
    ; Test: A function invocation
    {:foo 1, :plus (fn [x] (apply + (map lang/as-long (string/split x #"\s+"))))}
    {:a "x{{#plus}}7 4{{/plus}}x"}
    {:a "x11x"}))
