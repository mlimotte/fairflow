(ns fair.flow.util.test-lang
  (:require
    [clojure.test :refer :all]
    [fair.flow.util.lang :refer :all]))

(deftest test-as-long
  (are [x expected]
    (= (as-long x) expected)
    "100" 100
    "0" 0
    1.2 1
    "2.3" 2))

(deftest test-simple-kebab
  (are [s expected]
    (= (simple-kebab s) expected)
    nil nil
    "" ""
    " " ""
    "foo" "foo"
    "FoO" "foo"
    "foo-bar" "foo-bar"
    "Foo Bar  " "foo-bar"
    "Foo.Bar  " "foo.bar"
    "Foo1Bar" "foo1bar"))

(defn foo [])
(deftest test-resolve-var-by-name
  ; Happy path
  (is (= (resolve-var-by-name "clojure.core/+") #'+))
  (is (= (resolve-var-by-name "fair.flow.util.test-lang/foo") #'foo))
  ; Failure cases
  (is (thrown? ClassNotFoundException (resolve-var-by-name nil)))
  (is (thrown? ClassNotFoundException (resolve-var-by-name "")))
  (is (thrown? ClassNotFoundException (resolve-var-by-name "fair.flow.util.test-lang.foo")))
  (is (thrown? ClassNotFoundException (resolve-var-by-name "fair.flow.util.test-lang/nothing"))))

(deftest test-merge-maps
  (is (= (merge-maps {:a {:b {:c {:d 1 :e 1}}}}
                     {:a {:b {:c {:e 2 :f 2}}}})
         {:a {:b {:c {:d 1 :e 2 :f 2}}}}))
  (is (= (merge-maps {:a 1} {:b 2})
         {:a 1 :b 2}))
  (is (= (merge-maps {:a {:b 1}} {:a 2})
         {:a 2}))
  (is (= (merge-maps {:a 1 :c 1} {:a {:b 2}})
         {:a {:b 2} :c 1}))
  (is (= (merge-maps {:a 1 :c 1} nil)
         nil))
  (is (= (merge-maps nil {:a {:b 1}})
         {:a {:b 1}}))
  (is (= (merge-with merge-maps {:a {:b 1}})
         {:a {:b 1}}))
  (is (= (merge-maps {} {:a {:b 1}})
         {:a {:b 1}})))
