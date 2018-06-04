(ns fair.flow-contrib.test-datomic-datastore
  (:require
    [clojure.test :refer :all]
    [fair.flow.datastore :as ds]
    [fair.flow-contrib.datomic-datastore]
    [fair.util.lang :as lang]))

(comment
  (ds/new-session (:datastore @the-system) "TEST1d" 0)
  (def s1 (ds/get-session ds 17592186045434)) ;"TEST1d" 0
  (map (partial get s1) (keys s1)))

(def test-keys [:flow-session/flow-version :flow-session/current-flow
                :flow-session/current-step-name :flow-session/shared-state])

(deftest test-DatomicDatastore
  (let [conn              (fair.flow-contrib.datomic-datastore/setup "datomic:mem://test")
        store             (fair.flow-contrib.datomic-datastore/->DatomicDatastore conn)
        session           (ds/new-session store "v1" "flow-a" "step1")
        session-id        (ds/session-id store session)
        retrieved-session (ds/get-session store session-id)]

    ; Test: session
    (is (pos? (lang/as-long session-id)))
    (is (= (map #(get session %) test-keys)
           ["v1" "flow-a" "step1" nil]))
    (is (= (map #(get session %) test-keys) (map #(get retrieved-session %) test-keys)))

    ; Test: session storage
    (ds/store-session store retrieved-session
                      "flow-a" "step1"
                      {:bar 1}
                      {:foo 1})
    (is (= (ds/get-step-state store retrieved-session "flow-a" "step1"))
        {:foo 1})
    (is (= (ds/get-session-state store retrieved-session))
        {:bar 1})

    (ds/store-session store retrieved-session
                      "flow-a" "step2"
                      {:baz 1}
                      {:foo 2})
    ; test original step remains unchanged
    (is (= (ds/get-step-state store retrieved-session "flow-a" "step1"))
        {:foo 1})
    (is (= (ds/get-step-state store retrieved-session "flow-a" "step2"))
        {:foo 2})
    (is (= (ds/get-session-state store retrieved-session))
        {:bar 1 :baz 1})))
