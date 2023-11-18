(ns salmon.util
  (:require [cognitect.aws.client.api :as aws]
            [medley.core :as me]))

(defn anomaly?
  "Returns true when the response (from cognitect.aws.client.api)
   indicates an anomalous condition, such as an error."
  [response]
  (boolean (:cognitect.anomalies/category response)))

(defn aws-error-code
  "Returns the error code, if present, of an AWS API response."
  [response]
  (or (some-> response :ErrorResponse :Error :Code)
    (some-> response :Response :Errors :Error :Code)))

(defn aws-error-message
  "Returns the error message, if present, of an AWS API response."
  [response]
  (or (some-> response :ErrorResponse :Error :Message)
    (some-> response :Response :Errors :Error :Message)))

(defn ->ex-info
  "Returns an [[ex-info]] that represents a failed AWS API response."
  [result & {:as extra-ex-data}]
  (let [msg (aws-error-message result)]
    (throw
      (ex-info
        (str "Anomaly during invoke: " msg)
        (merge {:message msg :result result} extra-ex-data)))))

(defn invoke!
  "Calls the AWS API and returns a response, or throws an [[ex-info]]
   if the operation fails.

   See [cognitect.aws.client.api/invoke]."
  [client op-map]
  (let [result (aws/invoke client op-map)]
    (if-not (:cognitect.anomalies/category result)
      result
      (->ex-info result :op-map op-map))))

(defn full-name
  "Returns a string representing a symbol or keyword's full name
   and namespace, if any. If given a string, returns the string
   unchanged."
  ^String [x]
  (when x
    (if (string? x)
      x
      (if (simple-ident? x)
        (name x)
        (str (namespace x) "/" (name x))))))

(defn tags
  "Returns a vector representing a list of CloudFormation tag
   key-value pairs. If given a map, the map's key-value pairs are
   translated to the CloudFormation format. If given a vector or
   other sequential type, returns a vector of the unchanged items.
   Returns nil if `map-or-xs` is empty.

   ```clojure
   (tags {:a \"A\", :b \"B\"})
   ;; => [{:Key \"a\", :Value \"A\"} {:Key \"b\", :Value \"B\"}]

   (tags [{:Key \"a\", :Value \"A\"} {:Key \"b\", :Value \"B\"}])
   ;; => [{:Key \"a\", :Value \"A\"} {:Key \"b\", :Value \"B\"}]
   ```"
  [map-or-xs]
  (cond
    (empty? map-or-xs) nil
    (vector? map-or-xs) map-or-xs
    (sequential? map-or-xs) (vec map-or-xs)
    :else
    (mapv
     (fn [[k v]]
       {:Key (full-name k) :Value v})
     map-or-xs)))

(defn resource
  "Returns a map representing a CloudFormation resource definition.

   Resource attributes are documented at
   https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/aws-product-attribute-reference.html"
  [type properties
   & {:keys [creation-policy deletion-policy depends-on
             metadata update-policy update-replace-policy]}]
  (->> {:Type type
        :CreationPolicy creation-policy
        :DeletionPolicy deletion-policy
        :DependsOn depends-on
        :Metadata metadata
        :Properties (me/remove-vals nil? properties)
        :UpdatePolicy update-policy
        :UpdateReplacePolicy update-replace-policy}
       (me/remove-vals nil?)))
