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
      :else (when lint?
              (let [{:keys [message]} (template-data :template template)]
                (when (seq message)
                  {:message message}))))))

(defn stack [& {:as conf}]
  {:conf (assoc conf :comp/name :stack)
   :start
   (fn [_ _ {:keys [->error ->validation] 
                ::ds/keys [component-def resolved-component]
                :as system}]
     (let [conf (:conf resolved-component)
           template (:template conf)]
       (if-let [errors (validate conf system
                                 (:schema component-def)
                                 template)]
         (->validation errors)
         (let [client (aws/client {:api :cloudformation})
               r (aws/invoke client
                             {:op :CreateStack
                              :request {:StackName (:name conf)
                                        :TemplateBody
                                        (:json (template-data :template template))}})]
           (if (:cognitect.anomalies/category r)
             (->error {:message "Error creating stack"
                       :response r})
             {:client client})))))
   :stop
   (fn [_ _ _])
   :salmon/pre-schema (val/allow-refs stack-schema)
   :salmon/pre-validate
   (fn [conf _ {:keys [->validation] ::ds/keys [component-def] :as system}]
     (some-> (validate conf system
                       (:salmon/pre-schema component-def)
                       (:template (:conf component-def))
                       :pre? true)
             ->validation))
   :schema stack-schema})
