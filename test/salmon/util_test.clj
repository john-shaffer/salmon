(ns salmon.util-test
  (:require [clojure.test :refer [deftest is testing]]
            [salmon.util :as u]))

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
