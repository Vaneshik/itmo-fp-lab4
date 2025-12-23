(ns app.main
  (:require [app.http.server :as server])
  (:gen-class))

(defn -main [& args]
  (let [mode (first args)
        port 3002]
    (case mode
      "dev" (do
              (println "Starting in DEV mode...")
              (server/start-server! port)
              (println (str "API available at http://localhost:" port "/api/torrents"))
              (println "Press Ctrl+C to stop"))
      "run" (do
              (println "Starting in PRODUCTION mode...")
              (server/start-server! port)
              (println (str "API available at http://localhost:" port "/api/torrents")))
      (println "Usage: clj -M:dev or clj -M:run"))))

