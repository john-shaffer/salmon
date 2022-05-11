(ns salmon.cleanup.interface
  (:require [salmon.cleanup.core :as core]))

(defn delete-all!
  "Deletes all CloudFormation stacks.
   
   Must pass :confirm? true."
  [& {:keys [confirm?]}]
  (core/delete-all! :confirm? confirm?))
