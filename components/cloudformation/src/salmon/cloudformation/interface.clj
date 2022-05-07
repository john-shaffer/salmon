(ns salmon.cloudformation.interface
  (:require [salmon.cloudformation.core :as core]))

(def ^{:doc "A regular expression for allowed CloudFormation stack names"}
  re-stack-name core/re-stack-name)

(defn stack
  ""
  [& {:as conf}]
  (core/stack conf))

