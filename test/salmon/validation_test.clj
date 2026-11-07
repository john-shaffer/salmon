(ns salmon.validation-test
  (:require [clojure.test :refer [deftest is]]
            [donut.system :as ds]
            [salmon.validation :as val]))

(deftest test-resolve-refs-of-started-components
  (let [system {::ds/instances {:group {:comp {:a {:b "value" :f false}}}}}
        resolve-ref #(val/resolve-refs system [:group :other] %)]
    (is (= {:a {:b "value" :f false}} (resolve-ref (ds/ref [:group :comp]))))
    (is (= "value" (resolve-ref (ds/ref [:group :comp :a :b])))
      "a ref can reach inside of an instance")
    (is (= "value" (resolve-ref (ds/local-ref [:comp :a :b]))))
    (is (false? (resolve-ref (ds/ref [:group :comp :a :f])))
      "a false value isn't mistaken for a component that hasn't started")))
