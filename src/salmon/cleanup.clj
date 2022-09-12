(ns salmon.cleanup
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cognitect.aws.client.api :as aws]))

(defn- anomaly? [response]
  (boolean (:cognitect.anomalies/category response)))

(def ^{:private true} deleteable-statuses
  ["CREATE_COMPLETE"
   "CREATE_FAILED"
   "DELETE_FAILED"
   "IMPORT_COMPLETE"
   "IMPORT_ROLLBACK_COMPLETE"
   "IMPORT_ROLLBACK_FAILED"
   "ROLLBACK_COMPLETE"
   "ROLLBACK_FAILED"
   "UPDATE_COMPLETE"
   "UPDATE_FAILED"
   "UPDATE_ROLLBACK_COMPLETE"
   "UPDATE_ROLLBACK_FAILED"])

(defn- get-stacks [client & [next-token]]
  (lazy-seq
   (Thread/sleep 1000)
   (let [{:keys [NextToken StackSummaries] :as r}
         #__ (aws/invoke client {:op :ListStacks
                                 :request (cond->
                                           {:StackStatusFilter deleteable-statuses}
                                            next-token (assoc :NextToken next-token))})]
     (if (anomaly? r)
       (throw (ex-info "Error getting stacks" {:response r}))
       (concat
        StackSummaries
        (when NextToken
          (get-stacks client NextToken)))))))

(defn- wait-until-complete! [client stack-name]
  (let [r (aws/invoke client {:op :DescribeStacks
                              :request {:StackName stack-name}})
        status (-> r :Stacks first :StackStatus)]
    (cond
      (anomaly? r) (log/error "Error checking stack status" stack-name r)
      (= "DELETE_COMPLETE" status) (log/info "Stack deleted" stack-name)

      (or (str/ends-with? status "_COMPLETE")
          (str/ends-with? status "_FAILED"))
      (log/info "Failed to delete stack" stack-name {:status status})

      :else
      (do
        (Thread/sleep 1000)
        (recur client stack-name)))))

(defn- delete-stacks! [client]
  (let [stack-ids (mapv :StackId (get-stacks client))]
    (log/info "Found" (count stack-ids) "stacks")
    (doseq [stack-id stack-ids]
      (let [r (aws/invoke client {:op :DeleteStack
                                  :request {:StackName stack-id}})]
        (if (anomaly? r)
          (log/error "Failed to request stack deletion" stack-id r)
          (log/info "Deleting stack" stack-id))
        (Thread/sleep 1000)))
    (doseq [stack-id stack-ids]
      (wait-until-complete! client stack-id)
      (Thread/sleep 1000))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn delete-all!
   "Deletes all CloudFormation stacks.
   
   Must pass :confirm? true."
  [& {:keys [confirm?]}]
  (if confirm?
    (do
      (log/info "Deleting all CloudFormation stacks")
      (delete-stacks! (aws/client {:api :cloudformation})))
    (log/error "delete-all! called without :confirm?. Doing nothing.")))
