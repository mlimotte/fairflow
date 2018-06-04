(ns fair.flow-contrib.sample.app
  "A sample app that runs a simple interaction via the console.
  - Flow Configuration is read from a local file.
  - A transient, in-memory datomic datastore is used for Session state
  - Input and actions are managed through the console where the app is launched. In practice,
    I've written flows that interact with Slack, google sheets and REST endpoints.

  Run this app using:

    `lein run -m fair.flow-contrib.sample.app ./sample-flows.yml`

  Note: This app is a little light on error checking to keep things simple.
  "
  (:require
    [clojure.string :as string]
    [clojure.core.async :as async]
    [datomic.api]
    [fair.flow.engine :as engine]
    [fair.flow.handlers :as handlers]
    [fair.flow-contrib.datomic-datastore :as datomicds]
    [fair.flow-contrib.mustache-render :as mustache-render]
    [yaml.core :as yaml]
    [fair.flow.datastore :as ds]
    [clj-logging-config.log4j :as logging-config]
    [clojure.tools.logging :as log]
    [fair.util.lang :as lang])
  (:import
    (java.util Date)))

(defn console-read-line
  "Read a line from the console and put it on the core.async channel"
  [context session-state callback-str]
  (print "\n=> ")
  (let [c    (get-in context [:extra-context :read-ch])
        line (read-line)]
    (async/go (async/>! c [callback-str line]))))

(defn message-step
  [callback-gen context data session-state step-state]
  (engine/map->StepResult
    {:actions    {:send-message {:text (-> context :args :text)}}
     :transition "ok"}))

(defn prompt-step
  [callback-gen {:keys [args] :as context} msg session-state step-state]
  (if (::wait-for step-state)
    (let [save-key (engine/key-path (:save-key args))]
      (if (string/blank? msg)
        (engine/map->StepResult
          {:step-state {::wait-for nil}
           :transition "blank"})
        (engine/map->StepResult
          ; TODO save-key should be a PATH --- fixme
          {:shared-state-mutations (assoc-in {} save-key msg)
           :step-state             {::wait-for nil}
           :transition             "ok"})))
    (engine/map->StepResult
      {:actions    {:send-message (select-keys args [:text])
                    :read-line    (callback-gen)}
       :step-state {::wait-for :response}})))

(defn- show-menu
  [args callback-str]
  (let [{:keys [title options]} args
        menu-text (format "\n%s\n%s\n%s\nEnter a number:"
                          title
                          (clojure.string/join (repeat (count title) "="))
                          (string/join "\n"
                                       (map-indexed (fn [idx item] (format "%s.\t%s" idx item))
                                                    options)))]
    (engine/map->StepResult
      {:actions    {:send-message {:text menu-text}
                    :read-line    callback-str}
       :step-state {::wait-for :response
                    ::options  options}})))

(defn menu-step
  [callback-gen {:keys [args] :as context} msg session-state step-state]
  (if (::wait-for step-state)
    (let [n (lang/as-long msg)]
      (if-not n
        (show-menu args (callback-gen))
        (engine/map->StepResult
          {:step-state {::wait-for nil :options nil}
           :transition (lang/simple-kebab (nth (::options step-state) n))})))
    (show-menu args (callback-gen))))

(def time-fmt (java.text.SimpleDateFormat. "HH:mm"))
(defn time-str
  [text]
  (.format time-fmt (Date.)))

(def date-fmt (java.text.SimpleDateFormat. "yyyy-MM-dd"))
(defn date-str
  [text]
  (.format date-fmt (Date.)))

(defn callback-loop
  [flow-engine {:keys [read-ch] :as extra-context}]
  (while true
    (let [[callback-str msg] (async/<!! read-ch)
          callback (engine/parse-callback callback-str)]
      (Thread/sleep 500)
      (let [session   (ds/get-session (:datastore flow-engine) (:session-id callback))
            flow      (->> callback :flow-name keyword (get (:flow-config flow-engine)))
            step-name (:step-name callback)
            step      (engine/find-step flow step-name)]
        (if flow
          (future
            (try
              (engine/run-step flow-engine session extra-context callback msg flow step)
              (catch Throwable t
                (log/error t "Unknown exception running step."))))
          (log/warn "No flow found in config which matches flow-name in the callback"
                    (pr-str callback)))
        (println ".")))))

(defn my-flow-engine
  [flow-config]
  (let [flow-version (engine/flow-version flow-config)
        datomic-conn (datomicds/setup "datomic:mem://fairflow-sample")
        datastore    (datomicds/->DatomicDatastore datomic-conn)
        handlers     {:send-message handlers/console-message
                      :read-line    console-read-line
                      :pause        handlers/pause-millis-handler}]
    (engine/map->FlowEngine
      {:datastore                    datastore
       :flow-config                  flow-config
       :flow-version                 flow-version
       :static-interpolation-context {:time-str time-str
                                      :date-str date-str}
       :renderer                     mustache-render/deep-string-values-render
       ; The values of the alias Map can be functions or Vars
       :aliases                      {"menu"    menu-step
                                      "prompt"  prompt-step
                                      "message" message-step}
       :handlers                     handlers})))

(defn setup-logging!
  []
  (let [default-layout (org.apache.log4j.EnhancedPatternLayout.
                         "%d{ISO8601} %5p (%x) [%t] %c{3} - %m%n%throwable")]
    (logging-config/set-loggers!
      :config {:level :debug} ; logging for logging-config itself
      :root {:name  "console"
             :out   (org.apache.log4j.ConsoleAppender. default-layout)
             :level :warn}
      ;"fair" {:level :debug}
      "datomic.peer" {:level :warn})))

(defn -main
  [& [flow-config-filename]]

  ; Logging
  (setup-logging!)

  ; Note: While this sample app reads flow config from a YAML file, the Flow Engine does
  ;       does not care. You could get your flow config from a JSON or a Google Sheet or
  ;       whatever.
  (let [flow-config   (-> flow-config-filename
                          (yaml/from-file true)
                          engine/normalize-flow-config)
        _             (when-not flow-config
                        (throw (RuntimeException.
                                 (format "Error: Could not load flow config; expecting YAML file `%s`"
                                         flow-config-filename))))
        engine        (my-flow-engine flow-config)
        extra-context {:read-ch (async/chan)}
        loop          (future (callback-loop engine extra-context))]

    ; There are generally two entry points to the flow engine.
    ; The first triggers new sessions...
    (engine/trigger-init engine "session-start"
                         extra-context
                         nil)
    ; The second is for callbacks...
    @loop
    ; A Slack bot, for example, could do `trigger-init` based on the Slack events api,
    ; while callbacks are triggered by the Slack "interactives" webhook.

    (println "Shutdown.")
    (async/close! (:read-ch extra-context))
    ))