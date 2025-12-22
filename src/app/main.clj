(ns app.main
  (:require [app.config :as config]
            [app.logging :as logging]
            [app.http.server :as http]
            [app.torrent.session.registry :as registry]))

(defonce system* (atom nil))

(defn start! [{:keys [env] :or {env :dev}}]
  (let [cfg (config/load-config env)
        reg (registry/new-registry)
        sys {:env env
             :config cfg
             :torrent/registry reg}]
    (logging/init-logging! cfg)
    (reset! system* (http/start! sys))
    @system*))

(defn stop! []
  (when-let [sys @system*]
    (reset! system* (http/stop! sys)))
  :stopped)

(defn -main [& args]
  (let [env (keyword (or (first args) "dev"))]
    (start! {:env env})))
