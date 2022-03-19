(ns salmon.cloudformation.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [donut.system :as ds]
   [salmon.cloudformation.interface :as cfn]
   [salmon.validation.interface :as val])
  (:import
   (clojure.lang ExceptionInfo)))

(defn system-a [stack]
  {::ds/base {:salmon/pre-validate val/pre-validate-conf}
   ::ds/defs
   {:services {:comp {:conf {:name :conf}
                      :start (fn [_ _ _] {:started? true})
                      :stop (fn [_ _ _])}
               :x :X
               :y "Y!"
               :stack-a stack}}
   ::ds/signals
   {:salmon/pre-validate {:order :reverse-topsort}}})

(defn stack-a [& {:as opts}]
  (-> {:name "stack-a"}
      (merge opts)
      cfn/stack))

(deftest test-blank-template-pre-validation
  (testing "Blank templates should fail validation"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Pre-validation failed: Template must be a map"
         (val/pre-validate! (system-a (stack-a)))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Pre-validation failed: Template must be a map"
         (val/pre-validate! (system-a (stack-a :template "")))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Pre-validation failed: Template must be a map"
         (val/pre-validate! (system-a (stack-a :template 1)))))
    (is (thrown-with-msg?
         ExceptionInfo
         #"Pre-validation failed: Template must not be empty"
         (val/pre-validate! (system-a (stack-a :template {})))))))

(deftest test-pre-validation-linting
  (testing "cfn-lint doesn't run unless :lint? is true"
    (is (val/pre-validate! (system-a (stack-a :template {:a 1})))))
  (testing "cfn-lint works in :pre-validate when there are no refs in the template"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Pre-validation failed: E1001 Top level template section a is not valid"
         (val/pre-validate! (system-a (stack-a :lint? true :template {:a 1}))))))
  (testing "cfn-lint works in :pre-validate when all refs have been resolved"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Pre-validation failed: E1001 Top level template section a is not valid"
         (val/pre-validate! (system-a (stack-a :lint? true :template {:a (ds/ref :y)})))))))
