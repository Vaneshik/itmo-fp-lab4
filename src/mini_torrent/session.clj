(ns mini-torrent.session
  "Session layer: оборачивает текущее ядро (mini-torrent.core) в управляемые 'сессии'
   для использования из HTTP API (фронт).

   Ключевая идея:
   - ядро качает торрент как раньше (peer-manager!/worker!)
   - мы держим registry сессий + control atom (:running/:paused/:stopped)
   - собираем DTO-снапшоты для фронта"
  (:require [mini-torrent.torrent :as tor]
            [mini-torrent.tracker :as tr]
            [mini-torrent.core :as core])
  (:import [java.io File]
           [java.util UUID]))

;; -------------------------
;; Registry
;; -------------------------

(defonce ^:private sessions*
  (atom {}))

(defn- now-ms [] (System/currentTimeMillis))

(defn- new-id [] (str (UUID/randomUUID)))

(defn list-sessions []
  (vals @sessions*))

(defn get-session [id]
  (get @sessions* id))

;; -------------------------
;; Session DTO helpers
;; -------------------------

(defn- state->status [state done?]
  (cond
    done? :completed
    (= state :paused) :paused
    (= state :stopped) :stopped
    :else :running))

(defn- session-status [session]
  (let [done?  (boolean @(-> session :done))
        state  (get @(-> session :control) :state :running)]
    (state->status state done?)))

(defn- safe-div [a b]
  (if (and (number? b) (pos? (double b)))
    (/ (double a) (double b))
    0.0))

(defn- progress-fraction
  "0..1 по downloaded/total (предпочтительно) + fallback по pieces."
  [{:keys [total-bytes pieces-total stats]}]
  (let [downloaded (long @(-> stats :downloaded))
        total      (long (or total-bytes 0))]
    (if (pos? total)
      (min 1.0 (max 0.0 (safe-div downloaded total)))
      (let [pd (long @(-> stats :pieces-done))
            pt (long (or pieces-total 0))]
        (if (pos? pt) (min 1.0 (max 0.0 (safe-div pd pt))) 0.0)))))

(defn session->summary
  "DTO для списка /api/torrents"
  [session]
  (let [stats (:stats session)]
    {:id             (:id session)
     :name           (:name session)
     :status         (session-status session)
     :progress       (progress-fraction session)
     :downloadedBytes (long @(-> stats :downloaded))
     :downSpeed      (long @(-> session :down-speed))
     :outDir         (:out-dir session)
     :createdAtMs    (:created-at-ms session)}))

(defn session->details
  "DTO для /api/torrents/:id"
  [session]
  (let [stats (:stats session)]
    {:id             (:id session)
     :name           (:name session)
     :status         (session-status session)
     :progress       (progress-fraction session)

     :downloadedBytes (long @(-> stats :downloaded))
     :totalBytes     (long (:total-bytes session))
     :downSpeed      (long @(-> session :down-speed))

     :piecesDone     (long @(-> stats :pieces-done))
     :piecesTotal    (long (:pieces-total session))

     :peersActive    (long @(-> stats :peers-active))
     :peerFails      (into {} @(-> stats :peer-fails))

     :infoHashHex    (:info-hash-hex (:torrent session))
     :outPath        (-> stats :out-path)}))

(defn session->peers
  "Пока в ядре нет полноценного реестра текущих peers с метриками,
   поэтому возвращаем минимум. Позже расширим, когда добавим tracking активных соединений."
  [session]
  (let [stats (:stats session)]
    {:id          (:id session)
     :peersActive (long @(-> stats :peers-active))
     :peerFails   (into {} @(-> stats :peer-fails))}))

;; -------------------------
;; Control
;; -------------------------

(defn pause! [id]
  (when-let [s (get-session id)]
    (swap! (:control s) assoc :state :paused :updated-at-ms (now-ms))
    true))

(defn resume! [id]
  (when-let [s (get-session id)]
    (swap! (:control s) assoc :state :running :updated-at-ms (now-ms))
    true))

(defn stop! [id]
  (when-let [s (get-session id)]
    (swap! (:control s) assoc :state :stopped :updated-at-ms (now-ms))
    ;; done=true помогает быстро завершить менеджер и статистику
    (reset! (:done s) true)
    true))

(defn delete! [id]
  (when-let [s (get-session id)]
    (stop! id)
    (swap! sessions* dissoc id)
    true))

;; -------------------------
;; Speed tracker (bytes/s)
;; -------------------------

(defn- start-speed-tracker!
  "Обновляет session :down-speed (atom) раз в 1с.
   Срабатывает пока (not @done)."
  [{:keys [stats done down-speed]}]
  (future
    (loop [prev-bytes (long @(-> stats :downloaded))
           prev-t     (now-ms)]
      (when-not @done
        (Thread/sleep 1000)
        (let [t (now-ms)
              b (long @(-> stats :downloaded))
              dt (max 1.0 (/ (- t prev-t) 1000.0))
              sp (long (max 0 (Math/round (/ (- b prev-bytes) dt))))]
          (reset! down-speed sp)
          (recur b t))))))

;; -------------------------
;; Create session
;; -------------------------

(defn create-session!
  "Создаёт и запускает загрузку торрента.

   opts:
   - :torrent-path (обязательно)
   - :out-dir (default \"downloads\")
   - :target-peers (default 100)
   - :port (default 6881)  ; для announce (TCP слушать мы не начинаем)
   - :peer-id (default random)

   Возвращает session map (в registry она уже добавлена)."
  [{:keys [torrent-path out-dir target-peers port peer-id]
    :or   {out-dir "downloads"
           target-peers 100
           port 6881}}]
  (when-not torrent-path
    (throw (ex-info "torrent-path is required" {})))

  (let [t (tor/parse-torrent torrent-path)
        peer-id (or peer-id (tr/random-peer-id))
        id (new-id)
        pieces-total (count (:piece-hashes t))

        _ (.mkdirs (File. out-dir))
        out-path (str out-dir File/separator (:name t))
        _ (core/ensure-file! out-path (:length t))

        stats {:downloaded   (atom 0)
               :pieces-done  (atom 0)
               :peers-active (atom 0)
               :peer-fails   (atom {})
               :out-path     out-path}

        done (atom false)
        control (atom {:state :running :updated-at-ms (now-ms)})
        queue (atom (vec (range pieces-total)))
        down-speed (atom 0)

        session {:id id
                 :created-at-ms (now-ms)
                 :out-dir out-dir

                 :torrent t
                 :name (:name t)
                 :total-bytes (:length t)
                 :pieces-total pieces-total

                 :peer-id peer-id
                 :port port

                 :stats stats
                 :queue queue
                 :done done
                 :control control
                 :down-speed down-speed}]

    ;; registry first (чтобы API увидел сессию сразу)
    (swap! sessions* assoc id session)

    ;; скорость
    (start-speed-tracker! session)

    ;; запуск ядра
    (core/peer-manager! {:torrent t
                         :peer-id peer-id
                         :port port
                         :stats stats
                         :queue queue
                         :done done
                         :control control}
                        target-peers)

    session))
