(ns salmon.validation.interface
  (:require [salmon.validation.core :as core]))

(defn allow-refs
  "Transform a map schema to allow entries to match either `donut.system/ref?`
  or the original schema.

  This produces a schema that can be used to do partial validation against
  known values before the other values are known."
  [map-schema]
  (core/allow-refs map-schema))

(defn refs-resolveable?
  "Returns true if all refs refer to either started services or constant
   values."
  [system x]
  (core/refs-resolveable? system x))

(defn resolve-refs
  "Resolve all refs in x."
  [system x]
  (core/resolve-refs system x))
