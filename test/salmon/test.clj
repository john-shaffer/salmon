(ns salmon.test
  (:require [clojure.string :as str]
            [donut.system :as-alias ds]
            [malli.generator :as mg]
            [salmon.cloudformation :as cfn]
            [salmon.signal :as sig]))

(def default-config
  {:regions [:us-east-1 :us-east-2 :us-west-2]
   :test-domain
   {:apex "shafferstest.net"
    :hosted-zone-id "Z08609191OFSO5HMA450N"}})

(defn get-config []
  default-config)

(def ^{:doc "A regular expression for matching S3 bucket names"}
  re-bucket-name
  #"^[a-z0-9][a-z0-9\-.]{1,61}[a-z0-9]$")

(def ^{:doc "A regular expression for matching DNS labels"}
  re-dns-label
  #"^[a-zA-Z0-9](?:[a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?$")


(def ^{:doc "A regular expression for matching IAM usernames"}
  re-iam-username
  #"[a-zA-Z0-9+=,.@_-]{1,64}")

(def ^{:doc "A regular expression for matching IPv4 addresses"}
  re-ipv4-address
  #"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$")

; https://docs.aws.amazon.com/AmazonS3/latest/userguide/bucketnamingrules.html
(def ^{:doc "A malli schema for S3 bucket names"}
  bucket-name-schema
  [:and
   [:re re-bucket-name]
   [:fn #(and
           (not (str/includes? % ".."))

           ; This does not seem to be documented,
           ; but is necessary to avoid the error
           ; "Bucket name should not contain dashes next to periods"
           (not (str/includes? % ".-"))
           (not (str/includes? % "-."))

           (not (str/ends-with? % "--ol-s3"))
           (not (str/ends-with? % "-s3alias"))
           (not (str/starts-with? % "sthree-"))
           (not (str/starts-with? % "xn--"))
           (not (re-matches re-ipv4-address %)))]])

(defn rand-bucket-name []
  (mg/generate bucket-name-schema))

(defn rand-dns-label []
  (mg/generate [:re re-dns-label]))

(defn rand-iam-username []
  (mg/generate [:re re-iam-username]))

(defn rand-stack-name []
  (mg/generate [:re cfn/re-stack-name]))

(def system-base
  {::ds/base {:salmon/early-validate sig/early-validate-conf}
   ::ds/signals
   {:salmon/delete {:order :topsort :returns-instance? true}
    :salmon/early-validate {:order :reverse-topsort}
    ::ds/validate {:order :reverse-topsort}}})

(def system-def-defaults
  {:start? true})

(defmacro with-system [[name-sym system-def] & body]
  `(let [system-def# (merge system-def-defaults ~system-def)
         sys# (atom (if (:start? system-def#)
                      (sig/start! system-def#)
                      system-def#))
         ~name-sym sys#]
     (try
       ~@body
       (finally
         (reset! sys# (sig/stop! @sys#))))))

(defmacro with-system-delete [[name-sym system-def] & body]
  `(let [system-def# (merge system-def-defaults ~system-def)
         sys# (atom (if (:start? system-def#)
                      (sig/start! system-def#)
                      system-def#))
         ~name-sym sys#]
     (try
       ~@body
       (finally
         (reset! sys# (sig/delete! @sys#))))))
