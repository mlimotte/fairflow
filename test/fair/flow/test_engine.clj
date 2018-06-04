(ns fair.flow.test-engine
  (:require
    [clojure.test :refer :all]
    [fair.flow.engine :refer :all]
    [fair.flow.datastore :as ds]
    [fair.util.lang :as lang]
    [fair.flow.engine :as engine]))

(defn sample-step-fn
  [& args]
  (count args))

(def sample-flow-config
  (normalize-flow-config
    {:flow-a {:trigger "foo"
              :type    "menu"
              :name    "A"}
     :flow-b {:type "fair.flow.test-engine/sample-step-fn"
              :name "B"}
     :flow-c {:trigger ["foo" "bar"]
              :steps   [{:type "menu3", :name "C1", :args {:a 1}}
                        {:type "menu4", :name "C2",}]}}))

(deftest test-key-path
  (are [s expected]
    (= (key-path s) expected)
    "" []
    nil []
    "x" [:x]
    "a.b" [:a :b])
  (is (thrown? IllegalArgumentException (key-path "a b")))
  (is (thrown? IllegalArgumentException (key-path 1))))

(deftest test-normalize-flow-config
  (is (= sample-flow-config
         {:flow-a {:trigger "foo"
                   :name    "flow-a"
                   :steps   [{:type "menu"
                              :name "A"
                              :idx  0}]}
          :flow-b {:name  "flow-b"
                   :steps [{:type "fair.flow.test-engine/sample-step-fn"
                            :name "B"
                            :idx  0}]}
          :flow-c {:trigger ["foo" "bar"]
                   :name    "flow-c"
                   :steps   [{:type "menu3", :name "C1", :args {:a 1}, :idx 0}
                             {:type "menu4", :name "C2", :idx 1}]}})))

(deftest test-trigger-matches
  (is (= (map :name (trigger-matches sample-flow-config "foo"))
         ["flow-a" "flow-c"])))

(deftest test-get-step-fn
  (is (= (get-step-fn {"menu" identity}
                      (-> sample-flow-config :flow-a :steps first))
         identity))
  (is (= (get-step-fn {"menu" identity}
                      (-> sample-flow-config :flow-b :steps first))
         #'fair.flow.test-engine/sample-step-fn))
  (is (thrown? ClassNotFoundException
               (get-step-fn nil
                            (-> sample-flow-config :flow-a :steps first)))))

(deftest test-mk-parse-callback
  (is (= (mk-callback-str "100" "FN" "s1") "100.FN.s1"))
  (is (= (mk-callback-str "100" "FN" "s2" "L" "L2") "100.FN.s2.L.L2"))
  (let [callback (parse-callback (mk-callback-str "100" "FN" "s3"))]
    (are [k expected] (= (get callback k) expected)
                      :session-id "100"
                      :flow-name "FN"
                      :step-name "s3"
                      :local nil))
  (let [callback (parse-callback (mk-callback-str "101" "FN" "s4" "L1"))]
    (is (= (get callback :local) ["L1"]))))

(deftest test-process-actions
  (let [var1    (atom 0)
        var2    (atom 100)
        context {:flow-name     "my-flow"
                 :step-idx      0
                 :step-name     "step1"
                 :extra-context nil
                 :args          {:extra 10}
                 :trigger       "start"
                 :session-id    "1"}]

    ; Test: actions as a collection of tuples
    (process-actions context
                     {:increment-var1 (fn [_ _ v] (swap! var1 + v))
                      ; Test: Access a value in context
                      :increment-var2 (fn [context _ v] (swap! var2 + v (-> context :args :extra)))}
                     {}
                     ; Test: Same action can be used multiple times
                     [[:increment-var1 5]
                      [:increment-var1 2]
                      [:increment-var2 2]])
    (is (= @var1 7))
    (is (= @var2 112))

    ; Test: actions can be a Map
    (process-actions context
                     {:increment-var1 (fn [_ _ v] (swap! var1 + v))}
                     {}
                     {:increment-var1 100})
    (is (= @var1 107))
    (is (= @var2 112))

    ; Test: session state mutations
    (let [var3    (atom 1000)
          result (process-actions context
                                  {:increment-var3 (fn [_ existing-state [k v]]
                                                     (swap! var3 + (:x existing-state) v)
                                                     (->ActionSessionMutation {:foo {k k}}))}
                                  {:x 50}
                                  [[:increment-var3 [:a 100]]
                                   [:increment-var3 [:b 100]]])]
      (is (= @var3 1300))
      (= result {:foo {:a :a :b :b}}))

    ; Test: no handler for action
    (is (thrown? clojure.lang.ExceptionInfo
                 (process-actions context {} {} {:foo 100})))))

(deftest test-parse-flow-step
  (are [x expected] (= (parse-flow-step x) expected)
                    ; Happy Path
                    "flow.0" ["flow" "0"]
                    "flow" ["flow" nil]
                    "flow_a.step-1" ["flow_a" "step-1"]

                    ; Non Matches
                    "flow.step.1" nil
                    "" nil
                    nil nil
                    "flow a.step" nil
                    "flow.step 1" nil
                    ".a" nil))

(deftest test-get-from-map-with-*-default
  (are [m k expected]
    (= (get-from-map-with-*-default m k) expected)
    {:a 1} :a 1
    {:a 1} nil nil
    nil :a nil
    {:a 1 :* "foo"} :a 1
    {:a 1 :* "foo"} :b "foo"
    {:a 1 :* "foo"} nil "foo"))

(deftest test-next-step-specifier
  (let [flow-config
        (normalize-flow-config
          {:flow1 {:trigger ["foo" "bar"]
                   :steps   [{:type        "menu3",
                              :args        {:a 1}
                              :name        "One-A"
                              :transitions "_next"}
                             {:type        "menu4"
                              :name        "One-B"
                              :transitions {:val1 "flow2.Two-B"}}]}
           :flow2 {:steps [{:type "A"
                            :name "Two-A"}
                           {:type        "B"
                            :name        "Two-B"
                            :transitions "_auto"}]}})]
    (are [current-flow-name current-step-idx trans-value expected]
      (let [[flow step] (evaluate-transition flow-config (get flow-config current-flow-name)
                                             (nth (get-in flow-config [current-flow-name :steps])
                                                  current-step-idx)
                                             current-step-idx trans-value)]
        (= [(:name flow) (:name step)] expected))
      :flow1 0 "any1" ["flow1" "One-B"]
      :flow1 1 "val1" ["flow2" "Two-B"]
      :flow2 1 "flow1.One-A" ["flow1" "One-A"]
      :flow2 1 "flow1" ["flow1" "One-A"])))

(deftype SampleDatastore [all-sessions]
  ds/FlowEngineDatastore
  (new-session [this flow-version flow-name step-name]
    (let [len         (count @all-sessions)
          new-session {:id            len
                       :session-state {}
                       :step-states   {}}]
      (swap! all-sessions conj new-session)
      new-session))

  (session-id [this session]
    (str (:id session)))

  (get-session [this session-id]
    (nth @all-sessions (lang/as-long session-id)))

  (get-session-state [this session]
    (:session-state session))

  (get-step-state [this session flow-name step-name]
    (get-in session [:step-states flow-name step-name]))

  (store-session [this session flow-name step-name shared-state-mutations step-state]
    (let [idx              (lang/as-long (ds/session-id this session))
          old-session      (get @all-sessions idx)
          new-shared-state (lang/merge-maps (:session-state old-session)
                                            ; If shared-state-mutations is nil, use `{}`, so it
                                            ; doesn't overwrite existing :session-state value.
                                            (or shared-state-mutations {}))
          new-session      (-> old-session
                               (assoc :session-state new-shared-state)
                               (assoc-in [:step-states flow-name step-name] step-state))]
      (swap! all-sessions assoc idx new-session))))

(defn sample-step
  [callback-gen context data session-state step-state]
  (let [step-state {:foo (+ (:foo data 0) (:foo step-state 0))}]
    (map->StepResult
      {:actions                {:samp "VALUE1"}
       :step-state             step-state
       :shared-state-mutations {:bar true}
       :transition             "flow-c"})))

(defn sample-handler
  [calls context _ v]
  (swap! calls conj [context v]))

(deftest test-run-step
  (let [fconfig       (normalize-flow-config
                        {:flow-c {:type "steppy"
                                  :name "C"}
                         :flow-b {:type        "steppy"
                                  :name        "start"
                                  :transitions "_auto"}})
        all-sessions  (atom [])
        dstore        (->SampleDatastore all-sessions)
        new-session   (ds/new-session dstore nil nil nil)
        handler-calls (atom [])
        extra-context {:extra-context "req1"}]
    (run-step {:flow-config fconfig
               :aliases     {"steppy" fair.flow.test-engine/sample-step}
               :handlers    {:samp (partial fair.flow.test-engine/sample-handler handler-calls)}
               :datastore   dstore}
              new-session
              extra-context
              "start"
              {:foo 10}
              (:flow-b fconfig)
              (-> fconfig :flow-b :steps first))

    ; Actions/Handlers
    (is (= (count @handler-calls) 2))
    (is (= (-> @handler-calls first second) "VALUE1"))
    (let [context2 (-> @handler-calls second first)]
      (is (= (-> context2 :flow-name) "flow-c"))
      (is (= (-> context2 :step-name) "C"))
      (is (= (-> context2 :extra-context) extra-context)))
    (is (= (-> @handler-calls second second) "VALUE1"))

    ; Shared session state
    (is (= (ds/get-session-state dstore (first @all-sessions))
           {:bar true}))

    ; Step-state / Transition
    (is (= (ds/get-step-state dstore (first @all-sessions) "flow-c" "C")
           {:foo 10}))
    (is (= (ds/get-step-state dstore (first @all-sessions) "flow-b" "start")
           {:foo 10}))))

(deftest test-trigger-init
  (let [all-sessions  (atom [])
        dstore        (->SampleDatastore all-sessions)
        handler-calls (atom [])
        flow-config   (normalize-flow-config
                        {:flow-a {:trigger "foo"
                                  :type    "steppy"
                                  :name    "A"}
                         :flow-b {:type "die-if-we-get-here"
                                  :name "B"}
                         :flow-c {:trigger ["foo" "bar"]
                                  :steps   [{:type "steppy", :name "C1"}
                                            {:type "steppy", :name "C2"}]}})]
    (trigger-init
      (engine/map->FlowEngine
        {:flow-config flow-config
         :aliases     {"steppy" fair.flow.test-engine/sample-step}
         :handlers    {:samp (partial fair.flow.test-engine/sample-handler handler-calls)}
         :datastore   dstore})
      "foo"
      {}
      {:foo 5})
    (is (= (count @all-sessions) 2))))