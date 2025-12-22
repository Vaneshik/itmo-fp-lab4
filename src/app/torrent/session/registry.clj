(ns app.torrent.session.registry
  (:refer-clojure :exclude [get list])
  (:require [app.torrent.session.model :as model]
            [app.torrent.session.engine :as engine]
            [app.torrent.pieces.state :as st]
            [app.torrent.storage.files :as files]
            [app.torrent.util.ids :as ids])
  (:import [java.util UUID]))

(defn new-registry []
  (atom {}))

(defn- uuid []
  (str (UUID/randomUUID)))

(defn create!
  [reg {:keys [config metainfo] :as req-map}]
  (let [id (uuid)
        cfg (or config {})
        sess (model/new-session (assoc req-map :id id))
        session-atom (atom sess)

        ;; init pieces state
        pieces-state (atom (st/init-state {:total-bytes (:totalBytes sess)
                                           :piece-length (:pieceLength sess)
                                           :pieces-total (:piecesTotal sess)}
                                          (get-in cfg [:client :block-size] 16384)))

        ;; peer-id once per session
        peer-id (ids/peer-id-bytes (get-in cfg [:client :peer-id-prefix] "-CLJ001-"))

        ;; open storage (single file)
        storage (files/open-storage! {:outDir (:outDir sess)
                                      :name (:name sess)
                                      :totalBytes (:totalBytes sess)})]

    ;; make tracker visible immediately
    (swap! session-atom assoc :tracker {:status "starting"})

    (let [stop-tracker (engine/start-tracker-loop! {:config cfg
                                                    :metainfo metainfo
                                                    :session-atom session-atom})
          stop-download (engine/start-download-loop! {:config cfg
                                                      :metainfo metainfo
                                                      :session-atom session-atom
                                                      :pieces-state pieces-state
                                                      :peer-id peer-id
                                                      :storage storage})
          stop-fn (fn []
                    (stop-tracker)
                    (stop-download))
          handle {:state session-atom
                  :stop stop-fn
                  :metainfo metainfo
                  :pieces-state pieces-state
                  :peer-id peer-id
                  :storage storage}]
      (swap! reg assoc id handle)
      @session-atom)))

(defn list [reg]
  (mapv (fn [[_ h]]
          @(clojure.core/get h :state))
        @reg))

(defn get [reg id]
  (when-let [h (clojure.core/get @reg id)]
    @(clojure.core/get h :state)))

(defn peers [reg id]
  (when-let [h (clojure.core/get @reg id)]
    (clojure.core/get @(clojure.core/get h :state) :peersList [])))

(defn delete! [reg id]
  (when-let [h (clojure.core/get @reg id)]
    ((clojure.core/get h :stop))
    (swap! reg dissoc id))
  {:ok true})

(defn- set-status! [reg id status]
  (when-let [h (clojure.core/get @reg id)]
    (swap! (clojure.core/get h :state) assoc :status status)
    @(clojure.core/get h :state)))

(defn pause! [reg id]  (set-status! reg id :paused))
(defn resume! [reg id] (set-status! reg id :running))
(defn stop! [reg id]   (set-status! reg id :stopped))
