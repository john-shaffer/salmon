(ns salmon.uberjar
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.tools.build.api :as b]
            [clojure.tools.deps :as t]
            [clojure.tools.deps.util.dir :as dir]
            [clojure.tools.logging.readable :as logr]
            [donut.system :as-alias ds]))

(defn- build-uberjar!
  [{:as opts :keys [aliases class-dir clean? deps-file project-dir]}]
  (dir/with-dir (fs/file project-dir)
    (let [; Read EDN ourselves to allow filenames like "deps.edn"
          ; rather than "./deps.edn" and surface errors more directly
          project-deps (-> (fs/file project-dir deps-file) slurp edn/read-string)
          basis (t/create-basis {:aliases aliases :project project-deps})
          opts (merge
                 {:basis basis
                  :main (-> basis :argmap :main)
                  :ns-compile (or (-> basis :argmap :ns-compile)
                                [(-> basis :argmap :main)])}
                 opts)]
      (when-not (:main opts)
        (throw (ex-info (str "No :main found in " (fs/path project-dir deps-file) " for aliases " (pr-str aliases))
                 {:aliases aliases :basis basis :deps-file deps-file})))
      (b/with-project-root (str project-dir)
        (when clean?
          (logr/info (str "Cleaning class dir " class-dir))
          (b/delete {:path class-dir}))
        (logr/info (str "Compiling " project-dir))
        (b/compile-clj opts)
        (logr/info (str "Building uberjar for " project-dir))
        (b/uber opts)))))

(defn- init-config [{:as config :keys [class-dir deps-file uber-file]}]
  (let [tmp-dir (when-not (or class-dir uber-file)
                  (fs/create-temp-dir {:prefix "salmon-uberjar"}))]
    (assoc config
      :class-dir (or class-dir (str (fs/path tmp-dir "classes")))
      :clean? (boolean (get config :clean? true))
      :deps-file (or deps-file "deps.edn")
      :tmp-dir tmp-dir
      :uber-file (or uber-file (str (fs/path tmp-dir "uberjar.jar"))))))

(defn- start!
  [{::ds/keys [config instance]}]
  (or instance
    (let [{:as config :keys [project-dir uber-file]} (init-config config)]
      (build-uberjar! config)
      {:uber-file (str (if (fs/absolute? uber-file)
                         uber-file
                         (fs/absolutize (fs/path project-dir uber-file))))})))

(defn- stop! [{::ds/keys [instance]}]
  (some-> instance :config :tmp-dir (fs/delete-tree {:force true}))
  nil)

(defn uberjar
  "Returns a component that builds an uberjar for a Clojure project.
   
   Config options:

   :aliases (Required)
   Seq of deps.edn aliases to use. Example: `[:uberjar :prod]`.
   One of the aliases must define a :main namespace that contains
   `(:gen-class)` and a `-main` fn.

   :project-dir (Required)
   The project directory for which to build the uberjar.

   :class-dir (Default: A temporary directory.)
   The directory to store the compiled code and source code that will
   go into the uberjar. Relative to :project-dir.

   :clean? (Default: true)
   Whether to delete :class-dir before building.

   :deps-file (Default: \"deps.edn\")
   The project's deps.edn file. Relative to :project-dir.

   :uber-file (Default: A file in a temporary directory.)
   Path to write the uberjar file. Relative to :project-dir.

   Instance keys:

   :uber-file
   Absolute path to the uberjar file."
  [& {:as config}]
  {::ds/config config
   ::ds/start start!
   ::ds/stop stop!})
