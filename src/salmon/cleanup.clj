(ns salmon.cleanup
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [cognitect.aws.client.api :as aws]
   [salmon.ec2 :as ec2]
   [salmon.util :as u])
  (:import
   [clojure.lang ExceptionInfo]))

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
      (if (u/anomaly? r)
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
      (u/anomaly? r) (log/error "Error checking stack status" stack-name r)
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
        (if (u/anomaly? r)
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
    (log/error "delete-all-stacks! called without :confirm?. Doing nothing.")))

(defn- full-delete-bucket! [bucket-name region]
  (let [s3 (aws/client {:api :s3 :region region})]
    (try
      (doseq [{:keys [Contents]}
              #__ (u/pages-seq s3
                    {:op :ListObjectsV2
                     :request {:Bucket bucket-name}})
              :when (seq Contents)
              :let [ct (count Contents)
                    objects (->> Contents
                              (mapv #(select-keys % [:Key :VersionId])))
                    r (aws/invoke s3
                        {:op :DeleteObjects
                         :request
                         {:Bucket bucket-name
                          :Delete
                          {:Objects objects
                           :Quiet true}}})]]
        (if (u/anomaly? r)
          (log/error "Failed to deleted" ct "objects in bucket" bucket-name (u/aws-error-code r) (u/aws-error-message r))
          (log/info "Deleted" ct "objects in bucket" bucket-name)))
      (catch ExceptionInfo e
        (log/error e "Error listing or deleting objects in bucket" bucket-name)))
    (let [r (aws/invoke s3
              {:op :DeleteBucket
               :request {:Bucket bucket-name}})]
      (if (u/anomaly? r)
        (log/error "Failed to delete bucket" bucket-name (u/aws-error-code r) (u/aws-error-message r))
        (log/info "Deleted bucket" bucket-name)))))

(defn- full-delete-stack! [client stack-id region]
  (doseq [{:keys [PhysicalResourceId :ResourceStatus ResourceType]}
          #__ (->> {:op :ListStackResources
                    :request {:StackName stack-id}}
                (u/pages-seq client)
                (mapcat :StackResourceSummaries))
          :when (not (#{"CREATE_FAILED" "DELETE_COMPLETE"} ResourceStatus))]
    (case ResourceType
      "AWS::S3::Bucket" (full-delete-bucket! PhysicalResourceId region)
      nil))
  (let [r (aws/invoke client
            {:op :DeleteStack
             :request {:StackName stack-id}})]
    (if (u/anomaly? r)
      (log/error "Failed to request stack deletion" stack-id r)
      (log/info "Deleting stack" stack-id))))

(defn- full-delete-stacks! [client region]
  (let [stack-ids (mapv :StackId (get-stacks client))]
    (log/info "Found" (count stack-ids) "stacks")
    (doseq [stack-id stack-ids]
      (full-delete-stack! client stack-id region)
      (Thread/sleep 1000))
    (doseq [stack-id stack-ids]
      (wait-until-complete! client stack-id)
      (Thread/sleep 1000))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn full-delete-all-stacks!
  "Deletes all CloudFormation stacks. Attempts to delete
   resources such as S3 objects that would otherwise
   prevent deleting the stack.

   This will not delete stacks with termination protection
   enabled.

   Must pass :confirm? and a seq of regions."
  [& {:keys [confirm? regions]}]
  (if confirm?
    (doseq [region regions]
      (log/info "Deleting all CloudFormation stacks in" region)
      (full-delete-stacks! (aws/client {:api :cloudformation :region region}) region))
    (log/error "full-delete-all-stacks! called without :confirm?. Doing nothing.")))

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
      (when (u/anomaly? r)
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
      (when (u/anomaly? r)
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
