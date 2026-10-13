(ns salmon.fake-http
  "A fake cognitect.aws.http/HttpClient for testing how AWS clients
   handle responses without making network requests."
  (:require
   [clojure.core.async :as a]
   [cognitect.aws.credentials :as creds]
   [cognitect.aws.http :as http])
  (:import
   (java.io IOException)
   (java.nio ByteBuffer)
   (java.nio.charset StandardCharsets)
   (java.util.concurrent CompletionException)))

(defn io-fault
  "Returns the anomaly that the java.net.http client produces when
   it sends a request on a connection that the server already closed."
  []
  {:cognitect.anomalies/category :cognitect.anomalies/fault
   :cognitect.anomalies/message "java.io.IOException: HTTP/1.1 header parser received no bytes"
   :cognitect.aws/throwable
   (CompletionException.
     (IOException. "HTTP/1.1 header parser received no bytes"))})

(defn xml-response
  ([body]
   (xml-response 200 body))
  ([status ^String body]
   {:body (ByteBuffer/wrap (.getBytes body StandardCharsets/UTF_8))
    :headers {"content-type" "text/xml"}
    :status status}))

(defn stack-does-not-exist-response [stack-name]
  (xml-response 400
    (str "<ErrorResponse><Error><Type>Sender</Type><Code>ValidationError</Code>"
      "<Message>Stack with id " stack-name " does not exist</Message>"
      "</Error><RequestId>1</RequestId></ErrorResponse>")))

(defn http-client
  "Returns [client requests]. client returns each of responses in
   order, and then repeats the last one. requests is an atom holding
   the number of requests made."
  [responses]
  (let [requests (atom 0)]
    [(reify http/HttpClient
       (-submit [_ _request channel]
         (let [n (swap! requests inc)]
           (a/put! channel (nth responses (min (dec n) (dec (count responses)))))
           channel))
       (-stop [_]))
     requests]))

(def client-opts
  {:credentials-provider (creds/basic-credentials-provider
                           {:access-key-id "AKIDEXAMPLE"
                            :secret-access-key "secret"})
   :region "us-east-1"})
