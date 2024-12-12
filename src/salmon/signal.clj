(ns salmon.signal
  (:require
   [donut.system :as ds]
   [malli.core :as m]
   [malli.error :as merr]))

(defn early-validate-conf
  "Handles :salmon/early-validate signal by validating `conf` against the schema
  in the `:salmon/early-schema` entry of the component definition. Does nothing if
  there is no such entry."
  [{::ds/keys [config system]}]
  (let [schema (-> system ::ds/component-def :salmon/early-schema)]
    (when-let [errors (and schema (m/explain schema config))]
      (throw
        (ex-info (str "Validation failed: " (merr/humanize errors))
          {:errors errors})))))
