(ns fair.flow.datastore
  "Protocol for the Flow Datastore, which describes the actions
  used to read and write session data."
  (:require
    [clojure.string :as string]))

(defn mk-key
  [& args]
  (string/join "." (map str args)))

(defn parse-key
  [k]
  (string/split k #"\."))

(defprotocol FlowEngineDatastore
  "Functions for managing state storage for the Flow Engine."

  ; Need to store:
  ;  1. session state (associated with a single flow) that drives the engine
  ;  2. user-data at the flow and step levels
  ; Note: The KH bucket concept is done at a higher level, by the handlers and step fns

  (new-session [this flow-version flow-name step-name]
    "Create a new session, saving the current flow/step in it. A unique session-id
    will also be generated, which serves as a primary key for the session data.

    Returns the \"session\" object. The contents of this object are opaque to
    the caller, but caller can pass it into the other functions in this protocol to
    get state, session id, etc.")

  (get-session [this session-id]
    "Retrieve the session from storage.")

  (session-id [this session]
    "Get the unique (PK) id for the session, which should be a string.")

  (get-session-state [this session]
    "Get the session state, which is arbitrary data that is maintained for all flows
    in the session.")

  (get-step-state [this session flow-name step-name]
    "Get the state for the named Step")

  (store-session [this session flow-name step-name shared-state-mutations step-state]
    "Persist the session details and state.
    Should do a recursive merge for shared-state-mutations, as per `fair.flow.util.lang/merge-maps`."))
