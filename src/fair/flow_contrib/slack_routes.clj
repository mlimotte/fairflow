(ns fair.flow-contrib.slack-routes
  (:require
    [clojure.tools.logging :as log]
    [clojure.data.json :as json]
    [compojure.core :refer [routes GET POST defroutes]]
    [fair.flow.engine :as engine]
    [fair.flow.datastore :as ds]))

(defn token-valid?
  [verification-token event]
  (let [token-in (:token event)]
    (= token-in verification-token)))

(defmulti handle-event (fn [flow-engine parse-trigger-fn req event]
                         [(get event :type) (get-in event [:event :type])]))

(defmethod handle-event ["url_verification" nil]
  [_ _ _ event]
  {:status 200
   :body   {:challenge (:challenge event)}})

(defmethod handle-event ["event_callback" "app_mention"]
  [flow-engine parse-trigger-fn req event]
  (log/info "app_mention received" event)
  (engine/trigger-init flow-engine (parse-trigger-fn req) nil event)
  {:status 200 :body "OK"})

(defmethod handle-event :default
  [_ _ _ event]
  (log/info "Unhandled event type " (:type event))
  "OK")

(defn events
  [verification-token flow-engine parse-trigger-fn]
  (POST "/slack/events" req
    (let [request-body (:body req)]
      ; TODO dedupe incoming slack events-- maybe keep LRU of incoming event id (-> request-body :event :ts)
      (if (token-valid? verification-token request-body)
        (handle-event flow-engine parse-trigger-fn req request-body)
        {:status 400
         :body   "Verification token check failed."}))))

(defn interactives
  [flow-engine]
  (POST "/slack/interactives" req
    ; TODO dedupe incoming slack events-- maybe keep LRU of incoming event id (:action_ts ?)
    (let [payload  (some-> req :params :payload (json/read-str :key-fn keyword))
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

      ; TODO use defspec to validate required keys in callback
      (if (every? #(get callback %) [:session-id :flow-name :step-name])
        (let [session   (ds/get-session (:datastore flow-engine) (:session-id callback))
              flow      (->> callback :flow-name keyword (get (:flow-config flow-engine)))
              step-name (:step-name callback)
              step      (engine/find-step flow step-name)]
          (if flow
            (future
              (try
                (engine/run-step flow-engine session nil payload flow step callback)
                (catch Throwable t
                  (log/error t "Unknown exception running step."))))
            (log/warn "No flow found in config which matches flow-name in the callback"
                      (pr-str callback)))
          {:status 200})
        (log/warnf "Could not parse callback '%s', skipping." callback-id)))))
