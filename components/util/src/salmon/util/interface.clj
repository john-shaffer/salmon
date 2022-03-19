(ns salmon.util.interface
  (:require
   [salmon.util.core :as core])
  (:import
   (java.io File)))

(defn create-temp-file!
  "Returns a temporary java.io.File.

  `prefix`: A String of at least 3 characters.
  `suffix`: A String or nil."
  ^File [^String prefix ^String suffix]
  (core/create-temp-file! prefix suffix))

(defmacro with-temp-file!
  "Evaluates body in a try expression with `binding` bound to a
  temporary java.io.File, and a finally clause that deletes the file.

  `opts`:
    `:prefix` A String of at least 3 characters.
    `:suffix` A String or nil."
  [[binding opts] & body]
  `(core/with-temp-file! [~binding ~opts]
     ~@body))
