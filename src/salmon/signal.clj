(ns salmon.signal
  (:require [clojure.string :as str]
            [donut.system :as ds]
            [malli.core :as m]
            [malli.error :as merr]))

(defn early-validate-conf
  "Handles :salmon/early-validate signal by validating `conf` against the schema
  in the `:salmon/early-schema` entry of the component definition. Does nothing if
  there is no such entry."
  [{:keys [->validation] ::ds/keys [config system]}]
  (let [schema (-> system ::ds/component-def :salmon/early-schema)]
    (when-let [errors (and schema (m/explain schema config))]
      (->validation {:errors errors
                     :message (merr/humanize errors)}))))

(defn- first-line [s]
  (if (string? s)
    (some-> s not-empty (str/split #"\n") first)
    (pr-str s)))

(defn signal!
  "Sends the signal to the system. Throws a
  `clojure.lang.ExceptionInfo` if there are any messages on the error or
  validation channels. Otherwise, returns the result of
  `(donut.system/signal system signal)`."
  [system signal-name]
  (let [{out ::ds/out :as system} (ds/signal system signal-name)
        {:keys [error validation]} out]
    (cond
      (seq error)
      (throw (ex-info
               (str "Error during " signal-name
                 (some->> error first val first val :message
                   first-line (str ": ")))
               out))

      (seq validation)
      (throw (ex-info
               (str "Validation failed during " signal-name
                 (some->> validation first val first val :message
                   first-line (str ": ")))
               out))

      :else system)))

(defn delete!
  "Calls `(signal! system :salmon/delete)`."
  [system]
  (signal! system :salmon/delete))

(defn early-validate!
  "Calls `(signal! system :salmon/early-validate)`."
  [system]
  (signal! system :salmon/early-validate))

(defn start!
  "Calls `(signal! system :donut.system/start)`."
  [system]
  (signal! system :donut.system/start))

(defn stop!
  "Calls `(signal! system :donut.system/stop)`."
  [system]
  (signal! system :donut.system/stop))
