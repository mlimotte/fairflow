(ns fair.flow-contrib.slack-routes
  (:require
    [clojure.tools.logging :as log]
    [clojure.data.json :as json]
    [compojure.core :refer [routes GET POST defroutes]]
    [fair.flow.engine :as engine]
    [fair.flow.datastore :as ds]
    [fair.flow-contrib.slack :as ffcslack]))

(defonce session-by-channel-id (atom {}))

(defn token-valid?
  [verification-token event]
  (let [token-in (:token event)]
    (= token-in verification-token)))

(defmulti handle-event (fn [token flow-engine parse-trigger-fn req event]
                         [(get event :type) (get-in event [:event :type])]))

(defmethod handle-event ["url_verification" nil]
  [_ _ _ _ event]
  {:status 200
   :body   {:challenge (:challenge event)}})

(defmethod handle-event ["event_callback" "app_mention"]
  [token engine-manager parse-trigger-fn req event]
  (let [flow-engine (engine/get-engine engine-manager nil)
        channel-id  (ffcslack/get-channel event)
        datastore   (:datastore flow-engine)
        user-id     (get-in event [:event :user])]
    ; TODO find username for user-id and include in this log line:
    (log/infof "app_mention received, starting session, channel: %s, user: %s"
               channel-id user-id)

    (log/warn "MARC app_mention - can we distinguish one of these from another?  Is ts different?"
              event)

    (let [trigger          (parse-trigger-fn req)
          context          (engine/trigger-init flow-engine trigger event)
          session-id       (get-in context [:session :id])
          previous-session (get @session-by-channel-id channel-id)]
      ; Context will be nil if trigger doesn't match any flows.
      (when-not context
        (ffcslack/send-message
          token "chat.postMessage"
          {:text      (str "No flows for trigger: " trigger)
           :channel   channel-id
           :thread_ts (get-in event [:event :ts])}))
      ; Only support one session per channel
      (when (and previous-session (not= previous-session session-id))
        (ds/end-session datastore previous-session)
        (swap! session-by-channel-id assoc channel-id session-id))))
  {:status 200 :body "OK"})

(defmethod handle-event :default
  [_ _ _ _ event]
  (log/info "Unhandled event type " (:type event))
  {:status 200 :body "OK"})

(defn events
  [token verification-token engine-manager parse-trigger-fn]
  (POST "/slack/events" req
    (let [request-body (:body req)]
      (if (token-valid? verification-token request-body)
        (handle-event token engine-manager parse-trigger-fn req request-body)
        {:status 400
         :body   "Verification token check failed."}))))

(defn interactives
  [token engine-manager]
  (POST "/slack/interactives" req

    (let [payload  (or (some-> req
                               :params
                               (as-> $ (or (get $ :payload) (get $ "payload")))
                               (json/read-str :key-fn keyword)
                               (assoc ::host-header (get-in req [:headers "host"])))
                       (throw (ex-info "No payload found for Slack callback" {:type :bad-request})))

          ; Also consider capturing :trigger_id, :response_url
          ;   response_url: if we want to overwrite previous interactive message item
          ;   trigger_id: is for dialogs
          {callback-id :callback_id} payload
          callback (engine/parse-callback callback-id)]

      (log/infof "Slack interactive, callback: %s" callback-id)

      ; Sample payload:
      ; {:action_ts "1526157610.961020",
      ;  :callback_id "Y",
      ;  :trigger_id "362726642259.300119657318.9ec211959887c87174aa3259a51bf2ac",
      ;  :is_app_unfurl false,
      ;  :channel {:id "CAN2U0V4Y", :name "workroom"},
      ;  :type "interactive_message",
      ;  :actions [{:name "assist", :type "button", :value ""}],
      ;  :token "cZ5l4pbLXYI2NmkuZkXTityd",
      ;  :attachment_id "1",
      ;  :team {:id "T8U3HKB9C", :domain "forward-path"},
      ;  :message_ts "1526157607.000123",
      ;  :user {:id "U8SJ29TUG", :name "mslimotte"},
      ;  :response_url "https://hooks.slack.com/actions/T8U3HKB9C/362281047073/IRGZ0SPxUElfUNMumWnLK4oY",
      ;  :original_message {...}}

      (if (every? #(get callback %) [:session-id :flow-name :step-name])
        (let [session-id          (:session-id callback)
              ; Always use the latest version to get the datastore:
              {:keys [datastore]} (engine/get-engine engine-manager nil)
              session             (ds/get-session datastore session-id)]

          (cond
            (not (ds/session-active? datastore session))
            (log/info "Session is not active, dropping event:"
                      {:session session-id :event payload})

            :else
            (let [flow-version (:flow_version session)
                  flow-engine  (engine/get-engine engine-manager flow-version)
                  flow         (->> callback
                                    :flow-name
                                    keyword
                                    (get (:flow-config flow-engine)))
                  step-name    (:step-name callback)
                  step         (engine/find-step flow step-name)]

              (if flow
                (future
                  (try
                    (engine/run-step flow-engine session payload flow step callback)
                    (catch Throwable t
                      (log/error t "Unknown exception running step."))))
                (log/warn "No flow found in config which matches flow-name in the callback"
                          (pr-str callback))))))

        (log/warnf "Could not parse callback '%s', skipping." callback-id))

      ; Always return success to Slack. If we want to report errors, we need
      ; to do it through response-url.
      {:status 200})))
