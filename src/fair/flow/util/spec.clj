(ns fair.flow.util.spec
  (:require
    [clojure.string :as string]
    [clojure.spec.alpha :as spec]))

(spec/def ::non-blank-str (spec/and string? (complement string/blank?)))

(spec/def ::vec-of-tuples
  (spec/coll-of (spec/coll-of string? :count 2)
                :kind vector?))
