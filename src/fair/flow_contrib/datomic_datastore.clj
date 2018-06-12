(ns fair.flow-contrib.datomic-datastore
  "A FlowDatastore based on a Datomic.

  To setup a new schema:

    (require '[fair.flow.datomic-datastore])
    (fair.flow.datomic-datastore/setup fair.skipper.system/default-datomic-uri)

  And create an instance with:

    (fair.flow.datomic-datastore/->DatomicDatastore conn)
  "
  (:require
    [clojure.tools.logging :as log]
    [clojure.tools.reader.edn :as edn]
    [datomic.api :as d]
    [fair.flow.datastore :as ds]
    [fair.flow.util.lang :as lang]))

; Helpful ref: http://subhasingh.com/blog/How-to-Setup-Datomic-Free/

(def schema
  [

   ;; Flow Session
   {:db/id          #db/id[:db.part/db]
    :db/ident       :flow-session/flow-version
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "A version number which corresponds to the config used to produce the flow"}
   {:db/id          #db/id[:db.part/db]
    :db/ident       :flow-session/current-flow
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Name of the flow which is the current one for this session."}
   {:db/id          #db/id[:db.part/db]
    :db/ident       :flow-session/current-step-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Name of the step which is the current one for this session."}
   {:db/id          #db/id[:db.part/db]
    :db/ident       :flow-session/shared-state
    :db/valueType   :db.type/ref ; REF :state
    :db/cardinality :db.cardinality/one
    :db/doc         "Contains shared state, global to the session."}
   {:db/id          #db/id[:db.part/db]
    :db/ident       :flow-session/step-state
    :db/valueType   :db.type/ref ; REF :state
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to session+flow+step specific state."}

   ;; State
   {:db/id          #db/id[:db.part/db]
    :db/ident       :state/key
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "A unique name, a compound key of session-id|flow|name"}
   {:db/id          #db/id[:db.part/db]
    :db/ident       :state/edn
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "EDN encoded state string"}

   ])

(defn- step-state-key
  "Create the standard state key"
  ([session-id flow-name step-name]
   (ds/mk-dot-key session-id flow-name step-name)))

(defn- store-step-state
  "Store the step state."
  [datastore conn session flow-name step-name step-state]
  (let [step-state-edn (pr-str step-state)
        session-id     (ds/session-id datastore session)]
    (log/infof "Storing step-state in session %s, %s.%s, state=%s"
               session-id flow-name step-name step-state)
    (d/transact conn
                [{:db/id                   session-id
                  :flow-session/step-state [{:state/key (step-state-key
                                                          session-id flow-name step-name)
                                             :state/edn step-state-edn}]}])))

(deftype DatomicDatastore [conn]
  ds/FlowEngineDatastore
  (new-session [this flow-version flow-name step-name data]
    (let [res        @(d/transact conn
                                  [{:db/id                          "new-session"
                                    :flow-session/flow-version      flow-version
                                    :flow-session/current-flow      flow-name
                                    :flow-session/current-step-name step-name}])
          session-id (get (:tempids res) "new-session")
          session    (d/entity (:db-after res) session-id)]
      (log/info "New session initialized" session-id flow-name step-name)
      session))

  (get-session [this session-id]
    (log/info "Load session for" session-id)
    (d/entity (d/db conn) (lang/as-long session-id)))

  (session-id [this session]
    (str (:db/id session)))

  (get-session-state [this session]
    (-> session :flow-session/shared-state :state/edn edn/read-string))

  (get-step-state [this session flow-name step-name]
    (let [k            (step-state-key (ds/session-id this session) flow-name step-name)
          db           (d/db conn)
          state-entity (d/q '[:find ?e .
                              :in $ ?k
                              :where [?e :state/key ?k]]
                            db
                            k)]
      (-> state-entity (->> (d/entity db)) :state/edn edn/read-string)))

  (store-session [this session flow-name step-name shared-state-mutations step-state]
    (let [; Debatable if we need to get fresh session. Only required if we think
          ;   session may be updated asynchronously, which is probably not the case
          ;   in practice.
          session-id          (ds/session-id this session)
          refreshed-session   (d/entity (d/db conn) (lang/as-long session-id))
          existing-flow-state (ds/get-session-state this refreshed-session)
          shared-state        (merge-with lang/merge-maps
                                          existing-flow-state shared-state-mutations)
          shared-state-edn    (pr-str shared-state)
          shared-state-key    session-id]
      (log/infof "Storing session %s, %s.%s, mutations=%s"
                 session-id flow-name step-name shared-state-mutations)
      (d/transact conn
                  [{:db/id                          (lang/as-long session-id)
                    :flow-session/current-flow      flow-name
                    :flow-session/current-step-name step-name
                    :flow-session/shared-state      {:state/key shared-state-key
                                                     :state/edn shared-state-edn}}])
      (when step-state
        (store-step-state this conn session flow-name step-name step-state)))))

(defn setup
  "Create a new database and install/update the schema."
  [uri]
  (d/create-database uri)
  (let [conn (d/connect uri)]
    (d/transact conn schema)
    conn))
