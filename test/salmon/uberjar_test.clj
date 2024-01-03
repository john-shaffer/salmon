(ns salmon.uberjar-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [donut.system :as ds]
            [salmon.signal :as sig]
            [salmon.test :as test]
            [salmon.uberjar :as uber]))

(deftest test-build-hello-http
  (testing "uberjar component can build hello-http project"
    (let [sys-def (assoc test/system-base
                    ::ds/defs
                    {:hello-http
                     {:uberjar (uber/uberjar
                                 {:aliases [:uberjar]
                                  :project-dir "test/projects/hello-http"})}})
          sys (atom sys-def)
          c #(-> @sys ::ds/instances :hello-http :uberjar)]
      (try
        (is (swap! sys sig/start!)
          "can be started")
        (is (-> (c) :uber-file (str/ends-with? "uberjar.jar"))
          ":uber-file is present and is a string")
        (is (swap! sys sig/stop!)
          "can be stopped")
        (finally
          (swap! sys sig/stop!))))))
