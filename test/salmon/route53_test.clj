(ns salmon.route53-test
  (:require [clojure.test :refer [deftest is]]
            [cognitect.aws.client.api :as aws]
            [salmon.route53 :as r53]))

(deftest test-fetch-hosted-zone-id
  (let [client (aws/client {:api :route53})]
    (is (= "Z08609191OFSO5HMA450N"
          (r53/fetch-hosted-zone-id client "shafferstest.net"))
      "Exact DNS name lookups work")
    (is (= "Z08609191OFSO5HMA450N"
          (r53/fetch-hosted-zone-id client "john.shafferstest.net"))
      "Subdomain lookups work")
    (is (= "Z08609191OFSO5HMA450N"
          (r53/fetch-hosted-zone-id client "a.b.c.shafferstest.net"))
      "Multiple subdomain lookups work")
    (is (= "Z08609191OFSO5HMA450N"
          (r53/fetch-hosted-zone-id client "*.shafferstest.net"))
      "Wildcard subdomain lookups work")
    (is (= "Z08609191OFSO5HMA450N"
          (r53/fetch-hosted-zone-id client "*.b.c.shafferstest.net"))
      "Deep wildcard subdomain lookups work")))
