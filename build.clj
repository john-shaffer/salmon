(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [org.corfield.build :as bb]))

(def lib 'com.github.john-shaffer/salmon)
(def version (format "0.1.%s" (b/git-count-revs nil)))

(defn test [opts]
  (-> opts
      (assoc :main-args ["-m" "kaocha.runner"])
      (bb/run-task [:test]))
  opts)
