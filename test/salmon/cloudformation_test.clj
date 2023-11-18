(ns salmon.cloudformation-test
  (:require [clojure.test :refer [deftest is testing]]
            [donut.system :as ds]
            [salmon.cloudformation :as cfn]
            [salmon.signal :as sig]
            [salmon.test :as test]
            [salmon.util :as u])
  (:import (clojure.lang ExceptionInfo)))

(defn system-a [stack]
  (assoc
    test/system-base
    ::ds/defs
    {:services {:comp {::ds/config {:name :conf}
                       ::ds/start (constantly {:started? true})
                       ::ds/stop (fn [_])}
                :empty {::ds/start (constantly {})}
                :empty-m {::ds/start {}}
                :nested {::ds/start {:empty {}}}
                :T {::ds/start (constantly :T)}
                :x {::ds/start :X}
                :y {::ds/start "Y!"}
                :stack-a stack}}))

(defn system-b [stack stack-properties]
  (assoc
    test/system-base
    ::ds/defs
    {:services {:stack-a stack
                :stack-properties-a stack-properties}}))

(defn stack-a [& {:as opts}]
  (-> {:name (test/rand-stack-name)}
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
  (testing "Pre-validation linting doesn't run for a ref to an un-started services"
    (is (sig/early-validate! (system-a (stack-a :lint? true :template (ds/ref [:services :T]))))))
  (testing "Pre-validation linting doesn't run if the template has a nested ref to an un-started service"
    (is (sig/early-validate! (system-a (stack-a :lint? true :template {:a (ds/ref [:services :T])}))))))

(deftest test-local-ref-early-validation
  (testing "Pre-validation linting doesn't run for a local ref to an un-started service"
    (is (sig/early-validate! (system-a (stack-a :lint? true :template (ds/local-ref [:T])))))
    (is (sig/early-validate! (system-a (stack-a :lint? true :template (ds/local-ref [:T :dne]))))))
  (testing "Local refs should be resolved and their values validated during early-validate!"
    (is (thrown-with-msg?
          ExceptionInfo
          #"Validation failed during :salmon/early-validate: Template must not be empty"
          (sig/early-validate! (system-a (stack-a :template (ds/local-ref [:empty-m]))))))
    (is (thrown-with-msg?
          ExceptionInfo
          #"Validation failed during :salmon/early-validate: Template must be a map"
          (sig/early-validate! (system-a (stack-a :lint? true :template (ds/local-ref [:x]))))))
    (is (thrown-with-msg?
          ExceptionInfo
          #"Validation failed during :salmon/early-validate: E1001 Top level template section a is not valid"
          (sig/early-validate! (system-a (stack-a :lint? true :template {:a (ds/local-ref [:nested :empty])}))))
      "Deep local ref")))

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
  (let [{:keys [regions]} (test/get-config)
        stack-name (test/rand-stack-name)]
    (doseq [region regions
            :let [system-def (system-a (stack-a :lint? true
                                         :name stack-name
                                         :region region
                                         :template template-a))]]
      (test/with-system-delete [system system-def]
        (testing ":start works"
          ; with-system-delete automatically sends start
          (is (-> @system ::ds/instances :services :stack-a :client))
          (testing ":start is idempotent"
            (let [start (System/nanoTime)]
              (is (= (::ds/instances @system) (::ds/instances (sig/start! @system))))
              (is (> 60 (quot (- (System/nanoTime) start) 1000000)))))
          (testing ":stop works"
            (reset! system (sig/stop! @system))
            (is (= nil (-> @system ::ds/instances :services :stack-a :client)))
            (testing ":stop is idempotent"
              (let [start (System/nanoTime)]
                (is (= (::ds/instances @system) (::ds/instances (sig/stop! @system))))
                (is (> 60 (quot (- (System/nanoTime) start) 1000000)))))
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
                (is (> 60 (quot (- (System/nanoTime) start) 1000000)))))
            (testing "system can be restarted after :delete"
              (reset! system (sig/start! @system))
              (is (-> @system ::ds/instances :services :stack-a :client)))))))))

(deftest test-lifecycle-while-in-progress
  (let [{:keys [regions]} (test/get-config)]
    (doseq [region regions
            :let [bucket-def-a {:Type "AWS::S3::Bucket"
                                :Properties {:BucketName (test/rand-bucket-name)}}
                  bucket-def-b {:Type "AWS::S3::Bucket"
                                :Properties {:BucketName (test/rand-bucket-name)}}
                  template-a {:AWSTemplateFormatVersion "2010-09-09"
                              :Resources {:BucketA bucket-def-a}}
                  template-b {:AWSTemplateFormatVersion "2010-09-09"
                              :Resources {:BucketB bucket-def-b}}
                  stack-a (cfn/stack
                            :name (test/rand-stack-name)
                            :region region
                            :template template-a)
                  stack-b (assoc-in stack-a [::ds/config :template] template-b)
                  system-def-a (assoc test/system-base
                                 ::ds/defs {:test {:stack stack-a}})
                  system-def-b (assoc test/system-base
                                 ::ds/defs {:test {:stack stack-b}})]]
      (test/with-system-delete [system (assoc system-def-a :start? false)]
        ; We can't control the timing, so this may not always catch the
        ; stack during an IN_PROGRESS state. It's enough to use a resource
        ; that takes a few seconds to create. We don't want to use resources
        ; that took a very long time (like CloudFront distributions) because
        ; it's not worth the wait.
        (future (swap! system sig/start!))
        (Thread/sleep 1000)
        (testing "stack start waits on CREATE_IN_PROGRESS state to complete"
          (reset! system (sig/start! system-def-b))
          (is (= {:ResourceStatus "CREATE_COMPLETE"}
                (-> @system ::ds/instances :test :stack :resources :BucketB
                  (select-keys [:ResourceStatus])))))
        (reset! system (sig/start! system-def-a))
        (future (reset! system (sig/start! system-def-b)))
        (Thread/sleep 1000)
        (testing "stack start waits on UPDATE_IN_PROGRESS state to complete"
          (reset! system (sig/start! system-def-a))
          (is (= {:ResourceStatus "CREATE_COMPLETE"}
                (-> @system ::ds/instances :test :stack :resources :BucketA
                  (select-keys [:ResourceStatus])))))
        (let [sys @system
              stack (-> sys ::ds/instances :test :stack (select-keys [:name :stack-id]))
              _ (future (reset! system (sig/start! system-def-a)))
              _ (Thread/sleep 1000)
              updated (future (reset! system (sig/delete! sys)))]
          (Thread/sleep 1000)
          (testing "stack deletion waits on DELETE_IN_PROGRESS state to complete"
            (is (= stack
                  (-> (reset! system (sig/delete! sys))
                    ::ds/instances :test :stack))))
          (testing "stack deletion waits on UPDATE_IN_PROGRESS state to complete"
            (is (= stack (-> @updated ::ds/instances :test :stack)))))))))

(deftest test-update
  (let [{:keys [regions]} (test/get-config)
        name (test/rand-stack-name)]
    (doseq [region regions
            :let [system-def (system-a (stack-a
                                         :name name
                                         :region region
                                         :template template-a))]]
      (test/with-system-delete [system system-def]
        (testing "Template update works during :start"
          (reset! system (sig/start! (system-a (stack-a :name name :template template-b))))
          (is (= #{:OAI1 :OAI2}
                (->> @system ::ds/instances :services :stack-a :resources
                  keys set))))))))

(deftest test-no-change-start
  (testing "If no changes are to be made to the template, start succeeds"
    (let [stack-name (test/rand-stack-name)
          template (assoc template-a :Outputs
                     {"OUT1" {:Value "1" :Export {:Name (str stack-name "-OUT1")}}})
          sys (system-a (stack-a :name stack-name :template template))
          _ (sig/start! sys)
          sys (sig/start! sys)]
      (is (= [:OAI1]
            (->> sys ::ds/instances :services :stack-a :resources keys))
        "Resources are correct")
      (is (= {:OUT1 "1"}
            (->> sys ::ds/instances :services :stack-a :outputs))
        "Outputs are correct")
      (sig/delete! sys))))

(deftest test-describe-stack-raw
  (let [stack-name (test/rand-stack-name)
        sys (sig/start! (system-a (stack-a :capabilities #{"CAPABILITY_NAMED_IAM"} :name stack-name :template template-a)))]
    (testing "Raw stack description is retrieved and attached to the stack-properties instance"
      (is (= ["CAPABILITY_NAMED_IAM"]
            (->> sys ::ds/instances :services :stack-a :describe-stack-raw :Capabilities)))
      (is (inst?
            (->> sys ::ds/instances :services :stack-a :describe-stack-raw :CreationTime))))
    (sig/delete! sys)))

(deftest test-outputs
  (let [stack-name (test/rand-stack-name)
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
    (is (= #{:OAI1 :OAI2}
          (->> sys ::ds/instances :services :stack-a :resources
            keys set))
      "Resources are retrieved and attached to the stack instance")
    (is (= #{:DriftInformation :LastUpdatedTimestamp :PhysicalResourceId
             :ResourceStatus :ResourceType}
          (->> sys ::ds/instances :services :stack-a :resources
            vals (mapcat keys) set))
      "Resource maps have the expected keys")
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
      (is (= [:User1]
            (->> sys ::ds/instances :services :stack-a :resources keys)))
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
      (is (= [:User1]
            (->> sys ::ds/instances :services :stack-a :resources keys)))
      (is (= {:Username {:ParameterValue "User1"}}
            (->> sys ::ds/instances :services :stack-a :parameters-raw)))
      (is (= {:Username "User1"}
            (->> sys ::ds/instances :services :stack-a :parameters)))
      (sig/delete! sys))))

(deftest test-tags
  (let [sys (sig/start! (system-a (stack-a
                                    :tags (u/tags {:cost "iam"})
                                    :template template-a)))
        stack-a (->> sys ::ds/instances :services :stack-a)]
    (is (= [:OAI1]
          (-> stack-a :resources keys)))
    (is (= {:cost {:Value "iam"}}
          (-> stack-a :tags-raw)))
    (is (= {:cost "iam"}
          (-> stack-a :tags)))
    (sig/delete! sys)))

(deftest test-stack-properties-validation
  (testing "stack-properties early-validation works"
    (is (thrown-with-msg?
          ExceptionInfo
          #"Validation failed during :salmon/early-validate: \{:name.*}"
          (sig/early-validate! (system-b
                                 (stack-a :template template-a)
                                 (stack-properties-a)))))))

(deftest test-stack-properties
  (let [stack-name (test/rand-stack-name)
        template (assoc template-b
                   :Parameters
                   {:Username {:Description "Username" :Type "String"}}
                   :Outputs
                   {"OUT1" {:Value "1" :Export {:Name (str stack-name "-OUT1")}
                            :Description "OUT1 desc"}
                    "OUT2" {:Value "2" :Export {:Name (str stack-name "-OUT2")}}
                         ;; We have to use the parameter to pass linting
                    "Username" {:Value {:Ref "Username"} :Export {:Name (str stack-name "-Username")}}})
        system (atom nil)]
    (testing ":start works"
      (reset! system (sig/start! (system-b
                                   (stack-a :capabilities #{"CAPABILITY_NAMED_IAM"}
                                     :lint? true
                                     :name stack-name
                                     :parameters {:Username "User1"}
                                     :tags (u/tags {:env "test"})
                                     :template template)
                                   (stack-properties-a))))
      (is (-> @system ::ds/instances :services :stack-properties-a))
      (testing ":start is idempotent"
        (let [start (System/nanoTime)]
          (is (= (::ds/instances @system) (::ds/instances (sig/start! @system))))
          (is (> 60 (quot (- (System/nanoTime) start) 1000000)))))
      (is (= #{:OAI1 :OAI2}
            (->> @system ::ds/instances :services :stack-properties-a :resources
              keys set))
        "Resources are retrieved and attached to the stack-properties instance")
      (testing "Raw stack description is retrieved and attached to the stack-properties instance"
        (is (= ["CAPABILITY_NAMED_IAM"]
              (->> @system ::ds/instances :services :stack-properties-a :describe-stack-raw :Capabilities)))
        (is (inst?
              (->> @system ::ds/instances :services :stack-properties-a :describe-stack-raw :CreationTime))))
      (is (= {:Username "User1"}
            (->> @system ::ds/instances :services :stack-properties-a :parameters))
        "Parameters are retrieved and attached to the stack-properties instance")
      (is (= {:Username {:ParameterValue "User1"}}
            (->> @system ::ds/instances :services :stack-properties-a :parameters-raw))
        "Parameters are retrieved and attached to the stack-properties instance")
      (is (= {:OUT1 "1" :OUT2 "2" :Username "User1"}
            (->> @system ::ds/instances :services :stack-properties-a :outputs))
        "Outputs are retrieved and attached to the stack-properties instance")
      (is (= {:env "test"}
            (->> @system ::ds/instances :services :stack-properties-a :tags))
        "Tags are retrieved and attached to the stack-properties instance")
      (is (= {:OUT1 {:OutputValue "1" :ExportName (str stack-name "-OUT1")
                     :Description "OUT1 desc"}
              :OUT2 {:OutputValue "2" :ExportName (str stack-name "-OUT2")}
              :Username {:OutputValue "User1" :ExportName (str stack-name "-Username")}}
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
            (is (> 60 (quot (- (System/nanoTime) start) 1000000)))))
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
            (is (> 60 (quot (- (System/nanoTime) start) 1000000)))))
        (testing "system can be restarted after :delete"
          (reset! system (sig/start! @system))
          (is (-> @system ::ds/instances :services :stack-properties-a :resources)))
        (sig/delete! @system)))))
