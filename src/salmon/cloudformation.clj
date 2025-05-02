(ns salmon.cloudformation
  (:require
   [babashka.fs :as fs]
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
   [salmon.validation :as val])
  (:import
   (clojure.lang ExceptionInfo)))

(defn- full-name ^String [x]
  (cond
    (string? x) x
    (simple-ident? x) (name x)
    (ident? x) (str (namespace x) \/ (name x))
    :else (throw (ex-info "full-name argument must be a String, Keyword, or Symbol"
                   {:arg x}))))

(def ^{:doc "A regular expression for allowed CloudFormation change-set names"}
  re-change-set-name
  #"^[a-zA-Z][-a-zA-Z0-9]{0,127}$")

(def ^{:doc "A regular expression for allowed CloudFormation stack names"}
  re-stack-name
  re-change-set-name)

(def ^:private re-in-progress-error-message
  #"status [A-Z_]+_IN_PROGRESS")

(defn- in-progress-error-message? [s]
  (or (str/includes? s "_IN_PROGRESS state")
    (boolean (re-find re-in-progress-error-message s))))

(defn- cfn-lint! [{:keys [region regions]} template]
  (fs/with-temp-dir [dir {:prefix "salmon-cloudformation"}]
    (let [f (fs/create-file (fs/path dir "cloudformation.template"))]
      (spit (fs/file f) template)
      (let [regions (or regions (when region [region]))
            {:keys [err exit out]}
            #__ (apply sh/sh
                  "cfn-lint"
                  (str f)
                  (when (and (seq regions)
                          (not (some ds/ref? regions)))
                    (cons "-r"
                      (map name regions))))]
        (cond
          (zero? exit) nil
          (empty? err) out
          (empty? out) err
          :else (str err out))))))

(defn- template-data
  [{:as config :keys [template-url]}
   & {:keys [template validate?]
      :or {validate? true}}]
  ; unwrap {:template {}} from the template component
  ; while allowing direct template data specification when not using
  ; a template component
  (let [template (:template template template)
        template-data
        #__ (cond
              template {:json (json/write-str template)}
              template-url {:url template-url}
              validate? (throw (ex-info "Missing template data" {})))
        {:keys [json url]} template-data
        errors (and validate?
                 (cfn-lint! config
                   (or json
                     (slurp url))))]
    (if (and errors (not (str/blank? errors)))
      (throw (ex-info errors template-data))
      template-data)))

(def ^{:private true}
  change-set-schema
  [:map
   [:capabilities
    {:optional true}
    [:maybe
     [:set [:enum "CAPABILITY_AUTO_EXPAND" "CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"]]]]
   [:name
    [:and
     [:string {:min 1 :max 128}]
     [:re re-change-set-name]]]
   [:stack-name
    [:and
     [:string {:min 1 :max 128}]
     [:re re-stack-name]]]])

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

(def ^{:private true}
  template-config-schema
  [:map
   [:lint?
    {:optional true}]
   [:regions
    {:optional true}
    [:sequential [:or :keyword :string]]]
   [:template
    [:or ds/DonutRef
     [:map]]]])

(defn- validate!
  [{:as signal ::ds/keys [component-id system]}
   & {:keys [pre?]}]
  (let [{::ds/keys [component-def]} system
        {::ds/keys [config]} (if pre? component-def signal)
        schema (if pre?
                 (:salmon/early-schema component-def)
                 (:schema component-def))
        errors (and schema (m/explain schema config))
        {:keys [change-set lint? template template-url]} config
        resolved-template (val/resolve-refs system component-id template)]
    (cond
      errors
      (throw
        (ex-info (str "Validation failed: " (merr/humanize errors))
          {:errors errors}))

      ; If we have a change-set, then we don't use a template directly
      change-set nil

      (and pre? (not (val/refs-resolveable? system component-id template)))
      nil

      (and (not template-url) (not (map? resolved-template)))
      (throw (ex-info "Template must be a map."
               {:template resolved-template}))

      (and (not template-url) (empty? resolved-template))
      (throw (ex-info "Template must not be empty."
               {:template resolved-template}))

      lint?
      (let [{:keys [message]} (template-data config :template resolved-template)]
        (when (seq message)
          (throw (ex-info (str "Template validation failed: " message)
                   {:message message
                    :template resolved-template})))))))

(defn- start-template!
  [{::ds/keys [config instance]
    :as signal}]
  (or instance
    (let [{:keys [template]} config]
      (validate! signal)
      {:template template})))

(defn template
  "Returns a component that defines and validates a CloudFormation template.

   Supported signals: ::ds/start, ::ds/stop, :salmon/early-validate

   config options:

   :lint?
   Validate the template using cfn-lint.
   Default: false.

   :regions
   The AWS regions to consider when linting the template.
   Default: nil.

   :template
   A map representing a CloudFormation template. The map
   may contain donut.system refs."
  [& {:as config}]
  {::ds/config config
   ::ds/config-schema template-config-schema
   ::ds/start start-template!
   ::ds/stop (constantly nil)
   :salmon/early-validate
   (fn [signal]
     (validate! signal :pre? true))})

(defn- response-error [message response & [extra-ex-data]]
  (ex-info (str message
             (some->> response u/aws-error-message (str ": ")))
    (assoc extra-ex-data :response response)))

(defn- aws-parameters [parameters]
  (mapv
    (fn [[k v]]
      {:ParameterKey (full-name k)
       :ParameterValue v})
    parameters))

(defn- find-failure-cause [stack-name client]
  (let [events (->> {:op :DescribeStackEvents
                     :request {:StackName stack-name}}
                 (u/pages-seq client)
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
          status
          (throw (response-error "Error getting stack status" r)))

        (str/ends-with? status "_FAILED")
        (throw (ex-info (str "Stack " stack-name " is in failed state: " status)
                 {:name stack-name
                  :status status}))

        (and error-on-rollback?
          (str/includes? status "ROLLBACK"))
        (throw (rollback-error stack-name client status))

        (str/ends-with? status "_COMPLETE") status

        :else
        (do
          (Thread/sleep 5000)
          (recur))))))

(defn- delete-stack! [client name]
  (loop [r (aws/invoke client {:op :DeleteStack
                               :request {:StackName name}})]
    (cond
      (some-> r u/aws-error-message in-progress-error-message?)
      (let [status (wait-until-complete! name client :ignore-non-existence? true)]
        (if (= "DELETE_COMPLETE" status)
          (logr/info "Skipping stack delete because it was already deleted" name)
          (do
            (logr/info "Deleting stack" name)
            (recur (aws/invoke client {:op :DeleteStack
                                       :request {:StackName name}})))))

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
  [client {::ds/keys [config]} {:keys [json url]}]
  (let [{:keys [capabilities name parameters tags termination-protection?]} config
        request {:Capabilities (seq capabilities)
                 :EnableTerminationProtection (boolean termination-protection?)
                 :Parameters (aws-parameters parameters)
                 :StackName name
                 :Tags (u/tags tags)
                 (if json :TemplateBody :TemplateURL) (or json url)}
        r (aws/invoke client {:op :DescribeStacks
                              :request {:StackName name}})
        [{:keys [EnableTerminationProtection StackId StackStatus]}]
        #__ (:Stacks r)]
    (cond
      (= "ROLLBACK_COMPLETE" StackStatus)
      #__ (do
            (delete-stack! client StackId)
            (create-stack! client request))
      (= "ValidationError" (u/aws-error-code r)) (create-stack! client request)
      (u/anomaly? r) [r false]

      :else
      (do
        (when (and (not (nil? termination-protection?))
                (not= EnableTerminationProtection (boolean termination-protection?)))
          (u/invoke! client
            {:op :UpdateTerminationProtection
             :request
             {:EnableTerminationProtection (boolean termination-protection?)
              :StackName StackId}}))
        (update-stack! client request StackId)))))

(defn- execute-change-set! [client stack-name {:keys [changes id]}]
  (when (seq changes)
    ; This op only returns {}
    (aws/invoke client
      {:op :ExecuteChangeSet
       :request
       {:ChangeSetName id
        :StackName stack-name}})))

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
  (-> (u/invoke! client
        {:op :DescribeStacks
         :request {:StackName stack-name-or-id}})
    :Stacks
    first))

(defn- get-resources [client stack-name-or-id]
  (->> {:op :ListStackResources
        :request {:StackName stack-name-or-id}}
    (u/pages-seq client)
    (mapcat :StackResourceSummaries)))

(defn- resources-map [raw-resources]
  (reduce
    (fn [m {:as resource :keys [LogicalResourceId]}]
      (assoc m (keyword LogicalResourceId) (dissoc resource :LogicalResourceId)))
    {}
    raw-resources))

(defn- stack-instance [client stack-name stack-id]
  (let [resources (try (get-resources client stack-id)
                    (catch ExceptionInfo e
                      (throw (ex-info (str "Error getting resources: " (ex-message e))
                               {:stack-id stack-id
                                :stack-name stack-name}
                               e))))
        resources-map (resources-map resources)
        describe-r (describe-stack client stack-id)
        outputs-raw (-> describe-r :Outputs outputs-map-raw)
        parameters-raw (-> describe-r :Parameters parameters-map-raw)
        tags-raw (-> describe-r :Tags tags-map-raw)]
    {:client client
     :describe-stack-raw describe-r
     :name stack-name
     :outputs (me/map-vals :OutputValue outputs-raw)
     :outputs-raw outputs-raw
     :parameters (me/map-vals :ParameterValue parameters-raw)
     :parameters-raw parameters-raw
     :resources resources-map
     :resource-ids (me/map-vals :PhysicalResourceId resources-map)
     :stack-id stack-id
     :tags-raw tags-raw
     :tags (me/map-vals :Value tags-raw)}))

(defn- start-stack! [{::ds/keys [config instance]
                      :as signal}]
  (let [{:keys [change-set name region template]} config
        {inst-client :client} instance
        client (or inst-client
                 (:client config)
                 (aws/client {:api :cloudformation :region region}))]
    (if inst-client
      instance
      (let [{:keys [changes stack-id]} change-set
            ex! (if change-set
                  (fn []
                    (if (seq changes)
                      (let [r (execute-change-set! client name change-set)]
                        (if (u/anomaly? r)
                          [stack-id false]
                          [stack-id true]))
                      [stack-id false]))
                  #(cou-stack! client signal (template-data config :template template :validate? false)))]
        (validate! signal)
        (loop [[r updated?] (ex!)]
          (cond
            (some-> r u/aws-error-message in-progress-error-message?)
            (do
              (wait-until-complete! name client)
              (recur (ex!)))

            (u/anomaly? r)
            (throw (response-error "Error creating stack" r))

            :else
            (do
              (when updated?
                (wait-until-complete! name client :error-on-rollback? true))
              (stack-instance client name r))))))))

(defn- stop!
  "Stops a [[change-set]], [[stack]], or [[stack-properties]]."
  [{::ds/keys [instance]}]
  (select-keys instance [:id :name :stack-id :stack-name]))

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

   :change-set
   A reference to a [[change-set]] component.
   If this is provided, the :capabilities, :parameters,
   and :template options for the stack are ignored.

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
   may contain donut.system refs.

   :template-url
   The URL of a file containing the template body. The URL
   must point to a template (max size: 1 MB) that's located
   in an Amazon S3 bucket. The location for an Amazon S3
   bucket must start with https://.
   Ignored when :change-set or :template is present.

   :termination-protection?
   Enables or disables termination protection on the stack.
   Ignored when nil.
   Default: nil."
  [& {:as config}]
  {::ds/config config
   ::ds/start start-stack!
   ::ds/stop stop!
   :salmon/delete delete!
   :salmon/early-schema (val/allow-refs stack-schema)
   :salmon/early-validate
   (fn [signal]
     (validate! signal :pre? true))
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
  (let [{:keys [region throw-on-missing?]
         :or {throw-on-missing? true}}
        #__ config
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
          (if (or throw-on-missing? (not (str/includes? (u/aws-error-message r) "does not exist")))
            (throw (response-error "Error creating stack properties" r))
            {:client client})
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
   The AWS region of the stack. Ignored when :client is present.
   
   :throw-on-missing?
   Throw an exception if the stack does not exist. Default: true."
  [& {:as config}]
  {::ds/config config
   ::ds/start start-stack-properties!
   ::ds/stop stop!
   :salmon/delete stop!
   :salmon/early-schema (val/allow-refs stack-properties-schema)
   :schema stack-properties-schema})

(defn- wait-until-complete-change-set!
  [change-set-name
   stack-name
   client
   & {:keys [fail-on-no-changes?]}]
  (logr/info "Waiting for change set to enter a COMPLETE or FAILED status" stack-name)
  (loop []
    (let [{:as r :keys [Changes Status StatusReason]}
          #__ (aws/invoke client {:op :DescribeChangeSet
                                  :request
                                  {:ChangeSetName change-set-name
                                   :StackName stack-name}})]
      (cond
        (and (not fail-on-no-changes?)
          (= "FAILED" Status)
          (empty? Changes)
          (str/includes? StatusReason "didn't contain changes"))
        r

        (#{"DELETE_FAILED" "FAILED"} Status)
        (throw (ex-info (str "Change set " change-set-name " is in failed state: " Status)
                 {:change-set-name change-set-name
                  :stack-name stack-name
                  :status Status}))

        (#{"CREATE_COMPLETE" "DELETE_COMPLETE"} Status)
        r

        :else
        (do
          (Thread/sleep 5000)
          (recur))))))

(defn- create-change-set! [client {::ds/keys [config]} {:keys [json url]}]
  (let [{:keys [capabilities name parameters stack-name tags]} config
        request {:Capabilities (seq capabilities)
                 :ChangeSetName name
                 :Parameters (aws-parameters parameters)
                 :StackName stack-name
                 :Tags (u/tags tags)
                 (if json :TemplateBody :TemplateURL) (or json url)}
        _ (logr/info "Creating change-set" name "for stack" stack-name)
        op-map {:op :CreateChangeSet :request request}
        r (aws/invoke client op-map)]
    (cond
      (not (u/anomaly? r))
      r

      (and
        (= "ValidationError" (u/aws-error-code r))
        (str/includes? (u/aws-error-message r) "does not exist"))
      (aws/invoke client
        {:op :CreateChangeSet
         :request (assoc request :ChangeSetType "CREATE")})

      :else
      (throw (response-error "Error creating change set" r
               op-map)))))

(defn- start-change-set! [{::ds/keys [config instance]
                           :as signal}]
  (let [{:keys [fail-on-no-changes? name region stack-name template]} config
        {:keys [client]} instance]
    (if client
      instance
      (let [_ (validate! signal)
            client (or (:client config)
                     (aws/client {:api :cloudformation :region region}))
            {:keys [Id StackId]}
            #__ (create-change-set! client signal (template-data config :template template :validate? false))]
        (let [{:keys [Changes]}
              #__ (wait-until-complete-change-set! Id StackId client
                    :fail-on-no-changes? fail-on-no-changes?)]
          {:changes Changes
           :client client
           :id Id
           :name name
           :stack-id StackId
           :stack-name stack-name})))))

(defn- delete-change-set!
  [{:keys [::ds/instance]
    {:keys [name stack-name]} ::ds/config
    {:keys [client]} ::ds/instance
    :as signal}]
  (if-not client
    instance
    (let [op-map {:op :DeleteChangeSet
                  :request
                  {:ChangeSetName name
                   :StackName stack-name}}
          r (aws/invoke client op-map)
          dne? (and (u/anomaly? r)
                 (= "ValidationError" (u/aws-error-code r))
                 (str/includes? (u/aws-error-message r) "does not exist"))]
      (cond
        ; If the stack or change set has already been deleted, our
        ; work is done.
        dne? nil
        (u/anomaly? r) (throw (u/->ex-info r :op-map op-map))
        :else (wait-until-complete-change-set! name stack-name client))
      (stop! signal))))

(defn change-set
  "Returns a component that creates a CloudFormation ChangeSet.

   Supported signals: ::ds/start, ::ds/stop, :salmon/delete,
   :salmon/early-validate

   config options:

   :capabilities
   A set of IAM capabilities used when creating or
   updating the ChangeSet. Values must be in
   #{\"CAPABILITY_AUTO_EXPAND\"
     \"CAPABILITY_IAM\"
     \"CAPABILITY_NAMED_IAM\"}

   :client
   An AWS client as produced by
   `cognitect.aws.client.api/client`

   :fail-on-no-changes?
   Throw an error if the change set does not contain any
   changes compared to an existing stack, if present.
   Default: false

   :lint?
   Validate the template using cfn-lint.
   Default: false.

   :name
   The name of the CloudFormation ChangeSet. Must match the
   regex #\"^[a-zA-Z][-a-zA-Z0-9]{0,127}$\"

   :parameters
   A map of parameters used when creating the ChangeSet.

   :region
   The AWS region to create the ChangeSet in. Ignored when
   :client is present.

   :stack-name
   The name of the CloudFormation stack. Must match the
   regex #\"^[a-zA-Z][-a-zA-Z0-9]{0,127}$\"

   :template
   A map representing a CloudFormation template. The map
   may contain donut.system refs.

   :template-url
   The URL of a file containing the template body. The URL
   must point to a template (max size: 1 MB) that's located
   in an Amazon S3 bucket. The location for an Amazon S3
   bucket must start with https://.
   Ignored when :template is present."
  [& {:as config}]
  {::ds/config config
   ::ds/start start-change-set!
   ::ds/stop stop!
   :salmon/delete delete-change-set!
   :salmon/early-schema (val/allow-refs change-set-schema)
   :salmon/early-validate
   (fn [signal]
     (validate! signal :pre? true))
   :schema change-set-schema})
