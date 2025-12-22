(ns app.logging
  (:require [clojure.tools.logging :as log]))

(defn init-logging! [_config]
  (log/info "Logging initialized"))
