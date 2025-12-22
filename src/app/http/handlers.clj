(ns app.http.handlers
  (:require [app.torrent.session.registry :as reg]
            [app.torrent.session.model :as model]
            [app.torrent.metainfo :as meta])
  (:import [java.util HexFormat]))


(defn- bytes->hex [^bytes bs]
  (.formatHex (HexFormat/of) bs))

(defn health [_req]
  {:status 200
   :body {:status "ok"}})

;; универсально достаём :id из reitit (в разных версиях оно может лежать по-разному)
(defn- path-id [req]
  (or (get-in req [:path-params :id])
      (get-in req [:parameters :path :id])))

(defn create-torrent [req]
  (let [registry (get-in req [:system :torrent/registry])
        config (get-in req [:system :config])
        {:keys [torrentPath outDir]} (:json-body req)

        metainfo (meta/parse-torrent-file torrentPath)
        name (meta/torrent-name metainfo)
        total (meta/total-bytes metainfo)
        pl (meta/piece-length metainfo)
        pc (meta/pieces-count metainfo)
        ih (bytes->hex (meta/info-hash metainfo))

        sess (reg/create! registry {:name name
                                    :total-bytes total
                                    :out-dir outDir
                                    :piece-length pl
                                    :pieces-total pc
                                    :info-hash-hex ih
                                    :config config
                                    :metainfo metainfo})]
    {:status 201
     :body (model/snapshot sess)}))



(defn list-torrents [req]
  (let [registry (get-in req [:system :torrent/registry])]
    {:status 200
     :body (mapv model/snapshot (reg/list registry))}))

(defn get-torrent [req]
  (let [registry (get-in req [:system :torrent/registry])
        id (path-id req)]
    (if-let [sess (reg/get registry id)]
      {:status 200 :body (model/snapshot sess)}
      {:status 404 :body {:error "not_found"}})))

(defn pause [req]
  (let [registry (get-in req [:system :torrent/registry])
        id (path-id req)]
    (if-let [sess (reg/pause! registry id)]
      {:status 200 :body (model/snapshot sess)}
      {:status 404 :body {:error "not_found"}})))

(defn resume [req]
  (let [registry (get-in req [:system :torrent/registry])
        id (path-id req)]
    (if-let [sess (reg/resume! registry id)]
      {:status 200 :body (model/snapshot sess)}
      {:status 404 :body {:error "not_found"}})))

(defn stop [req]
  (let [registry (get-in req [:system :torrent/registry])
        id (path-id req)]
    (if-let [sess (reg/stop! registry id)]
      {:status 200 :body (model/snapshot sess)}
      {:status 404 :body {:error "not_found"}})))

(defn delete-torrent [req]
  (let [registry (get-in req [:system :torrent/registry])
        id (path-id req)]
    (reg/delete! registry id)
    {:status 200 :body {:ok true}}))

(defn peers [req]
  (let [registry (get-in req [:system :torrent/registry])
        id (path-id req)]
    (if-let [ps (reg/peers registry id)]
      {:status 200 :body ps}
      {:status 404 :body {:error "not_found"}})))


(defn download-one-block [req]
  (let [registry (get-in req [:system :torrent/registry])
        id (path-id req)]
    (if-let [sess (reg/get registry id)]
      (let [metainfo (get-in req [:system :torrent/metainfo id]) ;; если ещё не хранишь — скажи, добавим
            _ (when-not metainfo (throw (ex-info "no metainfo stored" {:id id})))
            peer (first (:peersList sess))]
        {:status 501 :body {:error "not implemented yet"}})
      {:status 404 :body {:error "not_found"}})))
