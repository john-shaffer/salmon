(ns salmon.validation
  (:require [com.rpl.specter :as sp]
            [donut.system :as ds]
            [malli.util :as mu]))

(defn- allow-refs* [map-entry]
  (mapv
   (fn [[kw schema conds]]
     [kw schema
      [:or
       ds/Ref
       ds/LocalRef
       conds]])
   map-entry))

(defn allow-refs
 "Transform a map schema to allow entries to match either `donut.system/ref?`
  or the original schema.

  This produces a schema that can be used to do partial validation against
  known values before the other values are known."
  [map-schema]
  (mu/transform-entries map-schema allow-refs*))

(defn- refs [x]
  (sp/select (sp/walker ds/ref?) x))

(defn- resolve-ref [system ref]
  (let [{::ds/keys [instances resolved-defs]} system
        k (ds/ref-key ref)
        k2 (take 2 k)
        instance (get-in instances k2)
        def (get-in resolved-defs k2)]
    (cond
      instance (get-in instance (drop 2 k))
      def (::ds/start (get-in def (drop 2 k))))))

(defn resolve-refs
  "Resolve all refs in x."
  [system x]
  (sp/transform (sp/walker ds/ref?) (partial resolve-ref system) x))

(defn- ref-resolveable? [system component-id ref]
  (let [{::ds/keys [instances resolved-defs]} system
        rt (ds/ref-type ref)
        rkey (cond
               (= ::ds/ref rt)
               (take 2 (ds/ref-key ref))

               (= ::ds/local-ref rt)
               (into [(first component-id)]
                     (ds/ref-key ref))

               :else
               (throw (ex-info "Not a ref" {:value ref})))]
    (boolean
     (or
      (get-in instances rkey)
      (some-> resolved-defs (get-in rkey) ::ds/start fn? not)))))

(defn refs-resolveable?
  "Returns true if all refs refer to either started services or constant
   values."
  [system component-id x]
  (every? (partial ref-resolveable? system component-id) (refs x)))
