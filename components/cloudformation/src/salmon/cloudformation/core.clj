(ns salmon.cloudformation.core
  (:require
   [clojure.data.json :as json]
   [clojure.java.shell :as sh]
   [donut.system :as ds]
   [malli.core :as m]
   [salmon.util.interface :as util]
   [salmon.validation.interface :as val]))

(defn cfn-lint! [template]
  (util/with-temp-file! [f {:prefix "salmon.cloudformation-"
                            :suffix ".template"}]
    (spit f template)
    (let [{:keys [err exit out]} (sh/sh "cfn-lint" (str f))]
      (cond
        (zero? exit) nil
        (empty? err) out
        (empty? out) err
        :else (str err out)))))

(defn template [& {:keys [template validate?]
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

(defn has-refs? [x]
  (seq
   (com.rpl.specter/select (com.rpl.specter/walker ds/ref?) x)))

(defn refs-resolved? [conf conf-def]
  (or
   (not (has-refs? conf-def))))

; template is a file
; template is a string (check validity of json)
(defn stack [& {:keys [lint?] :as conf}]
  ;; account for deep refs in template!
  #_[& {:keys [capabilities name parameters template timeout-minutes validate?]
        :or {validate? true}}]
  ;;    let [template-json (@#'template* template)]
  (let [pre-schema (val/allow-refs stack-schema)]
    {:conf (assoc conf :comp/name :stack)
     :start
     (fn [_ _ _])
     :stop
     (fn [_ _ _])
     :salmon/dry-run
     (fn [_ _ _])
     :salmon/pre-schema pre-schema
;; make sure template isn't {}
     :salmon/pre-validate
     (fn [{:keys [template] :as conf} _ {:keys [->validation ::ds/component-def]}]
       (let [schema (:salmon/pre-schema component-def)]
         (if-let [errors (and schema (m/explain schema conf))]
           (->validation errors)
           (when (refs-resolved? template (:template (:conf component-def)))
             (cond
               (not (map? template)) (->validation {:message "Template must be a map."})
               (empty? template) (->validation {:message "Template must not be empty."})
               :else (when lint?
                       (let [{:keys [message]} (#'template :template template)]
                         (when (seq message)
                           (->validation {:message message})))))))))
     :schema stack-schema
     :validate
     (fn [conf _ _] conf nil)}))

;    Capabilities: ["CAPABILITY_IAM"],
;    Parameters: event.Parameters,
;    StackName: event.StackName,
;    TemplateURL: TEMPLATE_URL,
;    TimeoutInMinutes: 60




