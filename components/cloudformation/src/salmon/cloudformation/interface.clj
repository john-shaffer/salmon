(ns salmon.cloudformation.interface
  (:require
   [salmon.cloudformation.core :as core]))

(defn stack
  ""
  [& {:as conf}]
  (core/stack conf))

