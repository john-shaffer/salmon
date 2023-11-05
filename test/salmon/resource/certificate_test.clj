(ns salmon.resource.certificate-test
  (:require [clojure.test :refer (deftest is)]
            [donut.system :as ds]
            [salmon.cloudformation :as cfn]
            [salmon.resource.certificate :as cert]
            [salmon.test :as test]))

(deftest test-dns-validated
  (let [{:keys [regions test-domain]} (test/get-config)
        {:keys [apex hosted-zone-id]} test-domain]
    (doseq [region regions
            :let [label (test/rand-dns-label)
                  ; Max of 64 chars
                  domain-name (str label "." apex)
                  domain-name (->> domain-name
                                (drop (- (count domain-name) 64))
                                (apply str))
                  cert (cert/dns-validated :domain-name domain-name :hosted-zone-id hosted-zone-id)
                  template {:AWSTemplateFormatVersion "2010-09-09"
                            :Resources {:Cert cert}}
                  stack (cfn/stack
                          :name (test/rand-stack-name)
                          :region region
                          :template template)
                  system-def (assoc test/system-base
                               ::ds/defs {:test {:stack stack}})]]
      (test/with-system-delete [system system-def]
        (is (= {:ResourceStatus "CREATE_COMPLETE", :ResourceType "AWS::CertificateManager::Certificate"}
              (-> @system ::ds/instances :test :stack :resources :Cert
                (select-keys [:ResourceStatus :ResourceType]))))))))
