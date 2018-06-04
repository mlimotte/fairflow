(defproject fairflow "0.1.0-SNAPSHOT"

  :description "A library (engine) to drive configurable workflows for any purpose."

  :url "https://github.com/mlimotte/fairflow"

  :dependencies [[org.clojure/clojure "1.9.0"]

                 ; [com.stuartsierra/component "0.3.2"]
                 ; [org.clojure/tools.cli "0.3.7"]
                 ; [org.clojure/core.async "0.4.474"]
                 ; [org.clojure/core.memoize "0.7.1"]
                 ; [org.clojure/tools.trace "0.7.9"]
                 ; [clj-time "0.14.3"]
                 ; [fipp "0.6.12"] ; pretty printer
                 ; [amazonica "0.3.123"] ; AWS api
                 ; [org.clojure/data.xml "0.0.8"]
                 ; [google-apps-clj "0.6.1"]
                 ; [camel-snake-kebab "0.4.0"]
                 ; [org.flatland/ordered "1.5.6"]
                 ; [org.flatland/useful "0.11.5"
                 ;   :exclusions [org.clojure/tools.macro
                 ;                org.clojure/tools.reader]]
                 ; [com.climate/java.shell2 "0.1.0"]
                 ; [throttler "1.0.0"] ; enforce rate limiting on our use of external APIs

                 [camel-snake-kebab "0.4.0"]
                 [org.clojure/tools.reader "1.2.1"]
                 [stencil "0.5.0"] ; Mustache implementation
                 [org.clojure/tools.logging "0.4.1"]

                 ]

  :target-path "target/%s"

  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [

                                  ; Optional dep, for using the datomic-datastore
                                  [com.datomic/datomic-free "0.9.5697"
                                   :exclusions [org.slf4j/log4j-over-slf4j
                                                org.slf4j/slf4j-nop]]

                                  ; These deps are for the example-app
                                  [io.forward/yaml "1.0.8"]
                                  [org.clojure/core.async "0.4.474"]
                                  [org.clojure/data.json "0.2.6"]

                                  ; logging
                                  [clj-logging-config "1.9.12"]
                                  [org.slf4j/slf4j-log4j12 "1.7.25"]
                                  [log4j/log4j "1.2.17"]

                                  ; Needed for Slackapi
                                  ; Need alpha3 for SNI support (needed for AWS Cloudfront hosts like , "Zillow")
                                  ; TODO See https://github.com/http-kit/http-kit/pull/335 for code to
                                  ;      enable SNI on Java8, using 2.3.0 instead of this alpha version.
                                  [http-kit "2.3.0-alpha3"]
                                  [ring "1.6.3"]
                                  [ring-logger "1.0.1"]
                                  [ring/ring-json "0.4.0"]
                                  [ring-middleware-format "0.7.2" :exclusions [org.clojure/test.check]]
                                  [compojure "1.6.1"]

                                  ]
                   }}

  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"})
