(ns salmon.cloudformation
  (:require [babashka.fs :as fs]
            [clojure.data.json :as json]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as logr]
            [cognitect.aws.client.api :as aws]
            [donut.system :as ds]
            [malli.core :as m]
            [malli.error :as merr]
            [medley.core :as me]
            [salmon.util :as u]
            [salmon.validation :as val]))

(declare pages-seq)

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

(def ^:private re-in-progress-error-message
  #"status [A-Z_]+_IN_PROGRESS")

(defn- in-progress-error-message? [s]
  (or (str/includes? s "_IN_PROGRESS state")
    (boolean (re-find re-in-progress-error-message s))))

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
      (if (str/blank? errors)
        {:json template-json}
        (throw (ex-info errors {:json template-json})))
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

(defn- validate! [{::ds/keys [component-id config system]} schema template & {:keys [pre?]}]
  (let [errors (and schema (m/explain schema config))
        resolved-template (val/resolve-refs system component-id template)
        {:keys [lint?]} config]
    (cond
      errors (throw
               (ex-info (str "Validation failed: " (merr/humanize errors))
                 {:errors errors}))
      (and pre? (not (val/refs-resolveable? system component-id template))) nil
      (not (map? resolved-template)) (throw (ex-info "Template must be a map."
                                              {:template resolved-template}))
      (empty? resolved-template) (throw (ex-info "Template must not be empty."
                                          {:template resolved-template}))
      lint? (let [{:keys [message]} (template-data :template resolved-template)]
              (when (seq message)
                (throw (ex-info (str "Template validation failed: " message)
                         {:message message
                          :template resolved-template})))))))

(defn- response-error [message response]
  (ex-info (str message
             (some->> response u/aws-error-message (str ": ")))
    {:response response}))

(defn- aws-parameters [parameters]
  (mapv
    (fn [[k v]]
      {:ParameterKey (full-name k)
       :ParameterValue v})
    parameters))

(defn- find-failure-cause [stack-name client]
  (let [events (->> {:op :DescribeStackEvents
                     :request {:StackName stack-name}}
                 (pages-seq client)
                 (mapcat :StackEvents))]
    (loop [[event & more] events
           last-failure nil]
      (cond
        (not event) last-failure
        (= "User Initiated" (:ResourceStatusReason event)) last-failure
        (#{"CREATE_FAILED" "UPDATE_FAILED"} (:ResourceStatus event)) (recur more event)
        :else (recur more last-failure)))))

(defn- rollback-error [stack-name client status]
  (let [event (find-failure-cause stack-name client)]
    (ex-info (str "Stack " stack-name " is in rollback state: " status ". "
               (:LogicalResourceId event) " failed with reason: "
               (:ResourceStatusReason event))
      {:name stack-name
       :event-cause event
       :status status})))

(defn- wait-until-complete!
  [stack-name
   client
   & {:keys [error-on-rollback? ignore-non-existence?]}]
  (if error-on-rollback?
    (logr/info "Waiting for stack to enter a COMPLETE, FAILED, or ROLLBACK status" stack-name)
    (logr/info "Waiting for stack to enter a COMPLETE or FAILED status" stack-name))
  (loop []
    (let [r (aws/invoke client {:op :DescribeStacks
                                :request {:StackName stack-name}})
          status (-> r :Stacks first :StackStatus)]
      (cond
        (u/anomaly? r)
        (if (and ignore-non-existence?
              (str/includes? (u/aws-error-message r) "does not exist"))
          nil
          (throw (response-error "Error getting stack status" r)))

        (str/ends-with? status "_FAILED")
        (throw (ex-info (str "Stack " stack-name " is in failed state: " status)
                 {:name stack-name
                  :status status}))

        (and error-on-rollback?
          (str/includes? status "ROLLBACK"))
        (throw (rollback-error stack-name client status))

        (str/ends-with? status "_COMPLETE") nil

        :else
        (do
          (Thread/sleep 5000)
          (recur))))))

(defn- delete-stack! [client name]
  (loop [r (aws/invoke client {:op :DeleteStack
                               :request {:StackName name}})]
    (cond
      (some-> r u/aws-error-message in-progress-error-message?)
      (do
        (wait-until-complete! name client)
        (logr/info "Deleting stack" name)
        (recur (aws/invoke client {:op :DeleteStack
                                   :request {:StackName name}})))

      (u/anomaly? r)
      (throw (response-error "Error deleting stack" r))

      :else
      (wait-until-complete! name client :ignore-non-existence? true))))

(defn- create-stack! [client request]
  (logr/info "Creating stack" (:StackName request))
  (let [r (aws/invoke client {:op :CreateStack :request request})]
    (if (u/anomaly? r)
      [r false]
      [(:StackId r) true])))

(defn- update-stack! [client request stack-id]
  (logr/info "Updating stack" stack-id)
  (let [request (assoc request :StackName stack-id)
        r (aws/invoke client {:op :UpdateStack :request request})
        msg (u/aws-error-message r)]
    (cond
      (= "No updates are to be performed." msg) [stack-id false]
      (u/anomaly? r) [r false]
      :else [stack-id true])))

(defn- cou-stack!
  "Create a new stack or update an existing one with the same name."
  [client {::ds/keys [config]} template-json]
  (let [{:keys [capabilities name parameters tags]} config
        request {:Capabilities (seq capabilities)
                 :Parameters (aws-parameters parameters)
                 :StackName name
                 :Tags (u/tags tags)
                 :TemplateBody template-json}
        r (aws/invoke client {:op :DescribeStacks
                              :request {:StackName name}})
        {:keys [StackId StackStatus]} (some-> r :Stacks first)]
    (cond
      (= "ROLLBACK_COMPLETE" StackStatus)
      #__ (do
            (delete-stack! client StackId)
            (create-stack! client request))
      (= "ValidationError" (u/aws-error-code r)) (create-stack! client request)
      (u/anomaly? r) [r false]
      :else (update-stack! client request StackId))))

(defn- pages-seq [client op-map & [next-token]]
  (lazy-seq
    (let [op-map (if next-token
                   (assoc-in op-map [:request :NextToken] next-token)
                   op-map)
          {:keys [NextToken] :as r} (aws/invoke client op-map)]
      (if NextToken
        (cons r (pages-seq client op-map NextToken))
        (list r)))))

(defn- get-all-pages [client op-map]
  (let [pages (pages-seq client op-map)]
    (or (first (filter u/anomaly? pages))
      (vec pages))))

(defn- outputs-map-raw [outputs-seq]
  (reduce
    (fn [m {:keys [OutputKey] :as output}]
      (assoc m (keyword OutputKey) (dissoc output :OutputKey)))
    {}
    outputs-seq))

(defn- parameters-map-raw [parameters-seq]
  (reduce
    (fn [m {:keys [ParameterKey] :as parameter}]
      (assoc m (keyword ParameterKey) (dissoc parameter :ParameterKey)))
    {}
    parameters-seq))

(defn- tags-map-raw [tags-seq]
  (reduce
    (fn [m {:keys [Key] :as tag}]
      (assoc m (keyword Key) (dissoc tag :Key)))
    {}
    tags-seq))

(defn- describe-stack [client stack-name-or-id]
  (let [r (aws/invoke client {:op :DescribeStacks
                              :request {:StackName stack-name-or-id}})]
    (if (u/anomaly? r)
      r
      (-> r :Stacks first))))

(defn- get-resources [client stack-name-or-id]
  (let [r (get-all-pages client {:op :ListStackResources
                                 :request {:StackName stack-name-or-id}})]
    (if (u/anomaly? r)
      r
      (mapcat :StackResourceSummaries r))))

(defn- resources-map [raw-resources]
  (reduce
    (fn [m {:as resource :keys [LogicalResourceId]}]
      (assoc m (keyword LogicalResourceId) (dissoc resource :LogicalResourceId)))
    {}
    raw-resources))

(defn- stack-instance [client stack-name stack-id]
  (let [resources (get-resources client stack-id)
        describe-r (when-not (u/anomaly? resources)
                     (describe-stack client stack-id))]
    (cond
      (u/anomaly? resources)
      (throw (response-error "Error getting resources" resources))

      (u/anomaly? describe-r)
      (throw (response-error "Error getting stack description" describe-r))

      :else
      (let [outputs-raw (-> describe-r :Outputs outputs-map-raw)
            parameters-raw (-> describe-r :Parameters parameters-map-raw)
            tags-raw (-> describe-r :Tags tags-map-raw)]
        {:client client
         :describe-stack-raw describe-r
         :name stack-name
         :outputs (me/map-vals :OutputValue outputs-raw)
         :outputs-raw outputs-raw
         :parameters (me/map-vals :ParameterValue parameters-raw)
         :parameters-raw parameters-raw
         :resources (resources-map resources)
         :stack-id stack-id
         :tags-raw tags-raw
         :tags (me/map-vals :Value tags-raw)}))))

(defn- start-stack! [{::ds/keys [config instance system]
                      :as signal}]
  (let [{:keys [name region template]} config
        {:keys [client]} instance
        schema (-> system ::ds/component-def :schema)]
    (if client
      instance
      (do
        (validate! signal schema template)
        (loop [client (or (:client config)
                        (aws/client {:api :cloudformation :region region}))
               [r updated?] (cou-stack! client signal (:json (template-data :template template :validate? false)))]
          (cond
            (some-> r u/aws-error-message in-progress-error-message?)
            (do
              (wait-until-complete! name client)
              (recur client (cou-stack! client signal (:json (template-data :template template :validate? false)))))

            (u/anomaly? r)
            (throw (response-error "Error creating stack" r))

            :else
            (do
              (when updated?
                (wait-until-complete! name client :error-on-rollback? true))
              (stack-instance client (:name config) r))))))))

(defn- stop! [{::ds/keys [instance]}]
  (select-keys instance [:name :stack-id]))

(defn- delete!
  [{:keys [::ds/instance]
    {:keys [name]} ::ds/config
    {:keys [client]} ::ds/instance
    :as signal}]
  (if-not client
    instance
    (do
      (delete-stack! client name)
      (stop! signal))))

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

   :client
   An AWS client as produced by
   `cognitect.aws.client.api/client`

   :lint?
   Validate the template using cfn-lint.
   Default: false.

   :name
   The name of the CloudFormation stack. Must match the
   regex #\"^[a-zA-Z][-a-zA-Z0-9]{0,127}$\"

   :parameters
   A map of parameters used when creating or updating
   the stack.

   :region
   The AWS region to deploy the stack in. Ignored when
   :client is present.

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
   (fn [{{::ds/keys [component-def]} ::ds/system
         :as signal}]
     (validate! signal
       (:salmon/early-schema component-def)
       (-> component-def ::ds/config :template)
       :pre? true))
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
  [{:as signal
    {:keys [name]} ::ds/config}
   client]
  (let [r (aws/invoke client {:op :DescribeStacks
                              :request {:StackName name}})
        status (-> r :Stacks first :StackStatus)]
    (cond
      (u/anomaly? r)
      (throw (response-error "Error getting stack status" r))

      (str/ends-with? status "_COMPLETE") true

      (not (str/starts-with? status "CREATE_")) true

      :else
      (do
        (Thread/sleep 5000)
        (recur signal client)))))

(defn- start-stack-properties! [{::ds/keys [config instance system]
                                 :as signal}]
  (let [{:keys [region]} config
        {:keys [client]} instance
        schema (-> system ::ds/component-def :schema)
        errors (when-not client
                 (and schema (m/explain schema config)))]
    (cond
      client instance
      errors (throw
               (ex-info (str "Validation failed: " (merr/humanize errors))
                 {:errors errors}))
      :else
      (let [client (or (:client config)
                     (aws/client {:api :cloudformation :region region}))
            r (get-stack-properties! client signal)]
        (if (u/anomaly? r)
          (throw (response-error "Error creating stack properties" r))
          (when (wait-until-creation-complete! signal client)
            (stack-instance client (:name config) r)))))))

(defn stack-properties
  "Returns a component that describes an existing CloudFormation
   stack's properties. Properties include the stack's resources,
   outputs, and parameters.

   Supported signals: ::ds/start, ::ds/stop

   config options:

   :client
   An AWS client as produced by
   `cognitect.aws.client.api/client`

   :name
   The name of the CloudFormation stack. Must match the
   regex #\"^[a-zA-Z][-a-zA-Z0-9]{0,127}$\"

   :region
   The AWS region of the stack. Ignored when :client is present."
  [& {:as config}]
  {::ds/config config
   ::ds/start start-stack-properties!
   ::ds/stop stop!
   :salmon/delete stop!
   :salmon/early-schema (val/allow-refs stack-properties-schema)
   :schema stack-properties-schema})
