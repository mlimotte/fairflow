(ns fair.flow.handlers
  "Sample handlers that you can use in your flows."
  )

(defn pause-millis-handler
  "A handler that pauses. Can be used between two async actions to coerce
  them to maintain order."
  [context session-state payload]
  (Thread/sleep (:millis payload)))

(defn console-message
  "A handler that writes a message to the console."
  [context session-state payload]
  (println "::: " (:text payload)))
