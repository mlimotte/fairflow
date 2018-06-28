(ns fair.flow-contrib.slack
  (:require
    [clojure.spec.alpha :as spec]
    [clojure.tools.logging :as log]
    [clojure.data.json :as json]
    [org.httpkit.client :as http]
    [camel-snake-kebab.core :as csk]
    [fair.flow.util.spec :as fus]
    [fair.flow.engine :as engine]
    [fair.flow.util.lang :as lang]
    [clojure.walk :as walk]))

(def color-error-red "#FF4444")
(def color-action-green "#44FF44")
(def color-dark-grey "#888888")
(def color-map {:error-red    color-error-red
                :action-green color-action-green})

(def last-round-messages "_slack_last-round-messages")
(def message-list "_slack_messages")

;;; Slack Helpers

(defn get-channel
  "Helper to get the Slack channel id. This tries several strategies,
  because the Slack API puts this value in several different places."
  [slack-thing]
  (or (get-in slack-thing [:event :channel])
      (get-in slack-thing [:channel :id])))

(def json-content-type {"Content-type" "application/json; charset=utf-8"})

; TODO Add a rate-limiter

(defn send-message
  "POST a message to Slack, synchronously."
  ; chat.postMessage works with JSON, but many Slack apis require content-type
  ; application/x-www-form-urlencoded instead.
  ([token api-method message]
   (let [response-url (:response-url message)]
     (if (log/enabled? :debug)
       (log/debug "Sending slack message:" (or response-url api-method) message)
       (log/info "Sending slack message:" (or response-url api-method) (keys message)))
     @(http/post (or response-url (str "https://slack.com/api/" api-method))
                 {:timeout 10000
                  :headers (assoc json-content-type
                             "Authorization" (str "Bearer " token))
                  :body    (json/write-str message)}))))

(defn slack-async
  "Run the function `f` in a future. `f` should return a Slack response body.
  It also checks the result of `f` for an error and logs the error if found.
  Returns the Future."
  [f & args]
  (future
    (try
      (let [result (apply f args)
            body   (json/read-str (:body result) :key-fn keyword)]
        ; TODO check for 429s, integrate w/ rate limiter and retry.
        ; TODO retry 500s
        (if (:ok body)
          body
          (log/error "Failed to post message: " body)))
      (catch Exception e
        (log/error e "Failed to post message for " args)
        (throw e)))))

(def ^{:doc "POST a message to Slack, asynchronously."} send-message-async
  (partial slack-async send-message))

;;; FlowEngine Handlers

(defn slack-send-message-handler
  "An action handler for sending Slack messages."
  [token {:keys [session round-epoch-millis]} payload]
  (let [message-id    (::message-id payload)
        existing-ts   (get-in session [:shared-state "messages" message-id])
        api-method    (::api-method payload)
        ; If this is a chat.postMessage and we have an existing ts, let's do an update instead
        api-method    (if (and existing-ts (= api-method "chat.postMessage"))
                        "chat.update"
                        api-method)
        response      (send-message token
                                    api-method
                                    (-> payload
                                        (dissoc ::api-method ::message-id)
                                        (assoc :ts existing-ts)))
        response-body (some-> response :body (json/read-str :key-fn keyword))
        ; Note: Slack timestamps (ts) are Strings
        ts            (:ts response-body)]

    (let [last-round-messages-map (get-in session [:shared-state last-round-messages])
          previous-keys           (some->> last-round-messages-map
                                           keys
                                           (filter (fn [k] (not= k round-epoch-millis)))
                                           ; Explicit nil value, when in ActionMutation, will
                                           ; cause the key to be deleted from the Shared session.
                                           (map #(vector % nil))
                                           (into {}))]

      (engine/map->ActionMutation
        {:session-state
         (merge
           ; Save the ts in message-list if message-id is set
           (if message-id {message-list {message-id ts}})
           {last-round-messages (merge {round-epoch-millis {ts true}}
                                       previous-keys)}
           )}))))

(spec/def ::message-id string?)

; payload has only been tested with these Slack message types, add more as they are tested.
(spec/def ::api-method #{"chat.postMessage"
                         "dialog.open"})

(spec/fdef slack-send-message-handler
  :args (spec/cat :token ::fus/non-blank-str
                  :context ::engine/context
                  :payload (spec/keys :req [::api-method]
                                      :opt [::message-id])))

;;; Slack Message creation Helpers

(defn select-option
  "Create a Slack option (text/value) pair."
  [[text value]]
  {:text text, :value value})

(defn select-menu
  "Create an action for a Slack select menu.
  options is a collection of text/value tuples."
  [name text options]
  {:name    name
   :text    text
   :type    "select"
   :options (mapv select-option options)})

(defn button
  "Create a Slack button. `spec` can be a String which is used as the text and name,
  or it can be a Map with keys for text, name (optional), value (optional)."
  [btn-spec]
  (let [{nm :name text :text value :value} (if (map? btn-spec) btn-spec {:text btn-spec})
        final-name (or nm text)]
    (lang/not-nil {:type  "button"
                   :text  text
                   :name  final-name
                   :value (or value final-name)})))

(spec/fdef button
  :args (spec/cat :button-spec
                  (spec/or
                    :map (spec/keys :opt-un [::name ::value]
                                    :req-un [::text])
                    :str ::fus/non-blank-str)))

;;; Step functions

(defn menu-step
  "A Flow Step fn which creates a Menu message.
  A menu is generally a set of buttons."
  [callback-gen {step-args :args session :session} slack-event]
  (let [step-state (:step-state session)]
    (log/debug "Menu" (:text step-args) step-state)
    (if (get step-state "wait-on-response")
      (let [action-name (-> slack-event :actions first :name csk/->kebab-case)]
        (log/debug "slack-event: " slack-event)
        (engine/map->StepResult
          {:transition action-name}))
      (engine/map->StepResult
        {:actions    {:slack-send-message
                      {::api-method "chat.postMessage"
                       :channel     (get-channel slack-event)
                       :attachments [{:text        (:text step-args)
                                      :color       (:color step-args)
                                      :callback_id (callback-gen)
                                      :actions     (mapv button (:options step-args))}]}}
         :step-state {"wait-on-response" true}}))))

(spec/def :fair.flow.slack.menu/args
  (spec/keys :opt-un [::text ::options]))
(spec/fdef menu-step
  ::args (spec/and ::engine/nil-kw-map
                   (spec/tuple any?
                               (spec/keys :req-un [:fair.flow.slack.menu/args])
                               any?)))

(defn message-step
  [callback-gen {:keys [args]} slack-event]
  (engine/map->StepResult
    {:actions                {:slack-send-message
                              (-> args
                                  (assoc
                                    ::api-method "chat.postMessage"
                                    ::message-id (:message-id args)
                                    :channel (get-channel slack-event))
                                  (dissoc :message-id))}
     ; TODO mesasge-id
     :shared-state-mutations {}
     :transition             "true"}))

; Note: This is a partial validation, we do not check all of Slack's rules; for
;   fear of getting out of sync.
(create-ns 'fair.flow.slack.dialog)
(alias 'dialog 'fair.flow.slack.dialog)
(spec/def ::slack-dialog
  (spec/keys :req-un [::dialog/title]
             :opt-un [::dialog/elements ::dialog/submit_label]))
(spec/def ::dialog/title (spec/and string? (partial re-find #"^[a-zA-Z0-9 ,.~?()]{1,24}$")))
(spec/def ::dialog/submit_label (spec/and string? (partial re-find #"^[a-zA-Z0-9]{1,24}$")))
(spec/def ::dialog/elements (spec/coll-of ::dialog/element :min-count 1 :max-count 5))
(spec/def ::dialog/element (spec/keys :req-un [::dialog/type ::dialog/name ::dialog/label]))
(spec/def ::dialog/type #{"text", "textarea", "sleect"})
(spec/def ::dialog/name ::fus/non-blank-str)
(spec/def ::dialog/label ::dialog/title)

(defn deep-key-xform
  "Recursively transforms all map keys from strings to some other form.
  key-fn is a function of the key, e.g. `csk/->kebab-case-keyword` or
  `csk/->snake_case_keyword`.
  Incoming keys can start as Strings or keywords."
  [key-fn m]
  (let [f (fn [[k v]] (if (or (keyword? k) (string? k)) [(key-fn k) v] [k v]))]
    (walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(defn dialog-step
  "Display a Slack dialog.
  session-state: Saves the submission result at the key named by the `save-key` arg.
  transition-value: `true` if dialog submission is saved; `false` if dialog was cancelled."
  [callback-gen {:keys [args session]} slack-event]
  ; TODO spec validate requirements of slack-event for both cases
  (let [step-state (:step-state session)]
    (if (get step-state "wait-on-response")
      (let [save-as    (:save-key args)
            submission (:submission slack-event)
            mutation   {save-as submission}]
        (if (= (:type slack-event) "dialog_cancellation")
          (do
            (log/info "Dialog was cancelled")
            (engine/map->StepResult
              {:transition "false"
               :step-state {"wait-on-response" false}}))
          (do
            (log/info "Received dialog submission, session mutations:" mutation)
            (engine/map->StepResult
              {:transition             "true"
               :step-state             {"wait-on-response" false}
               :shared-state-mutations mutation}))))
      (if (spec/valid? ::slack-dialog (:message args))
        (let [dialog (-> (:message args)
                         (assoc :callback_id (apply callback-gen (:callback-locals args)))
                         (assoc :notify_on_cancel true)
                         (->> (deep-key-xform csk/->snake_case_keyword)))]
          (engine/map->StepResult
            {:actions    {:slack-send-message
                          {::api-method "dialog.open"
                           :trigger_id  (:trigger_id slack-event)
                           :dialog      dialog}}
             :step-state {"wait-on-response" true}}))
        (engine/throw-misconfig
          "Dialog spec is not valid."
          {:explanation (spec/explain-str ::slack-dialog (:message args))})))))
