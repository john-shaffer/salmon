(ns salmon.test
  (:require [donut.system :as-alias ds]
            [malli.generator :as mg]
            [salmon.cloudformation :as cfn]
            [salmon.signal :as sig]))

(def default-config
  {:regions [:us-east-1 :us-east-2 :us-west-2]
   :test-domain
   {:apex "shafferstest.net"
    :hosted-zone-id "Z08609191OFSO5HMA450N"}})

(defn get-config []
  default-config)

(def ^{:doc "A regular expression for matching DNS labels"}
  re-dns-label
  #"^[a-zA-Z0-9](?:[a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?$")

(defn rand-dns-label []
  (mg/generate [:re re-dns-label]))

(defn rand-stack-name []
  (mg/generate [:re cfn/re-stack-name]))

(def system-base
  {::ds/base {:salmon/early-validate sig/early-validate-conf}
   ::ds/signals
   {:salmon/delete {:order :topsort :returns-instance? true}
    :salmon/early-validate {:order :reverse-topsort}
    ::ds/validate {:order :reverse-topsort}}})

(defmacro with-system [[name-sym system-def] & body]
  `(let [sys# (atom (sig/start! ~system-def))
         ~name-sym sys#]
     (try
       ~@body
       (finally
         (reset! sys# (sig/stop! @sys#))))))

(defmacro with-system-delete [[name-sym system-def] & body]
  `(let [sys# (atom (sig/start! ~system-def))
         ~name-sym sys#]
     (try
       ~@body
       (finally
         (reset! sys# (sig/stop! @sys#))
         (reset! sys# (sig/delete! @sys#))))))
