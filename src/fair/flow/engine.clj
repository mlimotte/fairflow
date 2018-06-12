(ns fair.flow.engine
  (:require
    [clojure.tools.logging :as log]
    [clojure.spec.alpha :as spec]
    [clojure.string :as string]
    [fair.flow.util.lang :as lang]
    [fair.flow.datastore :as ds]
    [fair.flow.util.spec :as fus]))

(defrecord FlowEngine
  ; Fields:
  ;   TODO ... add docs for other fields
  ;   :hooks (Map) -
  ;     :context-enrichment - fn of context and session, return an enriched Context
  ;       Map that will be used for steps, actions, rendering, etc.
  ;     :renderer (fn) - A function of the Context and a structure whose strings
  ;       should be rendered with interpolation.
  [datastore flow-config flow-version hooks aliases handlers])

(defrecord StepResult [actions transition shared-state-mutations step-state])

(spec/def ::nil-kw-map (spec/nilable (spec/map-of keyword? any?)))

(spec/def ::spec-result
  (spec/keys :opt-un [::actions ::transition ::shared-state-mutations ::step-state]))

(spec/def ::actions (spec/or :m ::nil-kw-map
                             :lst (spec/coll-of (spec/cat :k keyword? :v any?))))

(spec/def ::transition (spec/nilable ::fus/non-blank-str))
(spec/def ::shared-state-mutations ::nil-kw-map)
(spec/def ::step-state ::nil-kw-map)

(defrecord Callback [session-id flow-name step-name local])

;(spec/def ::context
;  (spec/keys :req-un [::flow-name ::step-idx ::step-name ::extra
;                      ::args ::trigger ::session-id]))
;(spec/def ::flow-name ::fus/non-blank-str)
;(spec/def ::step-idx int?)
;(spec/def ::step-name ::fus/non-blank-str)
;(spec/def ::extra (spec/nilable map?))
;(spec/def ::args (spec/nilable map?))
;(spec/def ::trigger (spec/or :name ::fus/non-blank-str :callback (partial instance? Callback)))
(spec/def ::context
  (spec/keys :req-un [::flow ::step ::global ::session ::args]))
(spec/def ::flow (spec/keys :req-un [::name]))
(spec/def ::step (spec/keys :req-un [::name ::args]))
(spec/def ::global (spec/nilable map?))
(spec/def ::args (spec/nilable map?))
(spec/def ::trigger (spec/or :name ::fus/non-blank-str :callback (partial instance? Callback)))
(spec/def ::session (spec/keys :req-un [::step-state ::shared-state ::id]))
; Session key must be a non-blank string and can not contain dots (otherwise we
; wouldn't be able to parse it out of the callback id.
(spec/def ::id (partial re-find #"^[^\.]+$"))

; Session key must be a non-blank string and can not contain dots (otherwise we
; wouldn't be able to parse it out of the callback id.
;(spec/def ::session-id (partial re-find #"^[^\.]+$"))

(spec/def ::step-fn-args
  (spec/cat :callback-gen fn?
            :context ::context
            :data map?))

(defrecord ActionSessionMutation [state])

(defn throw-misconfig
  [msg & [data]]
  (throw (ex-info msg (assoc data ::type ::misconfiguration))))

(defn node-name
  "Produce a node name based on the flow and step specified in the context.
  This is mainly for logs and error messages."
  [context]
  (format "Node[%s/%s/%s]" (:flow-name context) (:step-name context) (:step-idx context)))

(defn key-path
  [s]
  (cond
    (and (string? s) (string/blank? s))
    []

    (and (string? s) (re-find #"^[\w\._-]+$" s))
    (map keyword (string/split s #"\."))

    (nil? s)
    []

    :else
    (throw (IllegalArgumentException.
             (format "`s` must be a String of word characters and dots, but was `%s`(%s)."
                     s (type s))))))

(defn- normalize-steps
  "Given a raw step entry, normalize it's value."
  [steps]
  (map-indexed
    (fn [idx step] (assoc step :idx idx))
    steps))

(defn normalize-flow-config
  "Normalize the flow config as follows.
  - Add :steps keys when missing, with rest of flow Map as the single step.
  - Add :name key on each flow.
  - Add :idx key on each Step.  "
  [raw-flow-config]
  ; TODO defspec to validate flow-config
  (into {}
        (map (fn [[flow-name flow]]
               (vector
                 flow-name
                 (assoc
                   (if (:steps flow)
                     (assoc flow :steps (normalize-steps (:steps flow)))
                     (let [flow-level-keys [:trigger]
                           base            (select-keys flow flow-level-keys)
                           step            (apply dissoc flow flow-level-keys)]
                       (assoc base
                         :steps (normalize-steps [step]))))
                   :name (name flow-name))))
             raw-flow-config)))

(defn hash-for-version
  "Calculate a flow version identifier. We do this as a hash of the config."
  [data]
  ; TODO calc an MD5 for flow-config
  "1")

(defn trigger-matches
  "Find flows in the config that match the given trigger.
  `trigger` can be a String (test for equality) or a vector (test for presence)."
  [flow-config trigger]

  (prn "MARC 1 trigger  " trigger flow-config)

  (filter
    (fn [{flow-trigger :trigger}]
      (or (= flow-trigger trigger)
          (some #{trigger} flow-trigger)))
    (vals flow-config)))

(defn find-step
  "Find the step in the flow with the given name."
  [flow step-name]
  (->> flow :steps (filter #(= (:name %) step-name)) first))

(defn get-step-fn
  "Get the Step Function for the given flow and step-idx.
  Throws ClassNotFoundException if no valid :type is found"
  [aliases {step-type :type}]
  (or (get aliases step-type)
      (lang/resolve-var-by-name step-type)))

(defn mk-callback-str
  "Generate a callback string, which can later be parsed to identify the session/flow/step
  to callback into. In addition, `local` values can be passed, which are opaque to this
  fn, but are intended for use the step that is called back to."
  [session-id flow-name step-name & local]
  (apply ds/mk-dot-key session-id flow-name step-name local))

(defn parse-callback
  "Parse the callback string and return a Callback record."
  [s]
  (let [[session-id flow-name step-name & local] (ds/parse-dot-key s)]
    (->Callback session-id flow-name step-name local)))

;(defn process-actions
;  "Process the actions using the given context and handlers.
;  handlers that return ActionSessionMutation objects, will have those results
;  merged (in order), with the merged result used as return value of this
;  function.
;  Returns: A Map of shared session state mutations."
;  [context handlers session-state actions]
;  (->>
;    (for [[k v] actions
;          :let [handler (handlers k)]]
;      (if handler
;        (do (log/info "Running handler for" k)
;            (let [action-result (handler context session-state v)]
;              (if (instance? ActionSessionMutation action-result)
;                action-result)))
;        (throw-misconfig "No handler for action"
;                         {:action-name k
;                          :node        (node-name context)})))
;    doall
;    (apply merge-with lang/merge-maps)))
(defn process-actions
  "Process the actions using the given context and handlers.
  handlers that return ActionSessionMutation objects, will have those results
  merged (in order), with the merged result used as return value of this
  function.
  Returns: A Map of shared session state mutations."
  [context handlers actions]
  (->>
    (for [[k v] actions
          :let [handler (handlers k)]]
      (if handler
        (do (log/info "Running handler for" k)
            (let [
                  ;action-result (handler context (get-in context [:session :shared-state]) v)
                  action-result (handler context v)
                  ]
              (if (instance? ActionSessionMutation action-result)
                action-result)))
        (throw-misconfig "No handler for action"
                         {:action-name k
                          :node        (node-name context)})))
    doall
    (apply merge-with lang/merge-maps)))

(spec/fdef process-actions
           :args (spec/cat :context ::context
                           :handlers (spec/map-of keyword? fn?)
                           ;:session-state (spec/nilable map?)
                           :actions ::actions))

(defn parse-flow-step
  "Get the flow-name and step from step-specifier, which is a String, either
  `flow-name` or `flow-name.step-name`"
  [step-specifier]
  (if-let [m (re-find #"^([^. ]+)(\.([^. ]+))?$" (or step-specifier ""))]
    [(nth m 1) (nth m 3)]))

(defn get-from-map-with-*-default
  "Get the key, k, from Map, m. If not exists, get key :* from Map if it exists, else nil."
  [m k]
  (let [default (get m :*)]
    (get m k default)))

(defn evaluate-transition
  "Find the next [flow step-name] based on a transition value.
  `trans-value` is the `:transition` from the last StepResult."
  [flow-config current-flow step-name current-step-idx trans-value]

  ; TODO A single string as transition_config should become {"*" STRING_VAL}
  ;    - Special value `*` in map check should match any non-nil trans-value (iff there is no exact match)

  ; Step Functions need to return non-nil, in order for a transition config to be considered.
  (cond

    (nil? trans-value)
    nil

    (not (string? trans-value))
    (throw-misconfig
      "The transition in a StepResult must be a String if it is not nil."
      {:trans-value-type (type trans-value)
       :trans-value      trans-value
       :current-flow     (:name current-flow)
       :current-step     step-name})

    (string? trans-value)
    (let [_ (log/infof "Looking for next step specifier after %s/%s[%d], trans-value=%s"
                       (:name current-flow) step-name current-step-idx trans-value)

          transition-config
            (-> current-flow :steps (nth current-step-idx) :transitions (or "_next"))

          ret-val
            (cond

              (= transition-config "_next")
              (let [next-step-idx (inc current-step-idx)
                    new-flow      current-flow
                    step          (-> new-flow :steps (nth next-step-idx nil))]
                (if step
                  [new-flow step]
                  ; If there is no next step, just return nil
                  nil))

              (= transition-config "_terminal")
              nil

              :else
              (let [[part1 part2]
                    (parse-flow-step
                      (cond
                        (= transition-config "_auto") trans-value
                        ; If transition-config is a Map, we expect it to be a Map[keyword]
                        (map? transition-config) (get-from-map-with-*-default
                                                   transition-config
                                                   (keyword trans-value))))
                    new-flow? (get flow-config (keyword part1))]

                (cond
                  ; both parts, so this is a flow/step pair
                  (and new-flow? part2)
                  [new-flow? (find-step new-flow? part2)]

                  ; Single part, if it's a step-name in current flow, go with that...
                  (find-step current-flow part1)
                  [current-flow (find-step current-flow part1)]

                  ; Maybe it's a flow-name
                  (and new-flow? (-> new-flow? :steps first))
                  [new-flow? (-> new-flow? :steps first)]

                  ; else error
                  :else
                  ; In the case of an explicit step-specifier (not `next`), if there is no Step
                  ; then that is a Misconfiguration error.
                  (throw-misconfig
                    (cond
                      (= transition-config "_auto")
                      (format (str "step-specifier not found, expecting `%s` to match a flow or "
                                   "flow.step name, such as %s.")
                              trans-value (mapv name (keys flow-config)))
                      (map? transition-config)
                      (format "step-specifier not found, expecting `%s` to be one of %s"
                              (keyword trans-value) (keys transition-config))
                      :else
                      (format (str "step-specifier not found, transition `%s` not understood, "
                                   "expecting `_auto`, `_next`, `_terminal` or a Map")
                              transition-config))
                    {:part1 part1, :part2 part2}))))]

      (if ret-val
        (log/info "  Found next step: " (string/join "/" (map :name ret-val)))
        (log/info "  No next step."))
      ret-val)))

(defn full-context
  "
  Use this one fn for all of:
      - step fn calls (in run-step)
      - render (in run-step)
      - handlers (in run-step)
      / rule execution (this is just a subset of step fns)
  "
  [{:keys [datastore hooks] :as flow-engine} session flow step global]
  (let [enrichment (:context-enrichment hooks)
        renderer   (:renderer hooks)
        context1   (-> {:flow    (select-keys flow [:name])
                        :step    (select-keys step [:name :args])
                        :global  global
                        :session {:step-state   (ds/get-step-state datastore session
                                                                   (:name flow) (:name step))
                                  :shared-state (ds/get-session-state datastore session)
                                  :id           (ds/session-id datastore session)}}
                       (cond-> enrichment (enrichment session)))
        args       (if renderer (renderer context1 (:args step)) (:args step))]
    (assoc context1 :args args)))

(defn run-step
  "Run the specified flow + step.
  Note: session contains a flow-name and step-name, but those are the most recent
  persisted values of those arguments, so the passed in arguments will be used instead."
  [{:keys [flow-config aliases handlers datastore hooks] :as flow-engine}
   session global data flow step trigger
   & [depth]]
  (log/infof "Running step %s/%s" (:name flow) (:name step))

  (prn "MARC run-step session " session)

  (let [step-fn      (get-step-fn aliases step)
        flow-name    (:name flow)
        step-idx     (:idx step)
        step-name    (:name step)

        ;session-id (ds/session-id datastore session)
        ;session-state (ds/get-session-state datastore session)
        ;step-state    (ds/get-step-state datastore session flow-name step-name)
        ;context       {:flow-name  flow-name
        ;               :step-idx   step-idx
        ;               :step-name  step-name
        ;               :extra      extra
        ;               :args       (if renderer (renderer
        ;                                          session
        ;                                          {:session   session-state
        ;                                           :step      step-state
        ;                                           :flow      flow
        ;                                           :step-name step-name
        ;                                           :static    static-interpolation-context}
        ;                                          (:args step))
        ;                                        (:args step))
        ;               :trigger    trigger
        ;               :session-id session-id}
        ;step-res      (step-fn (partial mk-callback-str session-id flow-name step-name)
        ;                       context data session-state step-state)

        context      (full-context flow-engine session flow step global)
        callback-gen (partial mk-callback-str (get-in context [:session :id]) flow-name step-name)
        step-res     (step-fn callback-gen context data)]

    ; Process results
    ; TODO catch errors, here or around entire fn; get an error handler
    ;      from `handlers` (also do a log/error)
    (let [session-mutations-from-actions
          ;(process-actions context handlers (get-in context [:session :shared-state])
          ;                 (:actions step-res))
          (process-actions context handlers (:actions step-res))
          ]

      ; Update state
      (let [{session-mutations-from-step :shared-state-mutations step-state :step-state} step-res
            session-mutations (merge session-mutations-from-step session-mutations-from-actions)]
        (ds/store-session datastore session flow-name step-name session-mutations step-state)))

    ; Evaluate transitions
    (if-let [[next-flow step]
             (evaluate-transition flow-config flow step-name step-idx (:transition step-res))]
      (if (>= (or depth 0) 7)
        (throw (ex-info "Step has recursed too many times without user interaction"
                        {:depth       depth
                         ::type       "recursion-limit"
                         :next        [next-flow step]
                         :trans-value (:transition step-res)}))
        (run-step flow-engine
                  ; Get a fresh session for the next step.
                  (ds/get-session datastore (get-in context [:session :id]))
                  global data next-flow step trigger
                  (inc (or depth 0)))))))

(defn trigger-init
  "Trigger any matching flows.
  `global` and `data` are opaque to the engine, but are passed to the
  Step functions."
  [flow-engine trigger global data]
  (let [matches (trigger-matches (:flow-config flow-engine) trigger)]
    (log/info "Triggering" trigger (map :name matches))
    (doseq [flow matches
            :let [step    (-> flow :steps first)
                  session (and step (ds/new-session (:datastore flow-engine)
                                                    (:flow-version flow-engine)
                                                    (:name flow)
                                                    (:name step)
                                                    data))]]
      (if step
        (run-step flow-engine session global data flow step trigger)
        (throw-misconfig "Workflow has no steps for trigger "
                         {:trigger trigger, :flow (:name flow)})))))
