(ns salmon.ecr
  (:require
   [clojure.string :as str]
   [cognitect.aws.client.api :as aws]
   [donut.system :as ds]
   [salmon.docker :as docker]
   [salmon.util :as u]
   [sys-ext.core :as se]))

(defn get-auth-token [ecr-client]
  (let [auth-data (:authorizationData
                    (u/invoke! ecr-client
                      {:op :GetAuthorizationToken
                       :request {}}))
        {:as m :keys [authorizationToken]}
        #__ (if (map? auth-data)
              auth-data
              (first auth-data))
        [user pass] (-> authorizationToken
                      u/b64-decode
                      String.
                      (str/split #":" 2))]
    (assoc m :password pass :username user)))

(defn push-group
  [{:keys [aws-client-opts image-ref repo-uri]}]
  {:ecr-auth-token
   (se/call get-auth-token
     (ds/local-ref [:ecr-client :client]))
   :ecr-client
   (se/call
     (fn [opts]
       {:client (aws/client (assoc opts :api :ecr))})
     aws-client-opts)
   :login
   (se/call docker/login!
     repo-uri
     (ds/local-ref [:ecr-auth-token :username])
     (ds/local-ref [:ecr-auth-token :password]))
   :push
   (se/call docker/push!
     (ds/local-ref [:tag :image-ref]))
   :repo-image-ref
   (se/call
     (fn [local-image-ref repo-uri]
       {:image-ref
        (-> local-image-ref
          (str/split #":" 2)
          second
          (->> (str repo-uri ":")))})
     image-ref
     (ds/local-ref [:login :uri]))
   :tag
   (se/call docker/tag!
     image-ref
     (ds/local-ref [:repo-image-ref :image-ref]))})
