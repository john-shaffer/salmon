(ns salmon.cloudformation.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [donut.system :as ds]
            [salmon.cloudformation.interface :as cfn]
            [salmon.signal.interface :as sig])
  (:import (clojure.lang ExceptionInfo)))

(defn system-a [stack]
  {::ds/base {:salmon/pre-validate sig/pre-validate-conf}
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
   {:salmon/pre-validate {:order :reverse-topsort}
    :validate {:order :reverse-topsort}}})

(defn stack-a [& {:as opts}]
  (-> {:name "stack-a"}
      (merge opts)
      cfn/stack))

(deftest test-blank-template-pre-validation
  (testing "Blank templates should fail validation"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :salmon/pre-validate: Template must be a map"
         (sig/pre-validate! (system-a (stack-a)))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :salmon/pre-validate: Template must be a map"
         (sig/pre-validate! (system-a (stack-a :template "")))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :salmon/pre-validate: Template must be a map"
         (sig/pre-validate! (system-a (stack-a :template 1)))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :salmon/pre-validate: Template must not be empty"
         (sig/pre-validate! (system-a (stack-a :template {})))))))

(deftest test-pre-validation-linting
  (testing "cfn-lint doesn't run unless :lint? is true"
    (is (sig/pre-validate! (system-a (stack-a :template {:a 1})))))
  (testing "cfn-lint works in :pre-validate when there are no refs in the template"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :salmon/pre-validate: E1001 Top level template section a is not valid"
         (sig/pre-validate! (system-a (stack-a :lint? true :template {:a 1}))))))
  (testing "cfn-lint works in :pre-validate when all refs have been resolved"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Validation failed during :salmon/pre-validate: E1001 Top level template section a is not valid"
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

(def template
  {:AWSTemplateFormatVersion "2010-09-09"
   :Resources
   {:OAI
    {:Type "AWS::CloudFront::CloudFrontOriginAccessIdentity"
     :Properties
     {:CloudFrontOriginAccessIdentityConfig
      {:Comment "Test"}}}}})

(deftest test-deploy
  (let [system (sig/start! (system-a (stack-a :lint? true
                                              :template template)))]
    (testing ":start is idempotent"
      (let [start (System/nanoTime)]
        (is (= system (sig/start! system)))
        (is (> 30 (quot (- (System/nanoTime) start) 1000000)))))))

(deftest test-aws-error-messages
  (testing "AWS error messages are included in thrown exceptions"
    (is (thrown-with-msg?
         ExceptionInfo
         #".*Error creating stack.*Template format error"
         (sig/start! (system-a (stack-a :template {:a 1})))))))
