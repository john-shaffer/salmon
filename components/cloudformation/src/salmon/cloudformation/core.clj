(ns salmon.cloudformation.core
  (:require
   [babashka.fs :as fs]
   [clojure.data.json :as json]
   [clojure.java.shell :as sh]
   [com.rpl.specter :as sp]
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

(defn refs [x]
  (map second (sp/select (sp/walker ds/ref?) x)))

(defn refs-resolveable?
  "Returns true if all refs refer to either started services or constant
   values."
  [system x]
  (let [instances (-> system ::ds/instances :services)
        resolved (-> system ::ds/resolved :services)]
    (every?
     #(or (get instances %)
          (not (fn? (get-in resolved [% :start]))))
     (refs x))))

; template is a file
; template is a string (check validity of json)
(defn stack [& {:keys [lint?] :as conf}]
  #_[& {:keys [capabilities name parameters template timeout-minutes validate?]
        :or {validate? true}}]
  (let [pre-schema (val/allow-refs stack-schema)]
    {:conf (assoc conf :comp/name :stack)
     :start
     (fn [_ _ _])
     :stop
     (fn [_ _ _])
     :salmon/dry-run
     (fn [_ _ _])
     :salmon/pre-schema pre-schema
     :salmon/pre-validate
     (fn [conf _ {:keys [->validation]
                  ::ds/keys [component-def]
                  :as system}]
       (let [schema (:salmon/pre-schema component-def)
             template (:template (:conf component-def))]
         (if-let [errors (and schema (m/explain schema conf))]
           (->validation errors)
           (when (refs-resolveable? system (:template (:conf component-def)))
             (cond
               (not (map? template)) (->validation {:message "Template must be a map."})
               (empty? template) (->validation {:message "Template must not be empty."})
               :else (when lint?
                       (let [{:keys [message]} (template-data :template template)]
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
