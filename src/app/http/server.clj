(ns app.http.server
  (:require [ring.adapter.jetty :as jetty]
            [app.http.routes :as routes]
            [app.http.json :as json]))

(defonce server* (atom nil))

(defn start! [system]
  (let [handler (-> (routes/app-routes system)
                    (json/wrap-json-body)
                    (json/wrap-json-response))
        port (get-in system [:config :http :port] 8080)]
    (reset! server* (jetty/run-jetty handler {:port port :join? false}))
    system))

(defn stop! []
  (when-let [srv @server*]
    (.stop srv)
    (reset! server* nil)))
