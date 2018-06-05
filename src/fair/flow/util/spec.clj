(ns fair.flow.util.spec
  (:require
    [clojure.string :as string]
    [clojure.spec.alpha :as spec]))

(spec/def ::non-blank-str (spec/and string? (complement string/blank?)))
