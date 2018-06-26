(ns fair.flow.util.spec
  (:require
    [clojure.string :as string]
    [clojure.spec.alpha :as s])
  (:import
    (clojure.lang MultiFn)))

(s/def ::non-blank-str (s/and string? (complement string/blank?)))

(s/def ::vec-of-tuples
  (s/coll-of (s/coll-of string? :count 2)
             :kind vector?))

(s/def ::fn #(or (fn? %) (instance? MultiFn %)))
