(ns salmon.validation.interface
  (:require
   [salmon.validation.core :as core]))

(defn allow-refs
  "Transform a map schema to allow entries to match either `donut.system/ref?`
  or the original schema.

  This produces a schema that can be used to do partial validation against
  known values before the other values are known."
  [map-schema]
  (core/allow-refs map-schema))

(defn pre-validate-conf
  "Handles :salmon/pre-validate signal by validating `conf` against the schema
  in the `:salmon/pre-schema` entry of the component definition. Does nothing if
  there is no such entry."
  [conf instance system]
  (core/pre-validate-conf conf instance system))

(defn pre-validate!
  "Sends the :salmon/pre-validate signal to the system. Throws a
  `clojure.lang.ExceptionInfo` if there are any messages on the error or
  validation channels. Otherwise, returns the result of
  `(donut.system/signal system :salmon/pre-validate)`."
  [system]
  (core/pre-validate! system))
