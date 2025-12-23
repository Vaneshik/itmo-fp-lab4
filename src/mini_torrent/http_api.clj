(ns mini-torrent.http-api
  (:require
   [clojure.java.io :as io]
   [mini-torrent.session :as session]
   [muuntaja.core :as m]
   [reitit.ring :as ring]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.util.response :as resp])
  (:import [java.io File]))

;; ---------------------------
;; helpers: responses
;; ---------------------------

(defn- json [status body]
  (-> (resp/response body)
      (resp/status status)))

(defn- ok [body] (json 200 body))
(defn- created [body] (json 201 body))
(defn- bad [msg & [data]] (json 400 {:error {:message msg :data data}}))
(defn- not-found [msg & [data]] (json 404 {:error {:message msg :data data}}))
(defn- server-error [msg & [data]] (json 500 {:error {:message msg :data data}}))

(defn- req-body
  "Достаём body-параметры независимо от того, каким middleware они проставлены."
  [req]
  (or (:body-params req)
      (get-in req [:parameters :body])
      {}))

;; ---------------------------
;; CORS (простая реализация для dev)
;; ---------------------------

(defn wrap-cors
  "Минимальный CORS:
   - разрешаем все origins (для dev)
   - поддерживаем OPTIONS preflight"
  [handler]
  (fn [req]
    (let [origin (get-in req [:headers "origin"] "*")
          cors-headers {"Access-Control-Allow-Origin" origin
                        "Access-Control-Allow-Headers" "Content-Type, Authorization"
                        "Access-Control-Allow-Methods" "GET,POST,DELETE,OPTIONS"
                        "Access-Control-Allow-Credentials" "true"}]
      (if (= :options (:request-method req))
        {:status 204 :headers cors-headers :body ""}
        (let [resp (handler req)]
          (update resp :headers merge cors-headers))))))

;; ---------------------------
;; multipart (.torrent upload)
;; ---------------------------

(defn- multipart-torrent-file
  "Ожидаем field name: file.
   Возвращает java.io.File (tempfile) или nil."
  [req]
  (let [f (or (get-in req [:multipart-params "file"])
              (get-in req [:params "file"]))]
    (when (map? f)
      (:tempfile f))))

;; ---------------------------
;; handlers
;; ---------------------------

(defn health-handler [_]
  (ok {:status "ok"}))

(defn list-torrents-handler [_]
  (ok (mapv session/session->summary (session/list-sessions))))

(defn create-torrent-handler [req]
  (try
    (let [body (req-body req)
          mp-file (multipart-torrent-file req)
          out-dir (or (:outDir body) (:out-dir body) "downloads")
          ;; Вариант A: multipart upload
          ;; Вариант B: JSON {torrentPath,outDir}
          torrent-path (cond
                         (instance? File mp-file) (.getAbsolutePath ^File mp-file)
                         (string? (:torrentPath body)) (:torrentPath body)
                         (string? (:torrent-path body)) (:torrent-path body)
                         :else nil)]
      (if-not torrent-path
        (bad "Expected multipart field `file` or JSON {torrentPath, outDir}" {:got body})
        (let [s (session/create-session! {:torrent-path torrent-path
                                          :out-dir out-dir})
              dto (session/session->summary s)]
          (created {:id (:id dto)
                    :name (:name dto)
                    :status (:status dto)}))))
    (catch clojure.lang.ExceptionInfo e
      (bad "Failed to create session" (merge {:message (.getMessage e)} (ex-data e))))
    (catch Exception e
      (server-error "Unexpected error while creating session"
                    {:message (.getMessage e)
                     :class (.getName (class e))}))))

(defn details-handler [req]
  (let [id (get-in req [:path-params :id])
        s (session/get-session id)]
    (if-not s
      (not-found "Session not found" {:id id})
      (ok (session/session->details s)))))

(defn peers-handler [req]
  (let [id (get-in req [:path-params :id])
        s (session/get-session id)]
    (if-not s
      (not-found "Session not found" {:id id})
      (ok (session/session->peers s)))))

(defn pause-handler [req]
  (let [id (get-in req [:path-params :id])]
    (if-not (session/pause! id)
      (not-found "Session not found" {:id id})
      (ok (session/session->details (session/get-session id))))))

(defn resume-handler [req]
  (let [id (get-in req [:path-params :id])]
    (if-not (session/resume! id)
      (not-found "Session not found" {:id id})
      (ok (session/session->details (session/get-session id))))))

(defn stop-handler [req]
  (let [id (get-in req [:path-params :id])]
    (if-not (session/stop! id)
      (not-found "Session not found" {:id id})
      (ok (session/session->details (session/get-session id))))))

(defn delete-handler [req]
  (let [id (get-in req [:path-params :id])]
    (if-not (session/delete! id)
      (not-found "Session not found" {:id id})
      (ok {:ok true :id id}))))

;; ---------------------------
;; router + app
;; ---------------------------

(defn routes []
  ["/api"
   ["/health" {:get health-handler}]
   ["/torrents"
    {:get list-torrents-handler
     :post create-torrent-handler}]
   ["/torrents/:id"
    {:get details-handler
     :delete delete-handler}]
   ["/torrents/:id/peers" {:get peers-handler}]
   ["/torrents/:id/pause" {:post pause-handler}]
   ["/torrents/:id/resume" {:post resume-handler}]
   ["/torrents/:id/stop" {:post stop-handler}]])

(defn app
  "Готовое Ring приложение.
   В dev можно включить CORS (wrap-cors).
   Multipart включён, чтобы принимать .torrent upload."
  [{:keys [cors?] :or {cors? true}}]
  (let [router
        (ring/router
         [(routes)]
         {:data {:muuntaja (m/create (assoc m/default-options
                                            ;; чтобы keyword-ключи в json нормально парсились
                                            :decode-key-fn keyword))
                 :middleware [parameters/parameters-middleware
                              muuntaja/format-middleware]}})

        handler (ring/ring-handler router (ring/create-default-handler))]
    (cond-> handler
      true wrap-params
      true wrap-multipart-params
      cors? wrap-cors)))
