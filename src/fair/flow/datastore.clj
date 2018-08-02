(ns fair.flow.datastore
  "Protocol for the Flow Datastore, which describes the actions
  used to read and write session data."
  (:require
    [clojure.string :as string]
    [clojure.spec.alpha :as s]))

(def session-state-active "active")
(def session-state-stopped "stopped")

(defn mk-dot-key
  [& args]
  (string/join "." (map str args)))

(defn parse-dot-key
  [k & [limit]]
  (if limit
    (string/split k #"\." limit)
    (string/split k #"\.")))

(defn mk-slash-key
  [& args]
  (string/join "/" (map str args)))

(defn parse-slash-key
  [k & [limit]]
  (if limit
    (string/split k #"/" limit)
    (string/split k #"/")))

(defn mk-dash-key
  [& args]
  (string/join "-" (map str args)))

(defn parse-dash-key
  [k & [limit]]
  (if limit
    (string/split k #"-" limit)
    (string/split k #"-")))

(defprotocol FlowEngineDatastore
  "Functions for managing state storage for the Flow Engine."

  ; Need to store:
  ;  1. session state (associated with a single flow) that drives the engine
  ;  2. user-data at the flow and step levels
  ; Note: The KH bucket concept is done at a higher level, by the handlers and step fns

  (new-session [this flow-version flow-name step-name data]
    "Create a new session, saving the current flow/step in it. A unique session-id
    will also be generated, which serves as a primary key for the session data.

    Returns the \"session\" object. The contents of this object are opaque to
    the caller, but caller can pass it into the other functions in this protocol to
    get state, session id, etc.")

  (get-session [this session-id]
    "Retrieve the session from storage. Return nil if session doesn't exist.")

  (session-id [this session]
    "Get the unique (PK) id for the session, which should be a string.")

  (get-session-state [this session]
    "Get the session state, which is arbitrary data that is maintained for all flows
    in the session.")

  (get-step-state [this session flow-name step-name]
    "Get the state for the named Step")

  (store-session [this session flow-name step-name shared-state-mutations step-state]
    "Persist the session details and state. Should do a recursive merge for
    shared-state-mutations, as per `fair.flow.util.lang/merge-maps`.

    N.B. If you persist data session-state/step-state as JSON, it is best-practice to
    read the keys back as Strings (not keywords). Automatic conversion to/from keywords
    can be confusing for a developer who intentionally creates a Map of Strings. Best
    to leave the persisted state un-molested.")

  (end-session [this session-id]
    "Mark the session is completed.")

  (session-status [this session]
    "Get the status for this session. One of fair.flow.datastore/session-state-*")

  )

(defn session-active?
  "Is the given session active (fair.flow.datastore/session-state-active)?"
  [datastore session]
  (= (session-status datastore session) session-state-active))

(s/def ::datastore (partial satisfies? FlowEngineDatastore))
