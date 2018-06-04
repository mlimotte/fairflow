(ns fair.util.lang
  (:require
    [clojure.string :as string]))

(defn not-nil
  [x]
  (cond
    (map? x) (into {} (remove (comp nil? second) x))
    (sequential? x) (remove nil? x)))

(defn as-long
  [s]
  "Coerce the input as a Long. Return nil if not possible."
  (cond
    (string? s) (try
                  (-> s (string/replace #"\.\d*" "") Long/valueOf)
                  (catch Exception _))
    (nil? s) nil
    :else (long s)))

(defn simple-kebab
  [s]
  (some-> s string/lower-case string/trim (string/replace #"[^0-9a-z-\.]" "-")))

(defn merge-maps
  "Recursively merge maps. Example:
  `(merge-with merge-maps {:a 1} {:b 1})`"
  [l r]
  (cond
    (and (map? l) (map? r)) (merge-with merge-maps l r)
    :else r))

(defn resolve-var-by-name
  [qualified-name]
  (try
    (let [[ns-name fn-name] (string/split qualified-name #"/" 2)
          ns-sym (symbol ns-name)]
      (require [ns-sym])
      (or (ns-resolve (the-ns ns-sym) (symbol fn-name))
          (throw (ClassNotFoundException. (format "No function found for `%s`" qualified-name)))))
    (catch Exception _
      (throw (ClassNotFoundException. (format "No function found for `%s`" qualified-name))))))
