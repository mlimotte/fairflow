(ns fair.flow-contrib.memory-datastore
  "This datastore is primarily for testing.  A simple store, that keeps sessions in
  an in-memory collection."
  (:require
    [fair.flow.datastore :as ds]
    [fair.flow.util.lang :as lang]))

(defrecord MemoryDatastore [all-sessions]
  ds/FlowEngineDatastore
  (new-session [this flow-version flow-name step-name data]
    (let [len         (count @all-sessions)
          new-session {:id            len
                       :status        ds/session-state-active
                       :session-state {}
                       :step-states   {}}]
      (swap! all-sessions conj new-session)
      new-session))

  (session-id [this session]
    (str (:id session)))

  (session-status [this session]
    (:status session))

  (get-session [this session-id]
    (nth @all-sessions (lang/as-long session-id) nil))

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
      (swap! all-sessions assoc idx new-session)))

  (end-session [this session-id]
    (let [idx         (lang/as-long session-id)
          old-session (get @all-sessions idx)]
      (swap! all-sessions assoc idx (assoc old-session
                                      :status ds/session-state-stopped)))))

(defn mk-store
  "Construct a basic MemoryDatastore.
   To get access to the underlying data, get the Atom.
   (:all-sessions (mk-store))"
  []
  (->MemoryDatastore (atom [])))

(defrecord MemoryDatastore2 [all-sessions session-key-fn]
  ds/FlowEngineDatastore
  (new-session [this flow-version flow-name step-name data]
    (let [id          (session-key-fn data)
          new-session {:id            id
                       :status        ds/session-state-active
                       :session-state {}
                       :step-states   {}}]
      (swap! all-sessions assoc id new-session)
      new-session))

  (session-id [this session]
    (str (:id session)))

  (session-status [this session]
    (:status session))

  (get-session [this session-id]
    (get @all-sessions session-id nil))

  (get-session-state [this session]
    (:session-state session))

  (get-step-state [this session flow-name step-name]
    (get-in session [:step-states flow-name step-name]))

  (store-session [this session flow-name step-name shared-state-mutations step-state]
    (let [id               (ds/session-id this session)
          old-session      (get @all-sessions id)
          new-shared-state (lang/merge-maps (:session-state old-session)
                                            ; If shared-state-mutations is nil, use `{}`, so it
                                            ; doesn't overwrite existing :session-state value.
                                            (or shared-state-mutations {}))
          new-session      (-> old-session
                               (assoc :session-state new-shared-state)
                               (assoc-in [:step-states flow-name step-name] step-state))]
      (swap! all-sessions assoc id new-session)))

  (end-session [this session-id]
    (let [old-session (get @all-sessions session-id)]
      (swap! all-sessions assoc session-id
             (assoc old-session :status ds/session-state-stopped)))))

(defn mk-store2
  "Construct a basic MemoryDatastore2.
   This one is different from MemoryDatastore (mk-store), because it (optionally)
   let's caller set the session-id in the new-session call by specifying an
   :id key in the data Map.
   To get access to the underlying data, get the Atom.
   (:all-sessions (mk-store))"
  [& [{:keys [session-key-fn]}]]
  (let [m (atom {})]
    (->MemoryDatastore2 m (or session-key-fn (constantly (count @m))))))
