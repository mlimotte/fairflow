(ns fair.flow-contrib.memory-datastore
  "This datastore is primarily for testing.  A simple store, that keeps sessions in
  an in-memory collection."
  (:require
    [fair.flow.datastore :as ds]
    [fair.flow.util.lang :as lang]))

(deftype MemoryDatastore [all-sessions]
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
      (swap! all-sessions assoc idx new-session)))

  (end-session [this session-id]
    (let [idx              (lang/as-long session-id)
          old-session      (get @all-sessions idx)]
      (swap! all-sessions assoc idx (assoc old-session
                                      :status ds/session-state-stopped)))))
