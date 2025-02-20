(ns salmon.signal
  (:require
   [donut.system :as ds]
   [malli.core :as m]
   [malli.error :as merr]))

(defn early-validate-conf
  "Handles :salmon/early-validate signal by validating the component's
  `:donut.system/config` against the schema in the
  `:salmon/early-schema` entry of the component definition.
  Does nothing if there is no such entry."
  [{{::ds/keys [component-def]} ::ds/system}]
  (let [{::ds/keys [config]
         :salmon/keys [early-schema]}
        #__ component-def]
    (when-let [errors (and early-schema (m/explain early-schema config))]
      (throw
        (ex-info (str "Validation failed: " (merr/humanize errors))
          {:errors errors})))))
