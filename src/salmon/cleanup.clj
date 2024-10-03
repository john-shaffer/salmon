(ns salmon.cleanup
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cognitect.aws.client.api :as aws]
            [salmon.ec2 :as ec2]
            [salmon.util :as u]))

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

(def ^{:private true} deregisterable-statuses
  ["available"
   "invalid"
   "transient"
   "failed"
   "error"
   "disabled"])

(defn- get-stacks [client & [next-token]]
  (lazy-seq
    (Thread/sleep 1000)
    (let [{:keys [NextToken StackSummaries] :as r}
          #__ (aws/invoke client
                {:op :ListStacks
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
  (let [r (aws/invoke client
            {:op :DescribeStacks
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
      (let [r (aws/invoke client
                {:op :DeleteStack
                 :request {:StackName stack-id}})]
        (if (anomaly? r)
          (log/error "Failed to request stack deletion" stack-id r)
          (log/info "Deleting stack" stack-id))
        (Thread/sleep 1000)))
    (doseq [stack-id stack-ids]
      (wait-until-complete! client stack-id)
      (Thread/sleep 1000))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn delete-all-stacks!
  "Deletes all CloudFormation stacks.

   Must pass :confirm? and a seq of regions."
  [& {:keys [confirm? regions]}]
  (if confirm?
    (doseq [region regions]
      (log/info "Deleting all CloudFormation stacks in" region)
      (delete-stacks! (aws/client {:api :cloudformation :region region})))
    (log/error "delete-all! called without :confirm?. Doing nothing.")))

(defn- deregister-amis! [client]
  (doseq [ami (->> (u/pages-seq client
                     {:op :DescribeImages
                      :request
                      {:Filters
                       [{:Name "state"
                         :Values deregisterable-statuses}]
                       :IncludeDeprecated true
                       :IncludeDisabled true
                       :MaxResults 50
                       :Owners ["self"]}})
                (mapcat :Images))]
    (let [image-id (:ImageId ami)
          _ (log/info "Deregistering AMI" image-id)
          r (aws/invoke client
              {:op :DeregisterImage
               :request {:ImageId image-id}})]
      (when (anomaly? r)
        (log/error "Failed to request AMI deregistation" image-id r)))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn deregister-all-amis!
  "Deletes all AMIs.

   Must pass :confirm? and a seq of regions."
  [& {:keys [confirm? regions]}]
  (if confirm?
    (doseq [region regions]
      (log/info "Deregistering all AMIs in" region)
      (deregister-amis! (aws/client {:api :ec2 :region region})))
    (log/error "deregister-all-amis! called without :confirm?. Doing nothing.")))

(defn- delete-snapshots! [client]
  (doseq [{:keys [SnapshotId]} (ec2/list-orphaned-snapshots :client client)]
    (log/info "Deleting snapshot" SnapshotId)
    (let [r (aws/invoke client
              {:op :DeleteSnapshot
               :request {:SnapshotId SnapshotId}})]
      (when (anomaly? r)
        (log/error "Failed to request snapshot deletion" SnapshotId r)))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn delete-orphaned-snapshots!
  "Deletes all snapshots that are not referenced by any AMI.

   Must pass :confirm? and a seq of regions."
  [& {:keys [confirm? regions]}]
  (if confirm?
    (doseq [region regions]
      (log/info "Deleting all orphaned snapshots in" region)
      (delete-snapshots! (aws/client {:api :ec2 :region region})))
    (log/error "delete-orphaned-snapshots! called without :confirm?. Doing nothing.")))
