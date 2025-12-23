(ns app.http.server
  (:require [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.util.response :refer [response resource-response content-type]]
            [compojure.core :refer [defroutes GET POST DELETE]]
            [compojure.route :as route]))

(def torrents-db
  (atom
   [{:id "1"
     :name "Ubuntu 22.04 LTS Desktop"
     :info-hash "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0"
     :total-size 3774873600
     :downloaded 2831155200
     :uploaded 1887436800
     :progress 0.75
     :status "downloading"
     :down-speed 2621440
     :up-speed 1048576
     :peers-connected 12
     :peers-total 45
     :pieces-total 1800
     :pieces-done 1350
     :eta 360
     :added-at "2024-01-15T10:30:00Z"}
    
    {:id "2"
     :name "Debian 12.0 DVD ISO"
     :info-hash "b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1"
     :total-size 4102029312
     :downloaded 4102029312
     :uploaded 8204058624
     :progress 1.0
     :status "completed"
     :down-speed 0
     :up-speed 524288
     :peers-connected 8
     :peers-total 23
     :pieces-total 1956
     :pieces-done 1956
     :eta 0
     :added-at "2024-01-14T08:15:00Z"}
    
    {:id "3"
     :name "Arch Linux x86_64 ISO"
     :info-hash "c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2"
     :total-size 838860800
     :downloaded 419430400
     :uploaded 0
     :progress 0.50
     :status "paused"
     :down-speed 0
     :up-speed 0
     :peers-connected 0
     :peers-total 34
     :pieces-total 400
     :pieces-done 200
     :eta 0
     :added-at "2024-01-16T14:45:00Z"}
    
    {:id "4"
     :name "Big Buck Bunny (Open Movie Project)"
     :info-hash "d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3"
     :total-size 725614592
     :downloaded 72561459
     :uploaded 36280729
     :progress 0.10
     :status "downloading"
     :down-speed 3145728
     :up-speed 1572864
     :peers-connected 28
     :peers-total 156
     :pieces-total 347
     :pieces-done 35
     :eta 210
     :added-at "2024-01-17T09:20:00Z"}
    
    {:id "5"
     :name "Fedora Workstation 39 Live x86_64"
     :info-hash "e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3x4"
     :total-size 2097152000
     :downloaded 2097152000
     :uploaded 4194304000
     :progress 1.0
     :status "seeding"
     :down-speed 0
     :up-speed 2097152
     :peers-connected 15
     :peers-total 67
     :pieces-total 1000
     :pieces-done 1000
     :eta 0
     :added-at "2024-01-13T16:30:00Z"}]))

(defn update-progress! []
  (swap! torrents-db
         (fn [torrents]
           (mapv (fn [t]
                   (if (= (:status t) "downloading")
                     (let [new-downloaded (min (:total-size t)
                                               (+ (:downloaded t) (* (:down-speed t) 2)))
                           new-progress (/ (double new-downloaded) (:total-size t))]
                       (assoc t 
                              :downloaded new-downloaded
                              :progress new-progress
                              :pieces-done (int (* (:pieces-total t) new-progress))))
                     t))
                 torrents))))

(defonce progress-updater
  (future
    (while true
      (Thread/sleep 2000)
      (update-progress!))))

(defn get-torrents-handler [_]
  (response @torrents-db))

(defn health-handler [_]
  (response {:status "ok" :message "ClojureTorrent API is running!"}))

(defn pause-torrent-handler [request]
  (let [id (get-in request [:params :id])]
    (println "Pausing torrent:" id)
    (swap! torrents-db
           (fn [torrents]
             (mapv (fn [t]
                     (if (= (:id t) id)
                       (assoc t :status "paused" :down-speed 0 :up-speed 0)
                       t))
                   torrents)))
    (println "Torrent paused successfully")
    (response {:success true :message (str "Torrent " id " paused")})))

(defn resume-torrent-handler [request]
  (let [id (get-in request [:params :id])]
    (swap! torrents-db
           (fn [torrents]
             (mapv (fn [t]
                     (if (= (:id t) id)
                       (assoc t :status "downloading" 
                              :down-speed 2621440
                              :up-speed 1048576)
                       t))
                   torrents)))
    (response {:success true :message (str "Torrent " id " resumed")})))

(defn remove-torrent-handler [request]
  (let [id (get-in request [:params :id])]
    (swap! torrents-db
           (fn [torrents]
             (filterv #(not= (:id %) id) torrents)))
    (response {:success true :message (str "Torrent " id " removed")})))

(defn wrap-cors [handler]
  (fn [request]
    (if (= (:request-method request) :options)
      {:status 200
       :headers {"Access-Control-Allow-Origin" "*"
                 "Access-Control-Allow-Methods" "GET, POST, PUT, DELETE, OPTIONS"
                 "Access-Control-Allow-Headers" "Content-Type, Authorization"}}
      (let [response (handler request)]
        (-> response
            (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
            (assoc-in [:headers "Access-Control-Allow-Methods"] "GET, POST, PUT, DELETE, OPTIONS")
            (assoc-in [:headers "Access-Control-Allow-Headers"] "Content-Type, Authorization"))))))

(defroutes app-routes
  (GET "/api/health" [] health-handler)
  (GET "/api/torrents" [] get-torrents-handler)
  (POST "/api/torrents/:id/pause" [] pause-torrent-handler)
  (POST "/api/torrents/:id/resume" [] resume-torrent-handler)
  (DELETE "/api/torrents/:id" [] remove-torrent-handler)
  (GET "/" [] (content-type (resource-response "index.html" {:root "public"}) "text/html"))
  (route/resources "/")
  (route/not-found {:status 404 :body {:error "Not found"}}))

(def app
  (-> app-routes
      wrap-keyword-params
      wrap-params
      wrap-json-body
      wrap-json-response
      wrap-cors
      (wrap-resource "public")
      wrap-content-type
      wrap-not-modified))

(defonce server (atom nil))

(defn start-server! [port]
  (when-not @server
    (println (str "Starting server on port " port "..."))
    (reset! server (jetty/run-jetty #'app {:port port :join? false}))
    (println (str "Server started at http://localhost:" port))))

(defn stop-server! []
  (when @server
    (println "Stopping server...")
    (.stop @server)
    (reset! server nil)
    (println "Server stopped.")))

(defn restart-server! [port]
  (stop-server!)
  (start-server! port))

