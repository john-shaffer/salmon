(ns salmon.cloudformation
  (:require [babashka.fs :as fs]
            [clojure.data.json :as json]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [cognitect.aws.client.api :as aws]
            [donut.system :as ds]
            [malli.core :as m]
            [malli.error :as merr]
            [medley.core :as me]
            [salmon.validation :as val]))

(defn- full-name ^String [x]
  (cond
    (string? x) x
    (simple-ident? x) (name x)
    (ident? x) (str (namespace x) \/ (name x))
    :else (throw (ex-info "full-name argument must be a String, Keyword, or Symbol"
                          {:arg x}))))

(def ^{:doc "A regular expression for allowed CloudFormation stack names"}
  re-stack-name
  #"^[a-zA-Z][-a-zA-Z0-9]{0,127}$")

(defn- cfn-lint! [template]
  (fs/with-temp-dir [dir {:prefix (str "salmon-cloudformation")}]
    (let [f (fs/create-file (fs/path dir "cloudformation.template"))]
      (spit (fs/file f) template)
      (let [{:keys [err exit out]} (sh/sh "cfn-lint" (str f))]
        (cond
          (zero? exit) nil
          (empty? err) out
          (empty? out) err
          :else (str err out))))))

(defn- template-data [& {:keys [template validate?]
                        :or {validate? true}}]
  (let [template-json (json/write-str template)]
    (if-let [errors (and validate? (cfn-lint! template-json))]
      {:json template-json
       :message errors}
      {:json template-json})))

(def ^{:private true}
  stack-schema
  [:map
   [:capabilities
    {:optional true}
    [:maybe
     [:set [:enum "CAPABILITY_AUTO_EXPAND" "CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"]]]]
   [:name
    [:and
     [:string {:min 1 :max 128}]
     [:re re-stack-name]]]])

(def ^{:private true}
  stack-properties-schema
  [:map
   [:name
    [:and
     [:string {:min 1 :max 128}]
     [:re re-stack-name]]]])

(defn- validate [{::ds/keys [component-id config system]} schema template & {:keys [pre?]}]
  (let [errors (and schema (m/explain schema config))
        resolved-template (val/resolve-refs system component-id template)
        {:keys [lint?]} config]
    (cond
      errors {:errors errors
              :message (merr/humanize errors)}
      (and pre? (not (val/refs-resolveable? system component-id template))) nil
      (not (map? resolved-template)) {:message "Template must be a map."}
      (empty? resolved-template) {:message "Template must not be empty."}
      lint? (let [{:keys [message]} (template-data :template resolved-template)]
              (when (seq message)
                {:message message})))))

(defn- anomaly? [response]
  (boolean (:cognitect.anomalies/category response)))

(defn- aws-error-code [response]
  (some-> response :ErrorResponse :Error :Code))

(defn- aws-error-message [response]
  (some-> response :ErrorResponse :Error :Message))

(defn- response-error [message response]
  {:message (str message
                 (some->> response aws-error-message (str ": ")))
   :response response})

(defn- aws-parameters [parameters]
  (mapv
   (fn [[k v]]
     {:ParameterKey (full-name k)
      :ParameterValue v})
   parameters))

(defn- wait-until-complete!
  [{:keys [->error] :as signal
    {:keys [name]} ::ds/config}
   client]
  (let [r (aws/invoke client {:op :DescribeStacks
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
        (recur signal client)))))

(defn- create-stack! [client request]
  (let [r (aws/invoke client {:op :CreateStack :request request})]
    (if (anomaly? r)
      r
      (:StackId r))))

(defn- update-stack! [client request stack-id]
  (let [request (assoc request :StackName stack-id)
        r (aws/invoke client {:op :UpdateStack :request request})
        msg (aws-error-message r)]
    (cond
      (= "No updates are to be performed." msg) stack-id
      (anomaly? r) r
      :else stack-id)))

(defn cou-stack!
  "Create a new stack or update an existing one with the same name."
  [client {::ds/keys [config]} template-json]
  (let [{:keys [capabilities name parameters]} config
        request {:Capabilities (seq capabilities)
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

(defn- get-all-pages [client op-map]
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

(defn- outputs-map-raw [outputs-seq]
  (reduce
   (fn [m {:keys [OutputKey] :as output}]
     (assoc m (keyword OutputKey) (dissoc output :OutputKey)))
   {}
   outputs-seq))

(defn- describe-stack [client stack-name-or-id]
  (let [r (aws/invoke client {:op :DescribeStacks
                              :request {:StackName stack-name-or-id}})]
    (if (anomaly? r)
      r
      (-> r :Stacks first))))

(defn- get-resources [client stack-name-or-id]
  (let [r (get-all-pages client {:op :ListStackResources
                                 :request {:StackName stack-name-or-id}})]
    (if (anomaly? r)
      r
      (mapcat :StackResourceSummaries r))))

(defn- stack-instance [{:keys [->error]} client stack-name stack-id]
  (let [resources (get-resources client stack-id)
        describe-r (when-not (anomaly? resources)
                     (describe-stack client stack-id))]
    (cond
      (anomaly? resources)
      (->error (response-error "Error getting resources" resources))

      (anomaly? describe-r)
      (->error (response-error "Error getting stack description" describe-r))

      :else
      (let [outputs-raw (-> describe-r :Outputs outputs-map-raw)]
        {:client client
         :name stack-name
         :outputs (me/map-vals :OutputValue outputs-raw)
         :outputs-raw outputs-raw
         :resources resources
         :stack-id stack-id}))))

(defn- start-stack! [{:keys [->error ->validation]
               ::ds/keys [config instance system]
               :as signal}]
  (let [{:keys [template]} config
        {:keys [client]} instance
        schema (-> system ::ds/component-def :schema)
        errors (when-not client
                 (validate signal schema template))]
    (cond
      client instance
      errors (->validation errors)
      :else
      (let [client (aws/client {:api :cloudformation})
            r (cou-stack! client signal (:json (template-data :template template)))]
        (if (anomaly? r)
          (->error (response-error "Error creating stack" r))
          (when (wait-until-complete! signal client)
            (stack-instance system client (:name config) r)))))))

(defn- stop! [{::ds/keys [instance]}]
  (select-keys instance [:name :stack-id]))

(defn- delete!
  [{:keys [->error ::ds/instance]
    {:keys [name]} ::ds/config
    {:keys [client]} ::ds/instance
    :as signal}]
  (if-not client
    instance
    (let [r (aws/invoke client {:op :DeleteStack
                                :request {:StackName name}})]
      (if (anomaly? r)
        (->error (response-error "Error deleting stack" r))
        (do
          (wait-until-complete! signal client)
          (stop! signal))))))

(defn stack
  "Returns a component that manages a CloudFormation stack.

   Supported signals: ::ds/start, ::ds/stop, :salmon/delete,
   :salmon/early-validate

   config options:

   :capabilities
   A set of IAM capabilities used when creating or
   updating the stack. Values must be in
   #{\"CAPABILITY_AUTO_EXPAND\"
     \"CAPABILITY_IAM\"
     \"CAPABILITY_NAMED_IAM\"}

   :lint?
   Validate the template using cfn-lint.
   Default: false.

   :name
   The name of the CloudFormation stack. Must match the
   regex #\"^[a-zA-Z][-a-zA-Z0-9]{0,127}$\"

   :template
   A map representing a CloudFormation template. The map
   may contain donut.system refs."
  [& {:as config}]
  {::ds/config config
   ::ds/start start-stack!
   ::ds/stop stop!
   :salmon/delete delete!
   :salmon/early-schema (val/allow-refs stack-schema)
   :salmon/early-validate
   (fn [{:keys [->validation]
         {::ds/keys [component-def]} ::ds/system
         :as signal}]
     (some-> (validate signal
                       (:salmon/early-schema component-def)
                       (-> component-def ::ds/config :template)
                       :pre? true)
             ->validation))
   :schema stack-schema})

(defn- get-stack-properties!
  "Checks whether the stack exists and returns its ID."
  [client {::ds/keys [config]}]
  (let [{:keys [name]} config
        r (aws/invoke client {:op :DescribeStacks
                              :request {:StackName name}})
        stack-id (some-> r :Stacks first :StackId)]
    (or stack-id r)))

(defn- wait-until-creation-complete!
  [{:keys [->error] :as signal
    {:keys [name]} ::ds/config}
   client]
  (let [r (aws/invoke client {:op :DescribeStacks
                              :request {:StackName name}})
        status (-> r :Stacks first :StackStatus)]
    (cond
      (anomaly? r)
      (->error (response-error "Error getting stack status" r))

      (str/ends-with? status "_COMPLETE") true

      (not (str/starts-with? status "CREATE_")) true

      :else
      (do
        (Thread/sleep 5000)
        (recur signal client)))))

(defn- start-stack-properties! [{:keys [->error ->validation]
                                 ::ds/keys [config instance system]
                                 :as signal}]
  (let [{:keys [client]} instance
        schema (-> system ::ds/component-def :schema)
        errors (when-not client
                 (and schema (m/explain schema config)))]
    (cond
      client instance
      errors (->validation errors)
      :else
      (let [client (aws/client {:api :cloudformation})
            r (get-stack-properties! client signal)]
        (if (anomaly? r)
          (->error (response-error "Error creating stack" r))
          (when (wait-until-creation-complete! signal client)
            (stack-instance system client (:name config) r)))))))

(defn stack-properties
  "Returns a component that describes an existing CloudFormation
   stack's properties. Properties include the stack's resources,
   and outputs.

   Supported signals: ::ds/start, ::ds/stop

   config options:

   :name
   The name of the CloudFormation stack. Must match the
   regex #\"^[a-zA-Z][-a-zA-Z0-9]{0,127}$\""
  [& {:as config}]
  {::ds/config config
   ::ds/start start-stack-properties!
   ::ds/stop stop!
   :salmon/delete stop!
   :salmon/early-schema (val/allow-refs stack-properties-schema)
   :schema stack-properties-schema})
