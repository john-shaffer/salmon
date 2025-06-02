(ns salmon.docker
  (:require
   [clojure.java.process :as p]
   [clojure.string :as str]))

(defn load-from-flake! [dir output-name & {:keys [system]}]
  (let [args (->> ["nix"
                   "build"
                   output-name
                   "--print-out-paths"
                   (when system "--system")
                   system]
               (remove nil?))
        image-file (str/trim
                     (apply p/exec
                       {:dir dir
                        :err :inherit}
                       args))
        p (p/start {:dir dir
                    :err :inherit}
            "docker"
            "load"
            "-i"
            image-file)
        exit @(p/exit-ref p)]
    (if (zero? exit)
      {:image-ref (-> p p/stdout slurp (str/split #":\s*" 2) second str/trim)}
      (throw (ex-info (str "docker load failed with exit=" exit)
               {:exit exit})))))

(comment
  (load-from-flake! "projects/ssh-portal" ".#ssh-portal-image")
  (load-from-flake! "projects/ssh-portal" ".#ssh-portal-image"
    {:system "aarch64-linux"}))

(defn login! [uri username password]
  (let [p (p/start
            {:err :inherit
             :out :inherit}
            "docker"
            "login"
            "--username" username
            "--password-stdin"
            uri)
        _ (-> p p/stdin (spit password))
        exit @(p/exit-ref p)]
    (if (zero? exit)
      {:uri uri}
      (throw (ex-info (str "docker login failed with exit=" exit)
               {:exit exit})))))

(comment
  (login!
    "221414915535.dkr.ecr.us-west-2.amazonaws.com/sshportalrepo-oezlhpug1pod"
    "AWS"
    (p/exec "aws" "ecr" "get-login-password" "--region" "us-west-2")))

(defn tag! [local-image-ref remote-image-ref]
  (let [image-ref (if (str/includes? remote-image-ref ":")
                    remote-image-ref
                    (str remote-image-ref ":latest"))]
    (p/exec
      {:err :inherit
       :out :inherit}
      "docker"
      "tag"
      local-image-ref
      image-ref)
    {:image-ref image-ref}))

(defn push! [image-ref]
  (p/exec
    {:err :inherit
     :out :inherit}
    "docker"
    "push"
    image-ref)
  {:image-ref image-ref})
