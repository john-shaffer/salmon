(ns salmon.signal.interface
  (:require [salmon.signal.core :as core]))

(declare signal!)

(defn delete!
  "Calls `(signal! system :salmon/delete)`."
  [system]
  (signal! system :salmon/delete))

(defn early-validate-conf
  "Handles :salmon/early-validate signal by validating `conf` against the schema
  in the `:salmon/early-schema` entry of the component definition. Does nothing if
  there is no such entry."
  [signal]
  (core/early-validate-conf signal))

(defn early-validate!
  "Calls `(signal! system :salmon/early-validate)`."
  [system]
  (signal! system :salmon/early-validate))

(defn signal!
  "Sends the signal to the system. Throws a
  `clojure.lang.ExceptionInfo` if there are any messages on the error or
  validation channels. Otherwise, returns the result of
  `(donut.system/signal system signal)`."
  [system signal]
  (core/signal! system signal))

(defn start!
  "Calls `(signal! system :donut.system/start)`."
  [system]
  (signal! system :donut.system/start))

(defn stop!
  "Calls `(signal! system :donut.system/stop)`."
  [system]
  (signal! system :donut.system/stop))
