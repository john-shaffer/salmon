(ns salmon.ssm
  (:require
   [cognitect.aws.client.api :as aws]
   [donut.system :as-alias ds]
   [salmon.util :as u]))

(defn- parameters-map [params]
  (reduce
    #(assoc % (:Name %2) %2)
    {}
    params))

(defn- get-parameters [client {:keys [with-decryption?]} m]
  (let [kvs (seq m)
        vks (reduce (fn [m [k v]] (assoc m v k)) {} kvs)]
    (-> (u/invoke! client
          {:op :GetParameters
           :request {:Names (mapv val kvs)
                     :WithDecryption (boolean with-decryption?)}})
      :Parameters
      parameters-map
      (update-keys vks))))

(defn- start-parameters-getter!
  [{::ds/keys [config instance]}]
  (or instance
    (let [{:as config :keys [client parameters region]}
          #__ (merge {:with-decryption? true} config)
          client (or client (aws/client {:api :ssm :region region}))]
      {:client client
       :parameters (get-parameters client config parameters)})))

(defn- stop! [_]
  nil)

(defn parameters-getter
  [& {:as config}]
  {::ds/config config
   ::ds/start start-parameters-getter!
   ::ds/stop stop!})
