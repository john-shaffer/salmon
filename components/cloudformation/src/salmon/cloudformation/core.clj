(ns salmon.cloudformation.core
  (:require [babashka.fs :as fs]
            [clojure.data.json :as json]
            [clojure.java.shell :as sh]
            [cognitect.aws.client.api :as aws]
            [donut.system :as ds]
            [malli.core :as m]
            [salmon.validation.interface :as val]))

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
   [:name
    [:and
     [:string {:min 1 :max 128}]
     [:re #"^[a-zA-Z][-a-zA-Z0-9]*$"]]]])

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

(defn aws-error-code [response]
  (some-> response :ErrorResponse :Error :Code))

(defn aws-error-message [response]
  (some-> response :ErrorResponse :Error :Message))

(declare update-stack!)

(defn create-stack! [client request]
  (aws/invoke client {:op :CreateStack :request request}))

(defn update-stack! [client request]
  (let [r (aws/invoke client {:op :UpdateStack :request request})]
    (when-not (and (= "ValidationError" (aws-error-code r))
                   (= "No updates are to be performed." (aws-error-message r)))
      r)))

(defn cou-stack!
  "Create a new stack or update an existing one with the same name."
  [client {:keys [name]} template-json]
  (let [request {:StackName name
                 :TemplateBody template-json}
        r (aws/invoke client {:op :DescribeStacks
                              :request {:StackName name}})]
    (cond
      (= "ValidationError" (aws-error-code r)) (create-stack! client request)
      (:cognitect.anomalies/category r) r
      :else (update-stack! client request))))

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
        (if (:cognitect.anomalies/category r)
          (->error {:message
                    (str "Error creating stack"
                         (some->> r aws-error-message (str ": ")))
                    :response r})
          {:client client})))))

(defn stop! [_ instance _]
  (dissoc instance :client))

(defn stack [& {:as conf}]
  {:conf (assoc conf :comp/name :stack)
   :salmon/pre-schema (val/allow-refs stack-schema)
   :salmon/pre-validate
   (fn [conf _ {:keys [->validation] ::ds/keys [component-def] :as system}]
     (some-> (validate conf system
                       (:salmon/pre-schema component-def)
                       (:template (:conf component-def))
                       :pre? true)
             ->validation))
   :schema stack-schema
   :start start!
   :stop stop!})
