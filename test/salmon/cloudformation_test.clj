(ns salmon.cloudformation-test
  (:require [clojure.test :refer [deftest is testing]]
            [donut.system :as ds]
            [malli.generator :as mg]
            [salmon.cloudformation :as cfn]
            [salmon.signal :as sig])
  (:import (clojure.lang ExceptionInfo)))

(def system-base
  {::ds/base {:salmon/early-validate sig/early-validate-conf}
   ::ds/signals
   {:salmon/delete {:order :topsort :returns-instance? true}
    :salmon/early-validate {:order :reverse-topsort}
    ::ds/validate {:order :reverse-topsort}}})

(defn system-a [stack]
  (assoc
   system-base
   ::ds/defs
   {:services {:comp {::ds/config {:name :conf}
                      ::ds/start (constantly {:started? true})
                      ::ds/stop (fn [_])}
               :empty {::ds/start (constantly {})}
               :T {::ds/start (constantly :T)}
               :x {::ds/start :X}
               :y {::ds/start "Y!"}
               :stack-a stack}}))

(defn system-b [stack stack-properties]
  (assoc
   system-base
   ::ds/defs
   {:services {:stack-a stack
               :stack-properties-a stack-properties}}))

(defn rand-stack-name []
  (mg/generate [:re cfn/re-stack-name]))

(defn stack-a [& {:as opts}]
  (-> {:name (rand-stack-name)}
      (merge opts)
      cfn/stack))

(defn stack-properties-a [& {:as opts}]
  (-> {:name (ds/local-ref [:stack-a :name])}
      (merge opts)
      cfn/stack-properties))

(deftest test-blank-template-early-validation
  (testing "Blank templates should fail validation"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :salmon/early-validate: Template must be a map"
         (sig/early-validate! (system-a (stack-a)))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :salmon/early-validate: Template must be a map"
         (sig/early-validate! (system-a (stack-a :template "")))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :salmon/early-validate: Template must be a map"
         (sig/early-validate! (system-a (stack-a :template 1)))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :salmon/early-validate: Template must not be empty"
         (sig/early-validate! (system-a (stack-a :template {})))))))

(deftest test-early-validation-linting
  (testing "cfn-lint works in :early-validate when there are no refs in the template"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :salmon/early-validate: E1001 Top level template section a is not valid"
         (sig/early-validate! (system-a (stack-a :lint? true :template {:a 1}))))))
  (testing "cfn-lint doesn't run unless :lint? is true"
    (is (sig/early-validate! (system-a (stack-a :template {:a 1})))))
  (testing "cfn-lint works in :early-validate when all refs have been resolved"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :salmon/early-validate: E1001 Top level template section a is not valid"
         (sig/early-validate! (system-a (stack-a :lint? true :template {:a (ds/ref [:services :y])}))))))
  (testing "cfn-lint works in :early-validate when all local refs have been resolved"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :salmon/early-validate: E1001 Top level template section a is not valid"
         (sig/early-validate! (system-a (stack-a :lint? true :template {:a (ds/local-ref [:y])}))))))
  (testing "Pre-validation linting doesn't run for a ref to an un-started services"
    (is (sig/early-validate! (system-a (stack-a :lint? true :template (ds/ref [:services :T]))))))
  (testing "Pre-validation linting doesn't run if the template has a nested ref to an un-started service"
    (is (sig/early-validate! (system-a (stack-a :lint? true :template {:a (ds/ref [:services :T])}))))))

(deftest test-validation-linting
  (testing "ref templates are validated during :start"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :donut.system/start: Template must not be empty"
         (sig/start! (system-a (stack-a :lint? true :template (ds/local-ref [:empty]))))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :donut.system/start: Template must be a map"
         (sig/start! (system-a (stack-a :lint? true :template (ds/local-ref [:T])))))))
  (testing "Templates with deep refs are validated during :start"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :donut.system/start: E1001 Top level template section a is not valid"
         (sig/start! (system-a (stack-a :lint? true :template {:a (ds/local-ref [:empty])})))))))

(defn iam-user [name]
  {:Type "AWS::IAM::User"
   :Properties
   {:UserName name}})

(defn oai [comment]
  {:Type "AWS::CloudFront::CloudFrontOriginAccessIdentity"
   :Properties
   {:CloudFrontOriginAccessIdentityConfig
    {:Comment comment}}})

(def template-a
  {:AWSTemplateFormatVersion "2010-09-09"
   :Resources
   {:OAI1 (oai "OAI1")}})

(def template-b
  (assoc-in template-a [:Resources :OAI2] (oai "OAI2")))

(deftest test-lifecycle
  (let [stack-name (rand-stack-name)
        system (atom nil)]
    (testing ":start works"
      (reset! system (sig/start! (system-a (stack-a :lint? true
                                                    :name stack-name
                                                    :template template-a))))
      (is (-> @system ::ds/instances :services :stack-a :client))
      (testing ":start is idempotent"
        (let [start (System/nanoTime)]
          (is (= (::ds/instances @system) (::ds/instances (sig/start! @system))))
          (is (> 30 (quot (- (System/nanoTime) start) 1000000)))))
      (testing ":stop works"
        (reset! system (sig/stop! @system))
        (is (= nil (-> @system ::ds/instances :services :stack-a :client)))
        (testing ":stop is idempotent"
          (let [start (System/nanoTime)]
            (is (= (::ds/instances @system) (::ds/instances (sig/stop! @system))))
            (is (> 30 (quot (- (System/nanoTime) start) 1000000)))))
        (testing "system can be restarted after :stop"
          (reset! system (sig/start! @system))
          (is (-> @system ::ds/instances :services :stack-a :client))))
      (testing ":delete works"
        (let [stack-id (-> @system ::ds/instances :services :stack-a :stack-id)]
          (reset! system (sig/delete! @system))
          (is (= {:name stack-name :stack-id stack-id}
                 (-> @system ::ds/instances :services :stack-a))))
        (testing ":delete is idempotent"
          (let [start (System/nanoTime)]
            (is (= (::ds/instances @system) (::ds/instances (sig/delete! @system))))
            (is (> 30 (quot (- (System/nanoTime) start) 1000000)))))
        (testing "system can be restarted after :delete"
          (reset! system (sig/start! @system))
          (is (-> @system ::ds/instances :services :stack-a :client)))
        (sig/delete! @system)))))

(deftest test-update
  (let [name (rand-stack-name)
        sys (atom nil)]
    (reset! sys (sig/start! (system-a (stack-a :name name :template template-a))))
    (testing "Template update works during :start"
      (reset! sys (sig/start! (system-a (stack-a :name name :template template-b))))
      (is (= #{"OAI1" "OAI2"}
             (->> @sys ::ds/instances :services :stack-a :resources
                  (map :LogicalResourceId) set))))
    (sig/delete! @sys)))

(deftest test-no-change-start
  (testing "If no changes are to be made to the template, start succeeds"
    (let [stack-name (rand-stack-name)
          template (assoc template-a :Outputs
                          {"OUT1" {:Value "1" :Export {:Name (str stack-name "-OUT1")}}})
          sys (system-a (stack-a :name stack-name :template template))
          _ (sig/start! sys)
          sys (sig/start! sys)]
      (is (= ["OAI1"]
             (->> sys ::ds/instances :services :stack-a :resources
                  (map :LogicalResourceId)))
          "Resources are correct")
      (is (= {:OUT1 "1"}
             (->> sys ::ds/instances :services :stack-a :outputs))
          "Outputs are correct")
      (sig/delete! sys))))

(deftest test-outputs
  (let [stack-name (rand-stack-name)
        template (assoc template-a :Outputs
                        {"OUT1" {:Value "1" :Export {:Name (str stack-name "-OUT1")}
                                 :Description "OUT1 desc"}
                         "OUT2" {:Value "2" :Export {:Name (str stack-name "-OUT2")}}})
        sys (sig/start! (system-a (stack-a :name stack-name :template template)))]
    (is (= {:OUT1 "1" :OUT2 "2"}
           (->> sys ::ds/instances :services :stack-a :outputs))
        "Outputs are retrieved and attached to the stack instance")
    (is (= {:OUT1 {:OutputValue "1" :ExportName (str stack-name "-OUT1")
                   :Description "OUT1 desc"}
            :OUT2 {:OutputValue "2" :ExportName (str stack-name "-OUT2")}}
           (->> sys ::ds/instances :services :stack-a :outputs-raw))
        "Outputs are retrieved and attached to the stack instance")
    (sig/delete! sys)))

(deftest test-resources
  (let [sys (sig/start! (system-a (stack-a :template template-b)))]
    (is (= #{"OAI1" "OAI2"}
           (->> sys ::ds/instances :services :stack-a :resources
                (map :LogicalResourceId) set))
        "Resources are retrieved and attached to the stack instance")
    (sig/delete! sys)))

(deftest test-aws-error-messages
  (testing "AWS error messages are included in thrown exceptions"
    (is (thrown-with-msg?
         ExceptionInfo
         #".*Error creating stack.*Template format error"
         (sig/start! (system-a (stack-a :template {:a 1})))))))

(deftest test-capabilities
  (let [template {:AWSTemplateFormatVersion "2010-09-09"
                  :Resources
                  {:User1 (iam-user "User1")}}]
    (is (thrown-with-msg?
         ExceptionInfo
         #"Error creating stack.*Requires capabilities"
         (sig/start! (system-a (stack-a :template template)))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed"
         (sig/early-validate! (system-a (stack-a
                                         :capabilities #{"CAPABILITY_MADE_UP"}
                                         :template template)))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed"
         (sig/start! (system-a (stack-a
                                :capabilities #{"CAPABILITY_MADE_UP"}
                                :template template)))))
    (let [sys (sig/start! (system-a (stack-a
                                     :capabilities #{"CAPABILITY_NAMED_IAM"}
                                     :template template)))]
      (is (= ["User1"]
             (->> sys ::ds/instances :services :stack-a :resources
                  (map :LogicalResourceId))))
      (sig/delete! sys))))

(deftest test-parameters
  (let [template {:AWSTemplateFormatVersion "2010-09-09"
                  :Parameters {:Username {:Description "Username"
                                          :Type "String"}}
                  :Resources
                  {:User1 (iam-user {:Ref "Username"})}}]
    (is (thrown-with-msg?
         ExceptionInfo
         #"Error creating stack.*Parameters"
         (sig/start! (system-a (stack-a :template template)))))
    (let [sys (sig/start! (system-a (stack-a
                                     :capabilities #{"CAPABILITY_NAMED_IAM"}
                                     :parameters {:Username "User1"}
                                     :template template)))]
      (is (= ["User1"]
             (->> sys ::ds/instances :services :stack-a :resources
                  (map :LogicalResourceId))))
      (sig/delete! sys))))

(deftest test-stack-properties-validation
  (testing "stack-properties early-validation works"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :salmon/early-validate: \{:name.*}"
         (sig/early-validate! (system-b
                               (stack-a :template template-a)
                               (stack-properties-a)))))))

(deftest test-stack-properties
  (let [stack-name (rand-stack-name)
        template (assoc template-b :Outputs
                        {"OUT1" {:Value "1" :Export {:Name (str stack-name "-OUT1")}
                                 :Description "OUT1 desc"}
                         "OUT2" {:Value "2" :Export {:Name (str stack-name "-OUT2")}}})
        system (atom nil)]
    (testing ":start works"
      (reset! system (sig/start! (system-b
                                  (stack-a :lint? true
                                           :name stack-name
                                           :template template)
                                  (stack-properties-a))))
      (is (-> @system ::ds/instances :services :stack-properties-a))
      (testing ":start is idempotent"
        (let [start (System/nanoTime)]
          (is (= (::ds/instances @system) (::ds/instances (sig/start! @system))))
          (is (> 30 (quot (- (System/nanoTime) start) 1000000)))))
      (is (= #{"OAI1" "OAI2"}
             (->> @system ::ds/instances :services :stack-properties-a :resources
                  (map :LogicalResourceId) set))
          "Resources are retrieved and attached to the stack-properties instance")
      (is (= {:OUT1 "1" :OUT2 "2"}
             (->> @system ::ds/instances :services :stack-properties-a :outputs))
          "Outputs are retrieved and attached to the stack-properties instance")
      (is (= {:OUT1 {:OutputValue "1" :ExportName (str stack-name "-OUT1")
                     :Description "OUT1 desc"}
              :OUT2 {:OutputValue "2" :ExportName (str stack-name "-OUT2")}}
             (->> @system ::ds/instances :services :stack-properties-a :outputs-raw))
          "Outputs are retrieved and attached to the stack-properties instance")
      (testing ":stop works"
        (reset! system (sig/stop! @system))
        (let [stack-id (-> @system ::ds/instances :services :stack-a :stack-id)]
          (is (= {:name stack-name :stack-id stack-id}
                 (-> @system ::ds/instances :services :stack-properties-a))))
        (testing ":stop is idempotent"
          (let [start (System/nanoTime)]
            (is (= (::ds/instances @system) (::ds/instances (sig/stop! @system))))
            (is (> 30 (quot (- (System/nanoTime) start) 1000000)))))
        (testing "system can be restarted after :stop"
          (reset! system (sig/start! @system))
          (is (-> @system ::ds/instances :services :stack-properties-a :resources))))
      (testing ":delete works"
        (let [stack-id (-> @system ::ds/instances :services :stack-a :stack-id)]
          (reset! system (sig/delete! @system))
          (is (= {:name stack-name :stack-id stack-id}
                 (-> @system ::ds/instances :services :stack-properties-a))))
        (testing ":delete is idempotent"
          (let [start (System/nanoTime)]
            (is (= (::ds/instances @system) (::ds/instances (sig/delete! @system))))
            (is (> 30 (quot (- (System/nanoTime) start) 1000000)))))
        (testing "system can be restarted after :delete"
          (reset! system (sig/start! @system))
          (is (-> @system ::ds/instances :services :stack-properties-a :resources)))
        (sig/delete! @system)))))
