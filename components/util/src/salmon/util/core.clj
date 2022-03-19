(ns salmon.util.core
  (:import
   (java.io File)))

(defn create-temp-file! [prefix suffix]
  (File/createTempFile prefix (str suffix)))

(defmacro with-temp-file! [[binding {:keys [prefix suffix]}] & body]
  `(let [file# (create-temp-file! ~prefix ~suffix)
         ~binding file#]
     (try
       ~@body
       (finally
         (.delete file#)))))
