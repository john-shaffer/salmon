(ns salmon.ec2
  (:require
   [clojure.core.cache.wrapped :as cache]
   [salmon.util :as u]))

(defn- list-self-images [client]
  (->> (u/pages-seq client {:op :DescribeImages
                            :request {:MaxResults 1000
                                      :Owners ["self"]}})
    (mapcat :Images)))

(defn- image-snapshot-ids [ami]
  (->> ami :BlockDeviceMappings
    (map (comp :SnapshotId :Ebs))))

(defn- list-self-snapshots [client]
  (->> (u/pages-seq client {:op :DescribeSnapshots
                            :request {:MaxResults 1000
                                      :OwnerIds ["self"]}})
    (mapcat :Snapshots)))

(defn- check-volumes-existence [{:keys [cache client volume-ids]}]
  (loop [cached (reduce #(assoc % %2 (cache/lookup cache %2))
                  {}
                  volume-ids)
         misses (u/filter-vals nil? cached)]
    (if (seq misses)
      (let [; This is only used by find-orphaned-snapshots and
            ; shouldn't ever be paginated, but handle NextToken
            ; just in case AWS decides to paginate it unexpectedly.
            [existing not-found]
            #__ (try
                  (->> (u/pages-seq client
                         {:op :DescribeVolumes
                          :request {:VolumeIds (keys misses)}})
                    (mapcat :Volumes)
                    (mapv :VolumeId)
                    vector)
                  (catch clojure.lang.ExceptionInfo e
                    ; This is ugly, but this is the most efficient way to check
                    ; for volume existence that doesn't require loading all volumes
                    ; into memory at once.
                    ; This is slow to run if there are many nonexistent volumes,
                    ; but subsequent runs should be faster as long as orphaned
                    ; snapshots are cleaned up after each run.
                    (if (= "InvalidVolume.NotFound"
                          (-> e ex-data :result :cognitect.aws.error/code))
                      [nil (re-find #"vol-\w+" (ex-message e))]
                      (throw e))))
            new-cached (reduce #(assoc % %2 true) cached existing)
            new-cached (if not-found
                         (assoc new-cached not-found false)
                         new-cached)]
        (doseq [volume-id existing]
          (cache/miss cache volume-id true))
        (when not-found
          (cache/miss cache not-found false))
        (recur new-cached (u/filter-vals nil? new-cached)))
      cached)))

(defn list-orphaned-snapshots
  "Returns a lazy seq of EC2 snapshots for which the volume no longer exists.

   Orphaned snapshots often occur when using
   Amazon Data Lifecycle Manager. See
   https://repost.aws/knowledge-center/ebs-snapshot-dlm-policy-not-deleting
   for a list of common circumstances.

   Orphaned snapshots may also come about by deleting instances, volumes,
   or deregistering an AMI."
  [& {:as opts :keys [cache client]}]
  (let [ami-snapshots (->> client list-self-images (mapcat image-snapshot-ids) set)
        cache (or cache (cache/lru-cache-factory {} :threshold 10000))
        opts (assoc opts :cache cache)]
    (->> client list-self-snapshots
      (partition-all 1000)
      (mapcat
        (fn [chunk]
          (let [volumes (->> chunk
                          (remove (comp ami-snapshots :SnapshotId))
                          (mapv :VolumeId)
                          set
                          (assoc opts :volume-ids)
                          check-volumes-existence)]
            (filter #(-> % :VolumeId volumes false?) chunk)))))))
