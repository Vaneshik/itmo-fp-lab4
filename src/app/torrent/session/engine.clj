(ns app.torrent.session.engine
  (:require [app.torrent.tracker.http :as tracker]
            [app.torrent.metainfo :as meta]
            [app.torrent.util.ids :as ids]

            [app.torrent.peer.pipeline :as pipe]
            [app.torrent.pieces.state :as st]
            [app.torrent.pieces.selection :as sel]
            [app.torrent.pieces.verify :as verify]
            [app.torrent.storage.files :as files])
  (:import [java.util.concurrent.atomic AtomicBoolean AtomicReference]))

(defn- sleep-ms! ^long [^long ms]
  (long (max 0 ms)))

;; ---------------- Tracker loop ----------------

(defn- step-tracker!
  [{:keys [config metainfo session-atom started? peer-id]}]
  (let [info-hash (meta/info-hash metainfo)
        downloaded (long (get-in @session-atom [:downloadedBytes] 0))
        total (long (get-in @session-atom [:totalBytes] 0))
        left (max 0 (- total downloaded))
        port (get-in config [:client :port] 51413)
        numwant (get-in config [:client :numwant] 50)
        timeout-ms (get-in config [:timeouts :tracker-ms] 7000)
        event (when (not (.get ^AtomicReference started?)) :started)
        urls (vec (meta/announce-urls metainfo))]

    (swap! session-atom assoc :tracker {:status "requesting" :tries (count urls)})

    (let [result
          (reduce
           (fn [acc announce-url]
             (if (seq (:peers acc))
               (reduced acc)
               (let [url (tracker/build-announce-url {:announce-url announce-url
                                                      :info-hash info-hash
                                                      :peer-id peer-id
                                                      :port port
                                                      :downloaded downloaded
                                                      :uploaded 0
                                                      :left left
                                                      :event event
                                                      :numwant numwant})]
                 (try
                   (let [{:keys [interval peers]} (tracker/announce!
                                                   {:announce-url announce-url
                                                    :info-hash info-hash
                                                    :peer-id peer-id
                                                    :port port
                                                    :downloaded downloaded
                                                    :uploaded 0
                                                    :left left
                                                    :event event
                                                    :numwant numwant
                                                    :timeout-ms timeout-ms})]
                     {:interval (long (or interval 60))
                      :peers (vec (or peers []))
                      :url url})
                   (catch Throwable _
                     acc)))))
           {:interval 60 :peers [] :url nil}
           urls)]

      (.set ^AtomicReference started? true)

      ;; Ретраим чаще, если peers мало
      (let [pcount (count (:peers result))
            sleep-sec (long (cond
                              (zero? pcount) 15
                              (< pcount 20) 30
                              :else (max 60 (long (:interval result)))))]
        (swap! session-atom assoc
               :peersList (:peers result)
               :peers {:connected 0 :active 0 :known pcount}
               :tracker {:status "ok"
                         :interval (long (:interval result))
                         :sleepSec sleep-sec
                         :lastUrl (:url result)
                         :lastAnnounceMs (System/currentTimeMillis)
                         :lastError nil})
        (* 1000 sleep-sec)))))


(defn start-tracker-loop!
  [{:keys [config metainfo session-atom]}]
  (let [running? (AtomicBoolean. true)
        started? (AtomicReference. false)
        peer-id (ids/peer-id-bytes (get-in config [:client :peer-id-prefix] "-CLJ001-"))
        stop-fn (fn [] (.set running? false))]
    (future
      (while (.get running?)
        (let [ms (try
                   (step-tracker! {:config config
                                   :metainfo metainfo
                                   :session-atom session-atom
                                   :started? started?
                                   :peer-id peer-id})
                   (catch Throwable t
                     (.set started? true)
                     (swap! session-atom assoc
                            :tracker {:status "error"
                                      :lastError (or (ex-message t) (str t))
                                      :lastErrorMs (System/currentTimeMillis)})
                     30000))]
          (Thread/sleep (sleep-ms! ms)))))
    stop-fn))

;; ---------------- Download loop ----------------

(defn- verify-piece-if-ready!
  [{:keys [metainfo session-atom pieces-state storage]} ^long piece-idx]
  (when (get-in @pieces-state [:pieces piece-idx :done?])
    (let [piece-bytes (long (get-in @pieces-state [:pieces piece-idx :piece-bytes]))
          piece-off (* piece-idx (long (:pieceLength @session-atom)))
          data (files/read-piece storage piece-off piece-bytes)
          expected (meta/piece-hash metainfo piece-idx)]
      (if (verify/piece-ok? data expected)
        (swap! session-atom assoc-in [:download :lastVerifiedPiece] piece-idx)
        (do
          (swap! pieces-state st/reset-piece piece-idx)
          (swap! session-atom assoc-in [:download :lastVerifyFailPiece] piece-idx))))))

(defn- step-download!
  [{:keys [config metainfo session-atom pieces-state peer-id storage]}]
  (let [sess @session-atom
        peers (:peersList sess)
        pcount (count peers)]
    (cond
      (not= (:status sess) :running)
      300

      (zero? pcount)
      500

      ;; если peers слишком мало — не долбимся без конца в 1–2 узла
      (< pcount 5)
      1000

      :else
      (let [req (sel/next-block @pieces-state)]
        (if (nil? req)
          (do (swap! session-atom assoc :status :finished) 1000)
          (let [peer (rand-nth peers)
                timeout-ms (get-in config [:timeouts :peer-handshake-ms] 7000)
                res (pipe/download-one-block!
                     {:peer peer
                      :metainfo metainfo
                      :pieces-state pieces-state
                      :peer-id peer-id
                      :timeout-ms timeout-ms})]
            (if-not (:ok res)
              (do
                (swap! session-atom assoc-in [:download :lastError] (:error res))
                500)
              (let [block ^bytes (:block res)
                    piece-idx (long (:piece-idx req))
                    begin (long (:begin req))
                    offset (+ (* piece-idx (long (:pieceLength sess))) begin)]
                (files/write-block! storage offset block)
                (swap! pieces-state st/mark-received piece-idx (:block-idx req))

                (let [ps @pieces-state
                      pieces-done (st/pieces-done-count ps)
                      prog (st/progress ps)]
                  (swap! session-atom assoc
                         :downloadedBytes (+ (long (:downloadedBytes @session-atom))
                                             (long (alength block)))
                         :piecesDone pieces-done
                         :progress prog))

                (verify-piece-if-ready! {:metainfo metainfo
                                         :session-atom session-atom
                                         :pieces-state pieces-state
                                         :storage storage}
                                        piece-idx)
                0))))))))

(defn start-download-loop!
  [{:keys [config metainfo session-atom pieces-state peer-id storage]}]
  (let [running? (AtomicBoolean. true)
        stop-fn (fn []
                  (.set running? false)
                  (files/close! storage))]
    (future
      (while (.get running?)
        (let [ms (try
                   (step-download! {:config config
                                    :metainfo metainfo
                                    :session-atom session-atom
                                    :pieces-state pieces-state
                                    :peer-id peer-id
                                    :storage storage})
                   (catch Throwable t
                     (swap! session-atom assoc-in [:download :loopError] (or (ex-message t) (str t)))
                     1000))]
          (when (pos? (long ms))
            (Thread/sleep (sleep-ms! ms))))))
    stop-fn))
