(ns salmon.signal.core
  (:require [clojure.string :as str]
            [donut.system :as ds]
            [malli.core :as m]))

(defn early-validate-conf [{:keys [->validation]
                            ::ds/keys [system]
                            :as signal}]
  (let [schema (-> system ::ds/component-def :salmon/early-schema)]
    (when-let [errors (and schema (m/explain schema signal))]
      (->validation errors))))

(defn first-line [s]
  (some-> s not-empty (str/split #"\n") first))

(defn signal! [system signal-name]
  (let [{out ::ds/out :as system} (ds/signal system signal-name)
        {:keys [error validation]} out]
    (cond
      (seq error)
      (throw (ex-info
              (str "Error during " signal-name
                   (some->> error :services first val :message
                            first-line (str ": ")))
              out))

      (seq validation)
      (throw (ex-info
              (str "Validation failed during " signal-name
                   (some->> validation :services first val :message
                            first-line (str ": ")))
              out))

      :else system)))
