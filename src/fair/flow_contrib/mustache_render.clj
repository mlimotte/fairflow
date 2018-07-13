(ns fair.flow-contrib.mustache-render
  (:require
    [clojure.walk :as walk]
    [stencil.core :as stencil]
    [clojure.tools.logging :as log]))

(defn- string-values-render
  [data-map v]
  (if (string? v)
    (stencil/render-string v data-map)
    v))

(defn deep-string-values-render
  [data-map struc]
  (log/trace "Rendering with data-map:" (pr-str data-map))
  (walk/postwalk (partial string-values-render data-map) struc))
