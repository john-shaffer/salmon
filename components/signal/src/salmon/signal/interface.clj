(ns salmon.signal.interface
  (:require [salmon.signal.core :as core]))

(declare signal!)

(defn delete!
  "Calls `(signal! system :/delete)`."
  [system]
  (signal! system :delete))

(defn pre-validate-conf
  "Handles :pre-validate signal by validating `conf` against the schema
  in the `:salmon/pre-schema` entry of the component definition. Does nothing if
  there is no such entry."
  [conf instance system]
  (core/pre-validate-conf conf instance system))

(defn pre-validate!
  "Calls `(signal! system :pre-validate)`."
  [system]
  (signal! system :pre-validate))

(defn signal!
  "Sends the signal to the system. Throws a
  `clojure.lang.ExceptionInfo` if there are any messages on the error or
  validation channels. Otherwise, returns the result of
  `(donut.system/signal system signal)`."
  [system signal]
  (core/signal! system signal))

(defn start!
  "Calls `(signal! system :start)`."
  [system]
  (signal! system :start))

(defn stop!
  "Calls `(signal! system :stop)`."
  [system]
  (signal! system :stop))
