(ns hello-http.main
  (:gen-class)
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]))

; From http://pedestal.io/pedestal/0.6/guides/hello-world.html

(defn respond-hello [_request]
  {:status 200 :body "Hello, world!"})

(def routes
  (route/expand-routes
    #{["/greet" :get respond-hello :route-name :greet]}))

(defn create-server [port]
  (http/create-server
    {::http/routes routes
     ::http/type :jetty
     ::http/port port}))

(defn -main [port]
  (-> port parse-long create-server http/start))
