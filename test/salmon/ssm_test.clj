(ns salmon.ssm-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [donut.system :as ds]
            [salmon.signal :as sig]
            [salmon.ssm :as ssm]
            [salmon.test :as test]))

(deftest test-parameters-getter
  (let [{:keys [regions]} (test/get-config)]
    (testing "parameters-getter component"
      (doseq [region regions
              :let [param-name "/aws/service/debian/release/bookworm/latest/amd64"
                    sys-def (assoc test/system-base
                              ::ds/defs
                              {:ssm
                               {:parameters-getter
                                (ssm/parameters-getter
                                  {:parameters {:ami param-name}
                                   :region region})}})
                    sys (atom sys-def)
                    c #(-> @sys ::ds/instances :ssm :parameters-getter)]]
        (try
          (is (swap! sys sig/start!)
            "can be started")
          (is (-> (c) :parameters :ami :Name (= param-name))
            "parameter name is correct")
          (is (-> (c) :parameters :ami :Value (str/starts-with? "ami-"))
            "parameter value is correct")
          (is (-> (c) :parameters :ami :Type (= "String"))
            "parameter type is correct")
          (is (swap! sys sig/stop!)
            "can be stopped")
          (finally
            (swap! sys sig/stop!)))))))
