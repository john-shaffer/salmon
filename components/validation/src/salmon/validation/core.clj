(ns salmon.validation.core
  (:require
   [clojure.string :as str]
   [donut.system :as ds]
   [malli.core :as m]
   [malli.util :as mu]))

(defn allow-refs* [map-entry]
  (mapv
   (fn [[kw schema conds]]
     [kw schema
      [:or
       [:tuple [:= ::ds/ref] keyword?]
       conds]])
   map-entry))

(defn allow-refs [map-schema]
  (mu/transform-entries map-schema allow-refs*))

(defn pre-validate-conf
  [conf _ {:keys [->validation ::ds/component-def]}]
  (let [schema (:salmon/pre-schema component-def)]
    (when-let [errors (and schema (m/explain schema conf))]
      (->validation errors))))

(defn first-line [s]
  (some-> s not-empty (str/split #"\n") first))

(defn pre-validate! [system]
  (let [{out ::ds/out :as system} (ds/signal system :salmon/pre-validate)
        {:keys [error validation]} out]
    (cond
      (seq error)
      (throw (ex-info
              (str "Error during pre-validation"
                   (some->> error :services first val :message
                            first-line (str ": ")))
              out))

      (seq validation)
      (throw (ex-info
              (str "Pre-validation failed"
                   (some->> validation :services first val :message
                            first-line (str ": ")))
              out))

      :else system)))

