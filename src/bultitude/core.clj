(ns bultitude.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [dynapath.util :as dp])
  (:import (java.util.jar JarFile JarEntry)
           (java.util.zip ZipException)
           (java.io File BufferedReader PushbackReader InputStreamReader)
           (clojure.lang DynamicClassLoader)))

(defn- clj? [^File f]
  (and (not (.isDirectory f))
       (.endsWith (.getName f) ".clj")))

(defn- clj-jar-entry? [^JarEntry f]
  (and (not (.isDirectory f))
       (.endsWith (.getName f) ".clj")))

(defn- jar? [^File f]
  (and (.isFile f) (.endsWith (.getName f) ".jar")))

(defn- read-ns-form
  "Given a reader on a Clojure source file, read until an ns form is found."
  [rdr]
  (let [form (try (read rdr false ::done)
                  (catch Exception e ::done))]
    (if (try
          (and (list? form) (= 'ns (first form)))
          (catch Exception _))
      (try
        (str form) ;; force the read to read the whole form, throwing on error
        (second form)
        (catch Exception _))
      (when-not (= ::done form)
        (recur rdr)))))

(defn ns-form-for-file [file]
  (with-open [r (PushbackReader. (io/reader file))] (read-ns-form r)))

(defn namespaces-in-dir
  "Return a seq of all namespaces found in Clojure source files in dir."
  [dir]
  (for [^File f (file-seq (io/file dir))
        :when (and (clj? f) (.canRead f))
        :let [ns-form (ns-form-for-file f)]
        :when ns-form]
    ns-form))

(defn- ns-in-jar-entry [^JarFile jarfile ^JarEntry entry]
  (with-open [rdr (-> jarfile
                      (.getInputStream (.getEntry jarfile (.getName entry)))
                      InputStreamReader.
                      BufferedReader.
                      PushbackReader.)]
    (read-ns-form rdr)))

(defn- namespaces-in-jar [^File jar]
  (try
    (let [jarfile (JarFile. jar)]
      (for [entry (enumeration-seq (.entries jarfile))
            :when (clj-jar-entry? entry)
            :let [ns-form (ns-in-jar-entry jarfile entry)]
            :when ns-form]
        ns-form))
    (catch ZipException e
      (throw (Exception. (str "jar file corrupt: " jar) e)))))

(defn- split-classpath [^String classpath]
  (.split classpath (System/getProperty "path.separator")))

(defn loader-classpath
  "Returns a sequence of File objects from a classloader."
  [loader]
  (map io/as-file (dp/classpath-urls loader)))

(defn classpath-files
  "Returns a sequence of File objects of the elements on the classpath."
  ([classloader]
     (map io/as-file (dp/all-classpath-urls classloader)))
  ([] (classpath-files (clojure.lang.RT/baseLoader))))

(defn- classpath->collection [classpath]
  (if (coll? classpath)
    classpath
    (split-classpath classpath)))

(defn- classpath->files [classpath]
  (map io/file classpath))

(defn file->namespaces
  "Map a classpath file to the namespaces it contains. `prefix` allows for
   reducing the namespace search space. For large directories on the classpath,
   passing a `prefix` can provide significant efficiency gains."
  [^String prefix ^File f]
  (cond
    (.isDirectory f) (namespaces-in-dir
                      (if prefix
                        (io/file f (-> prefix
                                       (.replaceAll "\\." "/")
                                       (.replaceAll "-" "_")))
                        f))
    (jar? f) (let [ns-list (namespaces-in-jar f)]
               (if prefix
                 (filter #(and % (.startsWith (name %) prefix)) ns-list)
                 ns-list))))

(defn namespaces-on-classpath
  "Return symbols of all namespaces matching the given prefix both on disk and
  inside jar files. If :prefix is passed, only return namespaces that begin with
  this prefix. If :classpath is passed, it should be a seq of File objects or a
  classpath string. If it is not passed, default to java.class.path and the
  current classloader, assuming it is a dynamic classloader."
  [& {:keys [prefix classpath] :or {classpath (classpath-files)}}]
  (mapcat
   (partial file->namespaces prefix)
   (->> classpath
        classpath->collection
        classpath->files)))

(defn path-for
  "Transform a namespace into a .clj file path relative to classpath root."
  [namespace]
  (str (-> (str namespace)
           (.replace \- \_)
           (.replace \. \/))
       ".clj"))
