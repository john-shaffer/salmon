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

(defn refs [x]
  (map second (sp/select (sp/walker ds/ref?) x)))

(defn refs-resolved? [system x]
  (let [resolved-services (-> system ::ds/resolved :services (or {}))]
    (every? #(contains? resolved-services %) (refs x))))

(defn resolve-refs [system x]
  (sp/transform
   (sp/walker ds/ref?)
   (fn [[_ k :as ref]]
     (let [start (get-in system [::ds/resolved :services k :start])]
       (if (fn? start)
         ref
         start)))
   x))

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
     (fn [{:keys [template] :as conf} _ {:keys [->validation]
                                         ::ds/keys [component-def resolved-component resolved]
                                         :as system}]
       (let [schema (:salmon/pre-schema component-def)]
         (if-let [errors (and schema (m/explain schema conf))]
           (->validation errors)
           (when (refs-resolved? system (:template (:conf component-def)))
             (let [template (resolve-refs system (:template (:conf component-def)))]
               (cond
                 (not (map? template)) (->validation {:message "Template must be a map."})
                 (empty? template) (->validation {:message "Template must not be empty."})
                 :else (when lint?
                         (let [{:keys [message]} (#'template :template template)]
                           (when (seq message)
                             (->validation {:message message}))))))))))
     :schema stack-schema
     :validate
     (fn [conf _ _] conf nil)}))

;    Capabilities: ["CAPABILITY_IAM"],
;    Parameters: event.Parameters,
;    StackName: event.StackName,
;    TemplateURL: TEMPLATE_URL,
;    TimeoutInMinutes: 60
