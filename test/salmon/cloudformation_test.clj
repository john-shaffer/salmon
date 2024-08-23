(ns salmon.cloudformation-test
  (:require [clojure.test :refer [deftest is testing]]
            [donut.system :as ds]
            [salmon.cloudformation :as cfn]
            [salmon.test :as test]
            [salmon.util :as u])
  (:import (clojure.lang ExceptionInfo)
           [java.util.concurrent Executors]))

(defn system-a [stack]
  (let [pool (Executors/newFixedThreadPool 8)]
    (assoc
      test/system-base
      ::ds/execute (fn [f] (.execute pool f))
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
                  :stack-a stack}})))

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

(defmacro cause [& body]
  `(try
     (do ~@body)
     (catch Exception e#
       (throw (or (ex-cause e#) e#)))))

(defn bucket-template [& {:keys [name]}]
  {:AWSTemplateFormatVersion "2010-09-09"
   :Resources
   {:Bucket
    {:Type "AWS::S3::Bucket"
     :Properties
     {:BucketName (or name (test/rand-bucket-name))}}}})

(defn template-system [& {:keys [name template]}]
  (assoc
    test/system-base
    ::ds/defs
    {:services
     {:stack
      (cfn/stack
        {:capabilities #{"CAPABILITY_NAMED_IAM"}
         :name (or name (test/rand-stack-name))
         :template template})}}))

(deftest test-blank-template-early-validation
  (testing "Blank templates should fail validation"
    (is (thrown-with-msg?
          ExceptionInfo
          #"Template must be a map"
          (cause (ds/signal (system-a (stack-a))
                   :salmon/early-validate))))
    (is (thrown-with-msg?
          ExceptionInfo
          #"Template must be a map"
          (cause (ds/signal (system-a (stack-a :template ""))
                   :salmon/early-validate))))
    (is (thrown-with-msg?
          ExceptionInfo
          #"Template must be a map"
          (cause (ds/signal (system-a (stack-a :template 1))
                   :salmon/early-validate))))
    (is (thrown-with-msg?
          ExceptionInfo
          #"Template must not be empty"
          (cause (ds/signal (system-a (stack-a :template {}))
                   :salmon/early-validate))))))

(deftest test-early-validation-linting
  (testing "cfn-lint works in :early-validate when there are no refs in the template"
    (is (thrown-with-msg?
          ExceptionInfo
          #"E1001 Top level template section a is not valid"
          (cause (ds/signal (system-a (stack-a :lint? true :template {:a 1}))
                   :salmon/early-validate)))))
  (testing "cfn-lint doesn't run unless :lint? is true"
    (is (ds/signal (system-a (stack-a :template {:a 1}))
          :salmon/early-validate)))
  (testing "cfn-lint works in :early-validate when all refs have been resolved"
    (is (thrown-with-msg?
          ExceptionInfo
          #"E1001 Top level template section a is not valid"
          (cause (ds/signal (system-a (stack-a :lint? true :template {:a (ds/ref [:services :y])}))
                   :salmon/early-validate))))
    (testing "Pre-validation linting doesn't run for a ref to an un-started services"
      (is (ds/signal (system-a (stack-a :lint? true :template (ds/ref [:services :T])))
            :salmon/early-validate)))
    (testing "Pre-validation linting doesn't run if the template has a nested ref to an un-started service"
      (is (ds/signal (system-a (stack-a :lint? true :template {:a (ds/ref [:services :T])}))
            :salmon/early-validate)))))

(deftest test-local-ref-early-validation
  (testing "Pre-validation linting doesn't run for a local ref to an un-started service"
    (is (ds/signal (system-a (stack-a :lint? true :template (ds/local-ref [:T])))
          :salmon/early-validate))
    (is (ds/signal (system-a (stack-a :lint? true :template (ds/local-ref [:T :dne])))
          :salmon/early-validate)))
  (testing "Local refs should be resolved and their values validated during early-validate!"
    (is (thrown-with-msg?
          ExceptionInfo
          #"Template must not be empty"
          (cause (ds/signal (system-a (stack-a :template (ds/local-ref [:empty-m])))
                   :salmon/early-validate))))
    (is (thrown-with-msg?
          ExceptionInfo
          #"Template must be a map"
          (cause (ds/signal (system-a (stack-a :lint? true :template (ds/local-ref [:x])))
                   :salmon/early-validate))))
    (is (thrown-with-msg?
          ExceptionInfo
          #"E1001 Top level template section a is not valid"
          (cause (ds/signal (system-a (stack-a :lint? true :template {:a (ds/local-ref [:nested :empty])}))
                   :salmon/early-validate)))
      "Deep local ref")))

(deftest test-validation-linting
  (testing "ref templates are validated during :start"
    (is (thrown-with-msg?
          ExceptionInfo
          #"Template must not be empty"
          (cause (ds/start (system-a (stack-a :lint? true :template (ds/local-ref [:empty])))))))
    (is (thrown-with-msg?
          ExceptionInfo
          #"Template must be a map"
          (cause (ds/start (system-a (stack-a :lint? true :template (ds/local-ref [:T]))))))))
  (testing "Templates with deep refs are validated during :start"
    (is (thrown-with-msg?
          ExceptionInfo
          #"E1001 Top level template section a is not valid"
          (cause (ds/start (system-a (stack-a :lint? true :template {:a (ds/local-ref [:empty])}))))))))

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
              (is (= (::ds/instances @system) (::ds/instances (ds/start @system))))
              (is (> 60 (quot (- (System/nanoTime) start) 1000000)))))
          (testing ":stop works"
            (reset! system (ds/stop @system))
            (is (= nil (-> @system ::ds/instances :services :stack-a :client)))
            (testing ":stop is idempotent"
              (let [start (System/nanoTime)]
                (is (= (::ds/instances @system) (::ds/instances (ds/stop @system))))
                (is (> 60 (quot (- (System/nanoTime) start) 1000000)))))
            (testing "system can be restarted after :stop"
              (reset! system (ds/start @system))
              (is (-> @system ::ds/instances :services :stack-a :client))))
          (testing ":delete works"
            (let [stack-id (-> @system ::ds/instances :services :stack-a :stack-id)]
              (reset! system (ds/signal @system :salmon/delete))
              (is (= {:name stack-name :stack-id stack-id}
                    (-> @system ::ds/instances :services :stack-a))))
            (testing ":delete is idempotent"
              (let [start (System/nanoTime)]
                (is (= (::ds/instances @system) (::ds/instances (ds/signal @system :salmon/delete))))
                (is (> 60 (quot (- (System/nanoTime) start) 1000000)))))
            (testing "system can be restarted after :delete"
              (reset! system (ds/start @system))
              (is (-> @system ::ds/instances :services :stack-a :client)))))))))

(deftest test-rollback-status
  (let [{:keys [regions]} (test/get-config)]
    (doseq [region regions
            :let [bucket-def-a {:Type "AWS::S3::Bucket"
                                :Properties {:BucketName (test/rand-bucket-name)}}
                  ; Deliberately create an invalid bucket name
                  bucket-name-b (as-> (test/rand-bucket-name) $
                                  (subs $ 0 (min 60 (count $)))
                                  (str $ ".."))
                  bucket-def-b {:Type "AWS::S3::Bucket"
                                :Properties {:BucketName bucket-name-b}}
                  bucket-def-c {:Type "AWS::S3::Bucket"
                                :Properties {:BucketName (test/rand-bucket-name)}}
                  template-a {:AWSTemplateFormatVersion "2010-09-09"
                              :Resources {:BucketA bucket-def-a}}
                  template-b {:AWSTemplateFormatVersion "2010-09-09"
                              :Resources {:BucketB bucket-def-b}}
                  template-c {:AWSTemplateFormatVersion "2010-09-09"
                              :Resources {:BucketC bucket-def-c}}
                  stack-a (cfn/stack
                            :name (test/rand-stack-name)
                            :region region
                            :template template-a)
                  stack-b (assoc-in stack-a [::ds/config :template] template-b)
                  stack-c (assoc-in stack-a [::ds/config :template] template-c)
                  system-def-a (assoc test/system-base
                                 ::ds/defs {:test {:stack stack-a}})
                  system-def-b (assoc test/system-base
                                 ::ds/defs {:test {:stack stack-b}})
                  system-def-c (assoc test/system-base
                                 ::ds/defs {:test {:stack stack-c}})]]
      (test/with-system-delete [system system-def-a]
        (testing "Entering a rollback status causes an exception"
          (is (thrown-with-msg? ExceptionInfo
                ; Can be in any UPDATE_ROLLBACK_ state
                #"UPDATE_ROLLBACK.*"
                (cause (reset! system (ds/start system-def-b))))))
        (testing "A stack in a rollback status can be updated"
          (reset! system (ds/start system-def-c))
          (is (= {:ResourceStatus "CREATE_COMPLETE"}
                (-> @system ::ds/instances :test :stack :resources :BucketC
                  (select-keys [:ResourceStatus])))))))))

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
        (future (swap! system ds/start))
        (Thread/sleep 1000)
        (testing "stack start waits on CREATE_IN_PROGRESS state to complete"
          (reset! system (ds/start system-def-b))
          (is (= {:ResourceStatus "CREATE_COMPLETE"}
                (-> @system ::ds/instances :test :stack :resources :BucketB
                  (select-keys [:ResourceStatus])))))
        (reset! system (ds/start system-def-a))
        (future (reset! system (ds/start system-def-b)))
        (Thread/sleep 1000)
        (testing "stack start waits on UPDATE_IN_PROGRESS state to complete"
          (reset! system (ds/start system-def-a))
          (is (= {:ResourceStatus "CREATE_COMPLETE"}
                (-> @system ::ds/instances :test :stack :resources :BucketA
                  (select-keys [:ResourceStatus])))))
        (let [sys @system
              stack (-> sys ::ds/instances :test :stack (select-keys [:name :stack-id]))
              _ (future (reset! system (ds/start system-def-a)))
              _ (Thread/sleep 1000)
              updated (future (reset! system (ds/signal sys :salmon/delete)))]
          (Thread/sleep 1000)
          (testing "stack deletion waits on DELETE_IN_PROGRESS state to complete"
            (is (= stack
                  (-> (reset! system (ds/signal sys :salmon/delete))
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
          (reset! system (ds/start (system-a (stack-a :name name :template template-b))))
          (is (= #{:OAI1 :OAI2}
                (->> @system ::ds/instances :services :stack-a :resources
                  keys set))))))))

(deftest test-no-change-start
  (testing "If no changes are to be made to the template, start succeeds"
    (let [stack-name (test/rand-stack-name)
          template (assoc template-a :Outputs
                     {"OUT1" {:Value "1" :Export {:Name (str stack-name "-OUT1")}}})
          sys (system-a (stack-a :name stack-name :template template))
          _ (ds/start sys)
          sys (ds/start sys)]
      (is (= [:OAI1]
            (->> sys ::ds/instances :services :stack-a :resources keys))
        "Resources are correct")
      (is (= {:OUT1 "1"}
            (->> sys ::ds/instances :services :stack-a :outputs))
        "Outputs are correct")
      (ds/signal sys :salmon/delete))))

(deftest test-describe-stack-raw
  (let [stack-name (test/rand-stack-name)
        sys (ds/start (system-a (stack-a :capabilities #{"CAPABILITY_NAMED_IAM"} :name stack-name :template template-a)))]
    (testing "Raw stack description is retrieved and attached to the stack-properties instance"
      (is (= ["CAPABILITY_NAMED_IAM"]
            (->> sys ::ds/instances :services :stack-a :describe-stack-raw :Capabilities)))
      (is (inst?
            (->> sys ::ds/instances :services :stack-a :describe-stack-raw :CreationTime))))
    (ds/signal sys :salmon/delete)))

(deftest test-outputs
  (let [stack-name (test/rand-stack-name)
        template (assoc template-a :Outputs
                   {"OUT1" {:Value "1" :Export {:Name (str stack-name "-OUT1")}
                            :Description "OUT1 desc"}
                    "OUT2" {:Value "2" :Export {:Name (str stack-name "-OUT2")}}})
        sys (ds/start (system-a (stack-a :name stack-name :template template)))]
    (is (= {:OUT1 "1" :OUT2 "2"}
          (->> sys ::ds/instances :services :stack-a :outputs))
      "Outputs are retrieved and attached to the stack instance")
    (is (= {:OUT1 {:OutputValue "1" :ExportName (str stack-name "-OUT1")
                   :Description "OUT1 desc"}
            :OUT2 {:OutputValue "2" :ExportName (str stack-name "-OUT2")}}
          (->> sys ::ds/instances :services :stack-a :outputs-raw))
      "Outputs are retrieved and attached to the stack instance")
    (ds/signal sys :salmon/delete)))

(deftest test-resources
  (let [sys (ds/start (system-a (stack-a :template template-b)))]
    (is (= #{:OAI1 :OAI2}
          (->> sys ::ds/instances :services :stack-a :resources
            keys set))
      "Resources are retrieved and attached to the stack instance")
    (is (= #{:DriftInformation :LastUpdatedTimestamp :PhysicalResourceId
             :ResourceStatus :ResourceType}
          (->> sys ::ds/instances :services :stack-a :resources
            vals (mapcat keys) set))
      "Resource maps have the expected keys")
    (ds/signal sys :salmon/delete)))

(deftest test-aws-error-messages
  (testing "AWS error messages are included in thrown exceptions"
    (is (thrown-with-msg?
          ExceptionInfo
          #".*Error creating stack.*Template format error"
          (cause (ds/start (system-a (stack-a :template {:a 1}))))))))

(deftest test-capabilities
  (let [template {:AWSTemplateFormatVersion "2010-09-09"
                  :Resources
                  {:User1 (iam-user (test/rand-iam-username))}}]
    (is (thrown-with-msg?
          ExceptionInfo
          #"Error creating stack.*Requires capabilities"
          (cause (ds/start (system-a (stack-a :template template))))))
    (is (thrown-with-msg?
          ExceptionInfo
          #"Validation failed"
          (cause (ds/signal (system-a (stack-a
                                        :capabilities #{"CAPABILITY_MADE_UP"}
                                        :template template))
                   :salmon/early-validate))))
    (is (thrown-with-msg?
          ExceptionInfo
          #"Validation failed"
          (cause (ds/start (system-a (stack-a
                                       :capabilities #{"CAPABILITY_MADE_UP"}
                                       :template template))))))
    (let [sys (ds/start (system-a (stack-a
                                    :capabilities #{"CAPABILITY_NAMED_IAM"}
                                    :template template)))]
      (is (= [:User1]
            (->> sys ::ds/instances :services :stack-a :resources keys)))
      (ds/signal sys :salmon/delete))))

(deftest test-parameters
  (let [template {:AWSTemplateFormatVersion "2010-09-09"
                  :Parameters {:Username {:Description "Username"
                                          :Type "String"}}
                  :Resources
                  {:User1 (iam-user {:Ref "Username"})}}]
    (is (thrown-with-msg?
          ExceptionInfo
          #"Error creating stack.*Parameters"
          (cause (ds/start (system-a (stack-a :template template))))))
    (let [username (test/rand-iam-username)
          sys (ds/start (system-a (stack-a
                                    :capabilities #{"CAPABILITY_NAMED_IAM"}
                                    :parameters {:Username username}
                                    :template template)))]
      (is (= [:User1]
            (->> sys ::ds/instances :services :stack-a :resources keys)))
      (is (= {:Username {:ParameterValue username}}
            (->> sys ::ds/instances :services :stack-a :parameters-raw)))
      (is (= {:Username username}
            (->> sys ::ds/instances :services :stack-a :parameters)))
      (ds/signal sys :salmon/delete))))

(deftest test-tags
  (let [sys (ds/start (system-a (stack-a
                                  :tags (u/tags {:cost "iam"})
                                  :template template-a)))
        stack-a (->> sys ::ds/instances :services :stack-a)]
    (is (= [:OAI1]
          (-> stack-a :resources keys)))
    (is (= {:cost {:Value "iam"}}
          (-> stack-a :tags-raw)))
    (is (= {:cost "iam"}
          (-> stack-a :tags)))
    (ds/signal sys :salmon/delete)))

(deftest test-stack-properties-validation
  (testing "stack-properties early-validation works"
    (is (thrown-with-msg?
          ExceptionInfo
          #"Validation failed.*name"
          (cause (ds/signal (system-b
                              (stack-a :template template-a)
                              (stack-properties-a))
                   :salmon/early-validate))))))

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
        system (atom nil)
        username (test/rand-iam-username)]
    (testing ":start works"
      (reset! system (ds/start (system-b
                                 (stack-a :capabilities #{"CAPABILITY_NAMED_IAM"}
                                   :lint? true
                                   :name stack-name
                                   :parameters {:Username username}
                                   :tags (u/tags {:env "test"})
                                   :template template)
                                 (stack-properties-a))))
      (is (-> @system ::ds/instances :services :stack-properties-a))
      (testing ":start is idempotent"
        (let [start (System/nanoTime)]
          (is (= (::ds/instances @system) (::ds/instances (ds/start @system))))
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
      (is (= {:Username username}
            (->> @system ::ds/instances :services :stack-properties-a :parameters))
        "Parameters are retrieved and attached to the stack-properties instance")
      (is (= {:Username {:ParameterValue username}}
            (->> @system ::ds/instances :services :stack-properties-a :parameters-raw))
        "Parameters are retrieved and attached to the stack-properties instance")
      (is (= {:OUT1 "1" :OUT2 "2" :Username username}
            (->> @system ::ds/instances :services :stack-properties-a :outputs))
        "Outputs are retrieved and attached to the stack-properties instance")
      (is (= {:env "test"}
            (->> @system ::ds/instances :services :stack-properties-a :tags))
        "Tags are retrieved and attached to the stack-properties instance")
      (is (= {:OUT1 {:OutputValue "1" :ExportName (str stack-name "-OUT1")
                     :Description "OUT1 desc"}
              :OUT2 {:OutputValue "2" :ExportName (str stack-name "-OUT2")}
              :Username {:OutputValue username :ExportName (str stack-name "-Username")}}
            (->> @system ::ds/instances :services :stack-properties-a :outputs-raw))
        "Outputs are retrieved and attached to the stack-properties instance")
      (testing ":stop works"
        (reset! system (ds/stop @system))
        (let [stack-id (-> @system ::ds/instances :services :stack-a :stack-id)]
          (is (= {:name stack-name :stack-id stack-id}
                (-> @system ::ds/instances :services :stack-properties-a))))
        (testing ":stop is idempotent"
          (let [start (System/nanoTime)]
            (is (= (::ds/instances @system) (::ds/instances (ds/stop @system))))
            (is (> 60 (quot (- (System/nanoTime) start) 1000000)))))
        (testing "system can be restarted after :stop"
          (reset! system (ds/start @system))
          (is (-> @system ::ds/instances :services :stack-properties-a :resources))))
      (testing ":delete works"
        (let [stack-id (-> @system ::ds/instances :services :stack-a :stack-id)]
          (reset! system (ds/signal @system :salmon/delete))
          (is (= {:name stack-name :stack-id stack-id}
                (-> @system ::ds/instances :services :stack-properties-a))))
        (testing ":delete is idempotent"
          (let [start (System/nanoTime)]
            (is (= (::ds/instances @system) (::ds/instances (ds/signal @system :salmon/delete))))
            (is (> 60 (quot (- (System/nanoTime) start) 1000000)))))
        (testing "system can be restarted after :delete"
          (reset! system (ds/start @system))
          (is (-> @system ::ds/instances :services :stack-properties-a :resources)))
        (ds/signal @system :salmon/delete)))))

(deftest test-stack-rollback-error-message
  (let [stack-name (test/rand-stack-name)
        template (bucket-template :name "amazon.com")
        system-def (assoc
                     test/system-base
                     ::ds/defs
                     {:services
                      {:stack
                       (cfn/stack
                         {:capabilities #{"CAPABILITY_NAMED_IAM"}
                          :name stack-name
                          :template template})}})
        system (atom system-def)
        e (try (cause (swap! system ds/start))
            (catch Exception e
              e))
        {:keys [event-cause name status]} (ex-data e)]
    (testing "Rollback exception includes the source of the failure"
      (is (re-find #"Bucket failed with reason:.*amazon\.com already exists"
            (ex-message e))
        "Error message includes resource error message")
      (is (#{"ROLLBACK_COMPLETE" "ROLLBACK_IN_PROGRESS"} status))
      (is (= stack-name name))
      (is (= {:LogicalResourceId "Bucket"
              :PhysicalResourceId ""
              :ResourceProperties "{\"BucketName\":\"amazon.com\"}"
              :ResourceStatus "CREATE_FAILED"
              :ResourceType "AWS::S3::Bucket"
              :StackName stack-name}
            (select-keys event-cause [:LogicalResourceId :PhysicalResourceId :ResourceProperties
                                      :ResourceStatus :ResourceType :StackName]))))))

(deftest test-stack-rollback-state
  (let [system (atom (template-system :template (bucket-template :name " ")))]
    (testing "Force a rollback state"
      (is (thrown-with-msg? ExceptionInfo #"ROLLBACK_(COMPLETE|IN_PROGRESS)"
            (cause (swap! system ds/start)))))
    (testing "Creating a stack with the same name as a stack in a rollback state succeeds"
      (reset! system (template-system :template (bucket-template)))
      (swap! system ds/start)
      (is (= "CREATE_COMPLETE"
            (->> @system ::ds/instances :services :stack :resources
              :Bucket :ResourceStatus))))))

(deftest test-no-changes-update-rollback-complete
  (let [stack-name (test/rand-stack-name)
        system-def (template-system :name stack-name :template (bucket-template))
        system (atom system-def)]
    (swap! system ds/start)
    (testing "Force the stack to rollback"
      (reset! system (template-system :name stack-name :template (bucket-template :name " ")))
      (is (thrown-with-msg? ExceptionInfo #"UPDATE_ROLLBACK_(COMPLETE|IN_PROGRESS)"
            (cause (swap! system ds/start)))))
    (testing "The stack can be started with no changes when in UPDATE_ROLLBACK_COMPLETE status"
      (reset! system system-def)
      ; start will wait for _COMPLETE status before attempting to update
      (swap! system ds/start))))
