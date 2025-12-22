(ns app.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn- read-edn-file [f]
  (with-open [r (java.io.PushbackReader. (io/reader f))]
    (edn/read {:eof nil} r)))

(defn load-config
  "env: :dev | :prod | :test"
  [env]
  (let [path (str "config/" (name env) ".edn")
        f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info "Config file not found" {:path path})))
    (read-edn-file f)))

(defn cfg [config ks]
  (get-in config ks))
