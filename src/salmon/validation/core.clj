(ns salmon.validation.core
  (:require [com.rpl.specter :as sp]
            [donut.system :as ds]
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

(defn refs [x]
  (map second (sp/select (sp/walker ds/ref?) x)))

(defn refs-resolveable? [system x]
  (let [instances (-> system ::ds/instances :services)
        resolved (-> system ::ds/resolved :services)]
    (every?
     #(or (get instances %)
          (not (fn? (get-in resolved [% :start]))))
     (refs x))))
