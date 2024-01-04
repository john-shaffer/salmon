(ns salmon.packer
  (:require [babashka.fs :as fs]
            [clojure.data.csv :as csv]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.process :as p]
            [clojure.string :as str]
            [donut.system :as-alias ds]))

(defn- packer-build!
  [{:keys [->error]}
   {:keys [dir init? template-file vars]}]
  (when init?
    (p/exec {:dir dir :err :stdout :out :inherit}
      "packer" "init" (str template-file)))
  (fs/with-temp-dir [tmp {:prefix "salmon-packer"}]
    (let [var-file (when (seq vars)
                     (with-open [w (io/writer (fs/file tmp "vars.json"))]
                       (json/write vars w))
                     (fs/file tmp "vars.json"))
          {:as p :keys [out process]}
          #__ (p/start {:dir dir :err :stdout}
                "packer"
                "build"
                "-machine-readable"
                (or (some->> var-file (str "-var-file=")) "")
                (str template-file))
          data (try
                 (with-open [rdr (io/reader out)]
                   (->> rdr csv/read-csv
                     (keep
                       (fn [[_ _ type idx k v]]
                         (cond
                           (= "error" idx)
                           (do (->error (str "packer build: " k))
                             nil)

                           (and (= "artifact" type)
                             (= "id" k))
                           (let [[region ami] (str/split v #"\:")]
                             {:ami ami
                              :artifact idx
                              :region region}))))
                     doall))
                 (catch Exception e
                   (.destroy process)
                   (throw e)))]
      (if (zero? @p)
        data
        (throw (ex-info (str "packer build exited with code " @p)
                 {:process p}))))))

(defn- init-config [{:as config :keys [vars]}]
  (assoc config
    :init? (boolean (get config :init? true))
    :vars (or vars {})))

(defn- start!
  [{:as system ::ds/keys [config instance]}]
  (or instance
    (let [config (init-config config)
          {:keys [ami region]} (first (packer-build! system config))]
      {:ami ami :region region})))

(defn- stop! [_]
  nil)

(defn ami
  "Returns a component that builds an AMI using packer. Requires
   a packer binary on the $PATH.
   
   Config options:

   :dir (Required)
   The directory to run packer from.

   :template-file (Required)
   The packer template file. Relative to :dir.

   :init? (Default: true)
   Run `packer init` before building. This ensures that any plugins
   the template needs get installed.

   :vars (Default: {})
   A map of vars passed to packer build. The vars must be defined
   in the template file.

   Instance keys:

   :ami (string)
   The AMI that was built. Example: \"ami-01fbf1afdc14f6c96\".

   :region (string)
   The AWS region of the AMI. Example: \"us-east-2\"."
  [& {:as config}]
  {::ds/config config
   ::ds/start start!
   ::ds/stop stop!})
