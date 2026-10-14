(ns salmon.util-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cognitect.aws.client.api :as aws]
   [salmon.fake-http :as fake]
   [salmon.util :as u])
  (:import
   (java.io IOException)))

(def describe-stacks-response
  (fake/xml-response
    "<DescribeStacksResponse><DescribeStacksResult><Stacks/></DescribeStacksResult></DescribeStacksResponse>"))

(deftest test-retriable?
  (testing "Retries what cognitect.aws.retry/default-retriable? does"
    (is (true? (u/retriable? {:cognitect.anomalies/category :cognitect.anomalies/busy})))
    (is (true? (u/retriable? {:cognitect.anomalies/category :cognitect.anomalies/unavailable}))))
  (testing "Retries faults caused by an IOException"
    (is (true? (u/retriable? (fake/io-fault))))
    (is (true? (u/retriable? {:cognitect.anomalies/category :cognitect.anomalies/fault
                              :cognitect.aws/throwable (IOException. "EOF")}))))
  (testing "Doesn't retry other faults"
    (is (false? (u/retriable? {:cognitect.anomalies/category :cognitect.anomalies/fault})))
    (is (false? (u/retriable? {:cognitect.anomalies/category :cognitect.anomalies/fault
                               :cognitect.aws/throwable (RuntimeException. "oops")}))))
  (testing "Doesn't retry other anomalies or successful responses"
    (is (false? (u/retriable? {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                               :cognitect.aws/throwable (IOException. "EOF")})))
    (is (false? (u/retriable? {:Stacks []})))))

(deftest test-client
  (testing "Retries requests that fail with an IOException"
    (let [[http-client requests] (fake/http-client [(fake/io-fault) describe-stacks-response])
          client (u/client (assoc fake/client-opts :api :cloudformation :http-client http-client))
          r (aws/invoke client {:op :DescribeStacks})]
      (is (not (u/anomaly? r)))
      (is (= 2 @requests))))
  (testing "Allows overriding :retriable?"
    (let [[http-client requests] (fake/http-client [(fake/io-fault) describe-stacks-response])
          client (u/client (assoc fake/client-opts
                             :api :cloudformation
                             :http-client http-client
                             :retriable? (constantly false)))
          r (aws/invoke client {:op :DescribeStacks})]
      (is (u/anomaly? r))
      (is (= 1 @requests)))))

(deftest test-invoke-with-timeout
  (testing "Sends a request again when it times out"
    (let [[http-client requests] (fake/http-client [:no-response describe-stacks-response])
          client (u/client (assoc fake/client-opts :api :cloudformation :http-client http-client))
          ;; Long enough for the first aws-api call in a cold JVM
          r (u/invoke-with-timeout client {:op :DescribeStacks} 1000)]
      (is (= {:Stacks []} r))
      (is (= 2 @requests))))
  (testing "Returns an anomaly when every attempt times out"
    (let [[http-client requests] (fake/http-client [:no-response])
          client (u/client (assoc fake/client-opts :api :cloudformation :http-client http-client))
          r (u/invoke-with-timeout client {:op :DescribeStacks} 100)]
      (is (= :cognitect.anomalies/interrupted (:cognitect.anomalies/category r)))
      (is (= 3 @requests)))))

(deftest test-tags
  (testing "Empty values result in nil"
    (is (= nil (u/tags nil)))
    (is (= nil (u/tags {}))))
  (testing "Sequential values are returned as a vector"
    (is (= [{:Key "a" :Value "b"}] (u/tags [{:Key "a" :Value "b"}])))
    (is (= [{:Key "a" :Value "b"} {:Key "c" :Value "d"}]
          (u/tags (list {:Key "a" :Value "b"} {:Key "c" :Value "d"}))))
    (is (vector? (u/tags (list {:Key "a" :Value "b"} {:Key "c" :Value "d"})))))
  (testing "Maps are turned into a vector of {:Key :Value} maps."
    (is (= [{:Key "a" :Value "b"}] (u/tags {:a "b"})))
    (is (vector? (u/tags {:a "b"})))
    (is (= #{{:Key "a" :Value "b"} {:Key "c" :Value "d"}}
          (set (u/tags {:a "b" :c "d"})))))
  (is (= [{:Key "a/c" :Value "b"}] (u/tags {:a/c "b"}))
    "Keyword namespaces are retained."))

(deftest test-resource
  (is (= {:Type "AWS::S3::Bucket", :Properties {:Tags [{:Key "a", :Value "b"}]}}
        (u/resource "AWS::S3::Bucket"
          {:Tags (u/tags {:a "b"})}))
    "Basic resources work")
  (is (= {:Type "AWS::S3::Bucket", :Properties {:Tags [{:Key "a", :Value "b"}]}}
        (u/resource "AWS::S3::Bucket"
          {:Tags (u/tags {:a "b"})}
          {:depends-on nil :metadata nil}))
    "nil values are dropped")
  (is (= {:Type "AWS::S3::Bucket", :Properties {:Tags [{:Key "a", :Value "b"}]}}
        (u/resource "AWS::S3::Bucket"
          {:Tags (u/tags {:a "b"}) :Name nil}))
    "nil properties are dropped"))
