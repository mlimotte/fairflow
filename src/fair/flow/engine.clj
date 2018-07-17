(ns fair.flow.engine
  (:require
    [clojure.tools.logging :as log]
    [clojure.spec.alpha :as s]
    [clojure.string :as string]
    [java-time :as jtime]
    [fair.flow.util.lang :as lang]
    [fair.flow.datastore :as ds]
    [fair.flow.util.spec :as fus]))

; This protocol is not used by the core engine. It is made available for
; implementations that want to allow multiple flow engines to be active at once.
; See fair.flow.flow-contrib.slack-routes.
; The FlowEngine record itself, implements FlowEngineManager/get-engine. This
; way, you can use a FlowEngine as a FlowEngineManager that always returns itself.
(defprotocol FlowEngineManager
  (get-engine [this version])
  (load-engine [this version]))

(defrecord FlowEngine
  ; Fields:
  ;   TODO ... add docs for other fields
  ;   :hooks (Map) -
  ;     :context-enrichment - fn of context and session, return an enriched Context
  ;       Map that will be used for steps, actions, rendering, etc.
  ;     :renderer (fn) - A function of the Context and a structure whose strings
  ;       should be rendered with interpolation.
  [datastore flow-config flow-version hooks aliases handlers global]
  FlowEngineManager
  (get-engine [this version] this))

(defrecord StepResult [actions transition shared-state-mutations step-state])

(s/def ::nil-kw-map (s/nilable (s/map-of keyword? any?)))

(s/def ::spec-result
  (s/keys :opt-un [::actions ::transition ::shared-state-mutations ::step-state]))

(s/def ::actions (s/or :m ::nil-kw-map
                       :lst (s/coll-of (s/cat :k keyword? :v any?))))

(s/def ::transition (s/nilable ::fus/non-blank-str))
; N.B.: The next two (i.e. the state fields) require String keys (not keywords),
;       so that they will be compatible with non-clojure-aware datastores. E.g.
;       if the data is stored and read from json or yaml, it would write as a
;       (potentially qualified) keyword, but read back as a String.
(s/def ::shared-state-mutations (s/nilable (s/map-of string? any?)))
(s/def ::step-state (s/nilable (s/map-of string? any?)))

(defrecord Callback [session-id flow-name step-name local])

(s/def ::context
  (s/keys :req-un [::flow ::step ::global ::session ::trigger]
          :opt-un [::args]))
(s/def ::flow (s/keys :req-un [::name]))
(s/def ::step (s/keys :req-un [::name]
                      :opt-un [::args]))
(s/def ::global (s/nilable map?))
(s/def ::session (s/keys :req-un [::step-state ::shared-state ::id]))
; Session key must be a non-blank string and can not contain dots (otherwise we
; wouldn't be able to parse it out of the callback id.
(s/def ::id (partial re-find #"^[^\.]+$"))
(s/def ::args (s/nilable map?))

(s/def ::trigger (s/or :name ::fus/non-blank-str :callback (partial instance? Callback)))

(s/def ::step-fn-args
  (s/cat :callback-gen fn?
         :context ::context
         :data map?))

(defrecord ActionMutation [session-state])

(defn throw-misconfig
  [msg & [data]]
  (throw (ex-info msg (assoc data ::type ::misconfiguration))))

(defn key-path
  "A helper to parse a string into a path for use with `get-in`."
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

(defn date-as-version
  "A version string based on either the :_flow_version in the config, or if that
  doesn't exist, then the current date-time."
  [config]
  (or (:_flow_version config)
      (jtime/format "yyy-MM-dd'T'HH:mm:ss'Z'"
                    (jtime/with-zone-same-instant (jtime/zoned-date-time) "UTC"))))

(defn trigger-matches
  "Find flows in the config that match the given trigger.
  `trigger` can be a String (test for equality) or a vector (test for presence)."
  [flow-config trigger]
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

(defn process-actions
  "Process the actions using the given context and handlers.
  handlers that return ActionMutation objects, will have those results
  merged (in order), with the merged result used as return value of this
  function.
  Returns: A Map of shared session state mutations."
  [context handlers actions]
  (->>
    (for [[k v] actions
          :let [handler (handlers k)]]
      (if handler
        (do (log/info "Running handler for" k)
            (let [action-result (handler context v)]
              (if (instance? ActionMutation action-result)
                action-result)))
        (throw-misconfig "No handler for action"
                         {:action-name k
                          :flow        (-> context :flow :name)
                          :step        (-> context :step :name)})))
    doall
    (apply merge-with lang/merge-maps)))

(s/fdef process-actions
        :args (s/cat :context ::context
                     :handlers (s/map-of keyword? fn?)
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
  [{:keys [flow-config] :as flow-engine} context current-flow trans-value]

  (let [current-step-name (get-in context [:step :name])
        current-step-idx  (get-in context [:step :idx])]

    (log/trace "evaluate-transition " [(:name current-flow) current-step-name current-step-idx]
               trans-value)

    (cond

      ; Step Functions need to return non-nil, in order for a transition config to be considered.
      (nil? trans-value)
      nil

      (not (string? trans-value))
      (throw-misconfig
        "The transition in a StepResult must be a String if it is not nil."
        {:trans-value-type (type trans-value)
         :trans-value      trans-value
         :current-flow     (:name current-flow)
         :current-step     current-step-name})

      (string? trans-value)
      (let [_ (log/infof "Looking for next step specifier after %s/%s[%d], trans-value=%s"
                         (:name current-flow) current-step-name current-step-idx trans-value)

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
                    ; If there is no next step, we're done
                    [nil nil ::end]))

                (= transition-config "_terminal")
                [nil nil ::end]

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

                    ; otherwise error
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
        ret-val))))

(defn full-context
  "Create the common context object that is used as input for:
    - step fn calls (in run-step)
    - render (in run-step)
    - handlers (in run-step)"
  [{:keys [datastore hooks] :as flow-engine} session flow step global trigger]
  (let [enrichment (:context-enrichment hooks)
        renderer   (:renderer hooks)
        context1   {:flow    (select-keys flow [:name])
                    :step    (select-keys step [:name :args :idx])
                    :global  global
                    :trigger trigger
                    :session {:step-state   (ds/get-step-state datastore session
                                                               (:name flow) (:name step))
                              :shared-state (ds/get-session-state datastore session)
                              :id           (ds/session-id datastore session)}}
        context2   (if enrichment
                     (enrichment flow-engine context1 session)
                     context1)
        args       (if renderer (renderer context2 (:args step)) (:args step))]
    (assoc context2 :args args)))

(defn run-step
  "Run the specified flow/step.
  Note: session contains a flow-name and step-name, but those are the most recent
  persisted values of those arguments, so the passed in arguments will be used instead."
  [{:keys [aliases handlers datastore global] :as flow-engine}
   session data flow step trigger
   & [depth]]
  (log/infof "Running step %s/%s" (:name flow) (:name step))
  (let [step-fn      (get-step-fn aliases step)
        flow-name    (:name flow)
        step-name    (:name step)
        context      (full-context flow-engine session flow step global trigger)
        callback-gen (partial mk-callback-str
                              (get-in context [:session :id]) flow-name step-name)
        step-res     (step-fn callback-gen context data)]

    (log/tracef "For Step, %s (%s), result: %s" step step-fn step-res)

    ; Process results
    ; TODO catch errors, here or around entire fn; get an error handler
    ;      from `handlers` (also do a log/error)
    (let [{session-muta-from-actions :session-state}
          (process-actions context handlers (:actions step-res))]
      ; Update state
      (let [{session-mutations-from-step :shared-state-mutations
             step-state                  :step-state} step-res
            session-mutations (merge session-mutations-from-step
                                     session-muta-from-actions)]
        (ds/store-session datastore session flow-name step-name session-mutations step-state)))

    ; Evaluate transitions
    (let [[next-flow next-step-name end?]
          (evaluate-transition flow-engine context flow (:transition step-res))]

      (cond
        end?
        (do (ds/end-session datastore (ds/session-id datastore session))
            context)

        (and next-flow next-step-name (>= (or depth 0) 7))
        (throw (ex-info "Step has recursed too many times without user interaction"
                        {:depth       depth
                         ::type       "recursion-limit"
                         :next        [next-flow next-step-name]
                         :trans-value (:transition step-res)}))

        (and next-flow next-step-name)
        (run-step flow-engine
                  ; Get a fresh session for the next step.
                  (ds/get-session datastore (get-in context [:session :id]))
                  data next-flow next-step-name trigger
                  (inc (or depth 0)))

        :else
        context))))

(defn trigger-init
  "Trigger any matching flows. `data` is opaque to the engine, but is passed to
  the Step functions. Returns final `context` from the first executed flow."
  [flow-engine trigger data]
  (let [matches (trigger-matches (:flow-config flow-engine) trigger)]
    (log/infof "Triggering `%s`, matches: %s" trigger (mapv :name matches))
    (first
      (doall
        (for [flow matches
              :let [step    (-> flow :steps first)
                    session (and step (ds/new-session (:datastore flow-engine)
                                                      (:flow-version flow-engine)
                                                      (:name flow)
                                                      (:name step)
                                                      data))]]
          (if step
            (run-step flow-engine session data flow step trigger)
            (throw-misconfig "Workflow has no steps for trigger "
                             {:trigger trigger, :flow (:name flow)})))))))
