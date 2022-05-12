(ns salmon.cloudformation.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [donut.system :as ds]
            [malli.generator :as mg]
            [salmon.cloudformation.interface :as cfn]
            [salmon.signal.interface :as sig])
  (:import (clojure.lang ExceptionInfo)))

(defn system-a [stack]
  {::ds/base {:pre-validate sig/pre-validate-conf}
   ::ds/defs
   {:services {:comp {:conf {:name :conf}
                      :start (fn [_ _ _] {:started? true})
                      :stop (fn [_ _ _])}
               :empty {:start (fn [_ _ _] {})}
               :T {:start (fn [_ _ _] :T)}
               :x :X
               :y "Y!"
               :stack-a stack}}
   ::ds/signals
   {:delete {:order :topsort}
    :pre-validate {:order :reverse-topsort}
    :validate {:order :reverse-topsort}}})

(defn rand-stack-name []
  (mg/generate [:re cfn/re-stack-name]))

(defn stack-a [& {:as opts}]
  (-> {:name (rand-stack-name)}
      (merge opts)
      cfn/stack))

(deftest test-blank-template-pre-validation
  (testing "Blank templates should fail validation"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :pre-validate: Template must be a map"
         (sig/pre-validate! (system-a (stack-a)))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :pre-validate: Template must be a map"
         (sig/pre-validate! (system-a (stack-a :template "")))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :pre-validate: Template must be a map"
         (sig/pre-validate! (system-a (stack-a :template 1)))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :pre-validate: Template must not be empty"
         (sig/pre-validate! (system-a (stack-a :template {})))))))

(deftest test-pre-validation-linting
  (testing "cfn-lint doesn't run unless :lint? is true"
    (is (sig/pre-validate! (system-a (stack-a :template {:a 1})))))
  (testing "cfn-lint works in :pre-validate when there are no refs in the template"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :pre-validate: E1001 Top level template section a is not valid"
         (sig/pre-validate! (system-a (stack-a :lint? true :template {:a 1}))))))
  (testing "cfn-lint works in :pre-validate when all refs have been resolved"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :pre-validate: E1001 Top level template section a is not valid"
         (sig/pre-validate! (system-a (stack-a :lint? true :template {:a (ds/ref :y)}))))))
  (testing "Pre-validation linting doesn't run for a ref to an un-started services"
    (is (sig/pre-validate! (system-a (stack-a :lint? true :template (ds/ref :T))))))
  (testing "Pre-validation linting doesn't run if the template has a nested ref to an un-started service"
    (is (sig/pre-validate! (system-a (stack-a :lint? true :template {:a (ds/ref :T)}))))))

(deftest test-validation-linting
  (testing "ref templates are validated during :start"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :start: Template must not be empty"
         (sig/start! (system-a (stack-a :lint? true :template (ds/ref :empty))))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :start: Template must be a map"
         (sig/start! (system-a (stack-a :lint? true :template (ds/ref :T)))))))
  (testing "Templates with deep refs are validated during :start"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :start: E1001 Top level template section a is not valid"
         (sig/start! (system-a (stack-a :lint? true :template {:a (ds/ref :empty)})))))))

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
  (let [system (atom nil)]
    (testing ":start works"
      (reset! system (sig/start! (system-a (stack-a :lint? true
                                                    :template template-a))))
      (is (-> @system ::ds/instances :services :stack-a :client))
      (testing ":start is idempotent"
        (let [start (System/nanoTime)]
          (is (= @system (sig/start! @system)))
          (is (> 30 (quot (- (System/nanoTime) start) 1000000)))))
      (testing ":stop works"
        (reset! system (sig/stop! @system))
        (is (= nil (-> @system ::ds/instances :services :stack-a :client)))
        (testing ":stop is idempotent"
          (let [start (System/nanoTime)]
            (is (= @system (sig/stop! @system)))
            (is (> 30 (quot (- (System/nanoTime) start) 1000000)))))
        (testing "system can be restarted after :stop"
          (reset! system (sig/start! @system))
          (is (-> @system ::ds/instances :services :stack-a :client))))
      (testing ":delete works"
        (reset! system (sig/delete! @system))
        (is (= nil (-> @system ::ds/instances :services :stack-a :client)))
        (testing ":delete is idempotent"
          (let [start (System/nanoTime)]
            (is (= @system (sig/delete! @system)))
            (is (> 30 (quot (- (System/nanoTime) start) 1000000)))))
        (testing "system can be restarted after :delete"
          (reset! system (sig/start! @system))
          (is (-> @system ::ds/instances :services :stack-a :client)))
        (sig/delete! @system)))))

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