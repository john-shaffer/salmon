(ns salmon.cloudformation.core
  (:require [babashka.fs :as fs]
            [clojure.data.json :as json]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [cognitect.aws.client.api :as aws]
            [donut.system :as ds]
            [malli.core :as m]
            [salmon.validation.interface :as val]))

(defn full-name ^String [x]
  (cond
    (string? x) x
    (simple-ident? x) (name x)
    (ident? x) (str (namespace x) \/ (name x))
    :else (throw (ex-info "full-name argument must be a String, Keyword, or Symbol"
                          {:arg x}))))

(def re-stack-name #"^[a-zA-Z][-a-zA-Z0-9]{0,127}$")

(defn cfn-lint! [template]
  (fs/with-temp-dir [dir {:prefix (str "salmon-cloudformation")}]
    (let [f (fs/create-file (fs/path dir "cloudformation.template"))]
      (spit (fs/file f) template)
      (let [{:keys [err exit out]} (sh/sh "cfn-lint" (str f))]
        (cond
          (zero? exit) nil
          (empty? err) out
          (empty? out) err
          :else (str err out))))))

(defn template-data [& {:keys [template validate?]
                        :or {validate? true}}]
  (let [template-json (json/write-str template)]
    (if-let [errors (and validate? (cfn-lint! template-json))]
      {:json template-json
       :message errors}
      {:json template-json})))

(def stack-schema
  [:map
   [:capabilities
    {:optional true}
    [:maybe
     [:set [:enum "CAPABILITY_AUTO_EXPAND" "CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"]]]]
   [:name
    [:and
     [:string {:min 1 :max 128}]
     [:re re-stack-name]]]])

(defn validate [{:keys [lint?] :as conf} system schema template & {:keys [pre?]}]
  (if-let [errors (and schema (m/explain schema conf))]
    errors
    (cond
      (and pre? (not (val/refs-resolveable? system template))) nil
      (not (map? template)) {:message "Template must be a map."}
      (empty? template) {:message "Template must not be empty."}
      lint? (let [{:keys [message]} (template-data :template template)]
              (when (seq message)
                {:message message})))))

(defn anomaly? [response]
  (boolean (:cognitect.anomalies/category response)))

(defn aws-error-code [response]
  (some-> response :ErrorResponse :Error :Code))

(defn aws-error-message [response]
  (some-> response :ErrorResponse :Error :Message))

(defn response-error [message response]
  {:message (str message
                 (some->> response aws-error-message (str ": ")))
   :response response})

(defn aws-parameters [parameters]
  (mapv
   (fn [[k v]]
     {:ParameterKey (full-name k)
      :ParameterValue v})
   parameters))

(defn wait-until-complete! [{:keys [->error]
                             ::ds/keys [resolved-component]
                             :as system}
                            client]
  (let [name (-> resolved-component :conf :name)
        r (aws/invoke client {:op :DescribeStacks
                              :request {:StackName name}})
        status (-> r :Stacks first :StackStatus)]
    (cond
      (anomaly? r)
      (->error (response-error "Error getting stack status" r))

      (str/ends-with? status "_FAILED")
      (->error {:message (str "Stack " name " is in failed state: " status)
                :name name
                :status status})

      (str/ends-with? status "_COMPLETE") true

      :else
      (do
        (Thread/sleep 5000)
        (recur system client)))))

(defn create-stack! [client request]
  (let [r (aws/invoke client {:op :CreateStack :request request})]
    (if (anomaly? r)
      r
      (:StackId r))))

(defn update-stack! [client request stack-id]
  (let [request (assoc request :StackName stack-id)
        r (aws/invoke client {:op :UpdateStack :request request})
        msg (aws-error-message r)]
    (cond
      (not= "ValidationError" (aws-error-code r)) r
      (= "No updates are to be performed." msg) stack-id
      (anomaly? r) r
      :else (:StackId r))))

(defn cou-stack!
  "Create a new stack or update an existing one with the same name."
  [client {:keys [capabilities name parameters]} template-json]
  (let [request {:Capabilities (seq capabilities)
                 :Parameters (aws-parameters parameters)
                 :StackName name
                 :TemplateBody template-json}
        r (aws/invoke client {:op :DescribeStacks
                              :request {:StackName name}})
        stack-id (some-> r :Stacks first :StackId)]
    (cond
      (= "ValidationError" (aws-error-code r)) (create-stack! client request)
      (anomaly? r) r
      :else (update-stack! client request stack-id))))

(defn get-all-pages [client op-map]
  (loop [responses []
         next-token nil]
    (let [op-map (if next-token
                   (assoc-in op-map [:request :NextToken] next-token)
                   op-map)
          {:keys [NextToken] :as r} (aws/invoke client op-map)]
      (cond
        (anomaly? r) r
        NextToken (recur (conj responses r) NextToken)
        :else (conj responses r)))))

(defn outputs-map [outputs-seq]
  (reduce
   (fn [m {:keys [OutputKey] :as output}]
     (assoc m OutputKey (dissoc output :OutputKey)))
   {}
   outputs-seq))

(defn get-outputs [client stack-name-or-id]
  (let [r (aws/invoke client {:op :DescribeStacks
                              :request {:StackName stack-name-or-id}})]
    (if (anomaly? r)
      r
      (-> r :Stacks first :Outputs outputs-map))))

(defn get-resources [client stack-name-or-id]
  (let [r (get-all-pages client {:op :ListStackResources
                                 :request {:StackName stack-name-or-id}})]
    (if (anomaly? r)
      r
      (mapcat :StackResourceSummaries r))))

(defn stack-instance [{:keys [->error]} client stack-id]
  (let [resources (get-resources client stack-id)
        outputs (when-not (anomaly? resources)
                  (get-outputs client stack-id))]
    (cond
      (anomaly? resources)
      (->error (response-error "Error getting resources" resources))

      (anomaly? outputs)
      (->error (response-error "Error getting outputs" outputs))

      :else
      {:client client
       :outputs outputs
       :resources resources
       :stack-id stack-id})))

(defn start! [_
              {:keys [client] :as instance}
              {:keys [->error ->validation]
               ::ds/keys [component-def resolved-component]
               :as system}]
  (let [{:keys [conf]} resolved-component
        {:keys [template]} conf
        errors (when-not client
                 (validate conf system (:schema component-def) template))]
    (cond
      client instance
      errors (->validation errors)
      :else
      (let [client (aws/client {:api :cloudformation})
            r (cou-stack! client conf (:json (template-data :template template)))]
        (if (anomaly? r)
          (->error (response-error "Error creating stack" r))
          (when (wait-until-complete! system client)
            (stack-instance system client r)))))))

(defn stop! [_ instance _]
  (dissoc instance :client))

(defn delete!
  [conf
   {:keys [client] :as instance}
   {:keys [->error] :as system}]
  (if-not client
    instance
    (let [name (:name conf)
          r (aws/invoke client {:op :DeleteStack
                                :request {:StackName name}})]
      (if (anomaly? r)
        (->error (response-error "Error deleting stack" r))
        (do
          (wait-until-complete! system client)
          (stop! conf instance system))))))

(defn stack [& {:as conf}]
  {:conf (assoc conf :comp/name :stack)
   :delete delete!
   :salmon/pre-schema (val/allow-refs stack-schema)
   :pre-validate
   (fn [conf _ {:keys [->validation] ::ds/keys [component-def] :as system}]
     (some-> (validate conf system
                       (:salmon/pre-schema component-def)
                       (:template (:conf component-def))
                       :pre? true)
             ->validation))
   :schema stack-schema
   :start start!
   :stop stop!})
