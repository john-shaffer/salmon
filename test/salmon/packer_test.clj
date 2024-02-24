(ns salmon.packer-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [donut.system :as ds]
            [salmon.packer :as pkr]
            [salmon.ssm :as ssm]
            [salmon.test :as test]
            [salmon.uberjar :as uber]))

(deftest test-build-hello-http
  (let [{:keys [regions]} (test/get-config)]
    (testing "ami component can build hello-http project"
      (doseq [region regions
              :let [sys-def (assoc test/system-base
                              ::ds/defs
                              {:hello-http
                               {:ami (pkr/ami
                                       {:dir "test/projects/hello-http"
                                        :template-file "ami.pkr.hcl"
                                        :deps (ds/local-ref [:debian])
                                        :vars
                                        {:region (name region)
                                         :source_ami (ds/local-ref [:debian :parameters :ami :Value])
                                         :uberjar_path (ds/local-ref [:uberjar :uber-file])}})
                                :debian (ssm/parameters-getter
                                          {:parameters {:ami "/aws/service/debian/release/bookworm/latest/amd64"}
                                           :region region})
                                :uberjar (uber/uberjar
                                           {:aliases [:uberjar]
                                            :project-dir "test/projects/hello-http"})}})
                    sys (atom sys-def)
                    c #(-> @sys ::ds/instances :hello-http :ami)]]
        (try
          (is (swap! sys ds/start)
            "can be started")
          (is (-> (c) :ami (str/starts-with? "ami-"))
            ":ami is present and valid")
          (is (-> (c) :region (= (name region)))
            ":region is correct")
          (is (swap! sys ds/stop)
            "can be stopped")
          (finally
            (swap! sys ds/stop)))))))
