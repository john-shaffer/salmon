(ns salmon.signal.core
  (:require [clojure.string :as str]
            [donut.system :as ds]
            [malli.core :as m]))

(defn pre-validate-conf
  [conf _ {:keys [->validation ::ds/component-def]}]
  (let [schema (:salmon/pre-schema component-def)]
    (when-let [errors (and schema (m/explain schema conf))]
      (->validation errors))))

(defn first-line [s]
  (some-> s not-empty (str/split #"\n") first))

(defn signal! [system signal]
  (let [{out ::ds/out :as system} (ds/signal system signal)
        {:keys [error validation]} out]
    (cond
      (seq error)
      (throw (ex-info
              (str "Error during " signal
                   (some->> error :services first val :message
                            first-line (str ": ")))
              out))

      (seq validation)
      (throw (ex-info
              (str "Validation failed during " signal
                   (some->> validation :services first val :message
                            first-line (str ": ")))
              out))

      :else system)))
