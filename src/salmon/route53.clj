(ns salmon.route53
  (:require [clojure.string :as str]
            [cognitect.aws.client.api :as aws]
            [salmon.util :as u]))

(defn- extract-hosted-zone-id
  "Returns the raw ID (like \"Z08609191OFSO5HMA450N\") from a string like
   \"/hostedzone/Z08609191OFSO5HMA450N\""
  [s]
  (second (re-find #"/hostedzone/(.*)" s)))

(defn fetch-hosted-zone-id
  "Returns the first and most specific hosted zone that
   matchs dns-name.

   For example, if dns-name is \"www.example.com\", this
   will first look for a hosted zone with a DNS name of
   \"www.example.com\" and return it if present. If not,
   this will look for a hosted zone with a DNS name of
   \"example.com\". If no matches are found, returns nil.

   It is possible to have multiple hosted zone ids with
   the same DNS name, but only one will be returned."
  [client dns-name]
  (let [{:as response :keys [HostedZones]}
        (->> {:op :ListHostedZonesByName
              :request {:DNSName dns-name :MaxItems 1}}
             (aws/invoke client))]
    (cond
      (u/anomaly? response) response
      (seq HostedZones) (-> HostedZones first :Id extract-hosted-zone-id)
      :else
      (let [[_ dns-parts] (str/split dns-name #"\.")]
        (when (seq dns-parts)
          (fetch-hosted-zone-id client (str/join "." dns-parts)))))))
