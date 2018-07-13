(ns fair.test-gsheets2
  (:require
    [clojure.test :refer :all]
    [flatland.ordered.map :refer [ordered-map]]
    [fair.gsheets2 :refer :all])
  (:import (clojure.lang ExceptionInfo)))

(deftest test-get-headers
  (with-redefs [fair.gsheets2/cell->clj
                identity
                fair.gsheets2/read-all-data-cells
                (constantly [["Ab" "c D" "ef gh" "z ij .K" "foo .0 .bar .1 .baz"]])]
    (is (= (get-headers nil)
           (ordered-map (list [:ab "A"]
                              [:c-d "B"]
                              [:ef-gh "C"]
                              [:z-ij-.k "D"]
                              [(keyword "foo-.0-.bar-.1-.baz") "E"])))))

  ; Test error cases (against assert-non-blank!)
  (with-redefs [fair.gsheets2/cell->clj           identity
                fair.gsheets2/read-all-data-cells (constantly [["A" ""]])]
    (is (thrown? ExceptionInfo (get-headers nil))))

  (with-redefs [fair.gsheets2/cell->clj           identity
                fair.gsheets2/read-all-data-cells (constantly [["A" nil]])]
    (is (thrown? ExceptionInfo (get-headers nil)))))
