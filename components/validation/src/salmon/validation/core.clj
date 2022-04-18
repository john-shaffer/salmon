(ns salmon.validation.core
  (:require [donut.system :as ds]
            [malli.util :as mu]))

(defn allow-refs* [map-entry]
  (mapv
   (fn [[kw schema conds]]
     [kw schema
      [:or
       [:tuple [:= ::ds/ref] keyword?]
       conds]])
   map-entry))

(defn allow-refs [map-schema]
  (mu/transform-entries map-schema allow-refs*))
