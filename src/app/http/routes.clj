(ns app.http.routes
  (:require [reitit.ring :as ring]
            [app.http.handlers :as h]))

(defn- wrap-system [handler system]
  (fn [req]
    (handler (assoc req :system system))))

(defn app-routes [system]
  (let [router
        (ring/router
         [["/api/health" {:get h/health}]
          ["/api/torrents"
           {:get h/list-torrents
            :post h/create-torrent}]
          ["/api/torrents/:id"
           {:get h/get-torrent
            :delete h/delete-torrent}]
          ["/api/torrents/:id/peers" {:get h/peers}]
          ["/api/torrents/:id/pause" {:post h/pause}]
          ["/api/torrents/:id/resume" {:post h/resume}]
          ["/api/torrents/:id/stop" {:post h/stop}]])
        handler (ring/ring-handler router (ring/create-default-handler))]
    (wrap-system handler system)))
