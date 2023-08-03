(ns salmon.util)

(defn anomaly?
  "Returns true when the response (from cognitect.aws.client.api)
   indicates an anomalous condition, such as an error."
  [response]
  (boolean (:cognitect.anomalies/category response)))

(defn aws-error-code
  "Returns the error code, if present, of an AWS API response."
  [response]
  (some-> response :ErrorResponse :Error :Code))

(defn aws-error-message
  "Returns the error message, if present, of an AWS API response."
  [response]
  (some-> response :ErrorResponse :Error :Message))

