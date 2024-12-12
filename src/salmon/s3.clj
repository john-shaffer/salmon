(ns salmon.s3
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [cognitect.aws.client.api :as aws]
   [salmon.util :as u]))

(defn- md5 [file]
  (let [digest (java.security.MessageDigest/getInstance "MD5")
        input (-> file fs/file io/input-stream)]
    (loop [bytes (byte-array 1024)]
      (let [read (.read input bytes)]
        (when (pos? read)
          (.update digest bytes 0 read)
          (recur bytes))))
    (.close input)
    (let [hash (.digest digest)
          hex (java.lang.StringBuilder.)]
      (doseq [b hash]
        (.append hex (format "%02x" (bit-and b 0xff))))
      (str hex))))

(defn- get-file-details [client bucket k]
  (let [response (aws/invoke
                   client
                   {:op :GetObjectAttributes
                    :request {:Bucket bucket
                              :Key k
                              :ObjectAttributes ["Checksum" "ETag" "ObjectSize"]}})]
    (cond
      (= :cognitect.anomalies/not-found (:cognitect.anomalies/category response))
      nil

      (:cognitect.anomalies/category response)
      response

      :else
      response)))

(defn- put! [client bucket k file]
  (let [{:as response :keys [ETag]}
        (aws/invoke
          client
          {:op :PutObject
           :request {:Body (-> file fs/file io/input-stream)
                     :Bucket bucket
                     :ContentType (if (str/ends-with? (str file) ".jpg")
                                    "image/jpeg"
                                    "text/html")
                     :Key k}})]
    (cond
      (u/anomaly? response)
      (throw (ex-info (str "AWS Error: " (u/aws-error-message response))
               {:response response}))

      (= ETag (str "\"" (md5 file) "\""))
      response

      :else
      (throw (ex-info "MD5 mismatch" {:ETag ETag :md5 (md5 file)})))))

(defn upload! [& {:keys [client bucket prefix dir]}]
  (fs/walk-file-tree
    dir
    {:visit-file
     (fn [path _attrs]
       (when-not (fs/directory? path)
         (let [k (str prefix (fs/relativize dir path))
               {:keys [ETag ObjectSize]} (get-file-details client bucket k)]
           (when (or (not= ObjectSize (fs/size path))
                     (not= ETag (md5 path)))
             (put! client bucket k path))))
       :continue)}))
