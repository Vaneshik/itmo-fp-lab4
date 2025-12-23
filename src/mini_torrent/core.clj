(ns mini-torrent.core
  (:gen-class)
  (:require [mini-torrent.torrent :as tor]
            [mini-torrent.webseed :as ws]
            [mini-torrent.tracker :as tr]
            [mini-torrent.peer :as pw])
  (:import [java.io RandomAccessFile File]
           [java.util Arrays]))

(def block-size 16384)

(defn now-ms [] (System/currentTimeMillis))

(defn fmt-bytes [n]
  (let [units ["B" "KB" "MB" "GB" "TB"]]
    (loop [v (double (max 0 (long n))) i 0]
      (if (or (< v 1024.0) (= i (dec (count units))))
        (format "%.2f %s" v (units i))
        (recur (/ v 1024.0) (inc i))))))

(defn piece-len [total piece-length piece-idx pieces-count]
  (if (= piece-idx (dec pieces-count))
    (long (- total (* (dec pieces-count) piece-length)))
    (long piece-length)))

(defn ensure-file! [out-path total-len]
  (let [f (File. out-path)]
    (.mkdirs (.getParentFile f))
    (with-open [raf (RandomAccessFile. f "rw")]
      (.setLength raf (long total-len)))
    out-path))

(defn write-block! [^RandomAccessFile raf piece-idx piece-length begin ^bytes block]
  (let [offset (+ (long (* (long piece-idx) (long piece-length)))
                  (long begin))]
    (.seek raf offset)
    (.write raf block 0 (alength block))))

(defn read-piece-bytes [^RandomAccessFile raf piece-idx piece-length plen]
  (let [buf (byte-array (int plen))]
    (.seek raf (long (* (long piece-idx) (long piece-length))))
    (.readFully raf buf)
    buf))

(defn pick-piece!
  "Берёт из очереди первый piece, который есть у этого пира. Если нет — nil."
  [queue ^booleans have]
  (let [v @queue
        n (count v)]
    (loop [i 0]
      (when (< i n)
        (let [p (nth v i)]
          (if (aget have (int p))
            (do
              (swap! queue (fn [vv]
                             (vec (concat (subvec vv 0 i) (subvec vv (inc i))))))
              p)
            (recur (inc i))))))))

(defn start-stats-printer! [stats total-len pieces-total done]
  (future
    (loop [prev-bytes 0 prev-t (now-ms)]
      (when-not @done
        (Thread/sleep 1000)
        (let [t (now-ms)
              b @(:downloaded stats)
              dt (/ (- t prev-t) 1000.0)
              speed (if (pos? dt) (/ (- b prev-bytes) dt) 0.0)
              pct (* 100.0 (/ (double @(:pieces-done stats)) (double pieces-total)))]
          (print (format "\r%6.2f%% | peers:%2d | %s / %s | %s/s"
                         pct @(:peers-active stats)
                         (fmt-bytes b) (fmt-bytes total-len)
                         (fmt-bytes speed)))
          (flush)
          (recur b t))))
    (println)))

(defn- safe-read-msg!
  "pw/read-msg! кидает SocketTimeoutException при SoTimeout.
   Для устойчивости воркера таймаут считаем «нет сообщения»."
  [^java.io.DataInputStream in]
  (try
    (pw/read-msg! in)
    (catch java.net.SocketTimeoutException _
      {:timeout true})
    (catch java.io.EOFException _
      {:eof true})))

(defn worker!
  [{:keys [peer torrent peer-id stats queue done]}]
  (let [current-piece (atom nil)]
    (try
      (with-open [sock (pw/connect peer 5000)
                  in  (java.io.DataInputStream. (java.io.BufferedInputStream. (.getInputStream sock)))
                  out (java.io.DataOutputStream. (java.io.BufferedOutputStream. (.getOutputStream sock)))
                  raf (RandomAccessFile. (File. (:out-path stats)) "rw")]

        ;; важное: поднять read timeout (peer.clj ставит 5s — это слишком мало)
        (.setTcpNoDelay sock true)
        (.setSoTimeout sock 60000)

        (swap! (:peers-active stats) inc)

        ;; handshake
        (pw/send-handshake! out (:info-hash torrent) peer-id)
        (let [{:keys [pstr info-hash]} (pw/read-handshake! in)]
          (when-not (= pstr "BitTorrent protocol")
            (throw (ex-info "Bad handshake" {:peer peer :pstr pstr})))
          (when-not (Arrays/equals ^bytes info-hash ^bytes (:info-hash torrent))
            (throw (ex-info "Wrong info_hash" {:peer peer}))))

        ;; interested
        (pw/send-msg! out 2 (byte-array 0))

        (let [pieces-count (count (:piece-hashes torrent))
              have (boolean-array pieces-count)
              have-known? (atom false)
              choked? (atom true)]

          (loop []
            (when-not @done
              (let [{:keys [id payload timeout eof]} (safe-read-msg! in)]
                (cond
                  eof
                  (throw (ex-info "Peer disconnected" {:peer peer}))

                  timeout
                  (recur)

                  :else
                  (do
                    ;; обработка входящих
                    (when id
                      (case (pw/msg-type id)
                        :unchoke (reset! choked? false)
                        :choke   (reset! choked? true)

                        :bitfield (let [bf (pw/parse-bitfield payload pieces-count)]
                                    (dotimes [i pieces-count]
                                      (aset-boolean have i (aget bf i)))
                                    (reset! have-known? true))

                        :have (let [idx (pw/parse-have-index payload)]
                                (when (< idx pieces-count)
                                  (aset-boolean have idx true)
                                  (reset! have-known? true)))
                        nil))

                    ;; берём работу только если мы не choked и have известен
                    (when (and (not @choked?) @have-known? (not @done))
                      (when-let [piece-idx (pick-piece! queue have)]
                        (reset! current-piece piece-idx)

                        (let [plen (piece-len (:length torrent) (:piece-length torrent) piece-idx pieces-count)]

                          ;; качаем блоками
                          (loop [begin0 0]
                            (when (< begin0 plen)
                              (let [expected-begin begin0
                                    expected-len   (min block-size (- plen begin0))
                                    req-payload    (pw/build-request-payload piece-idx expected-begin expected-len)]
                                (pw/send-msg! out 6 req-payload)

                                ;; ждём piece именно для этого begin
                                (loop [timeouts 0]
                                  (let [{:keys [id payload timeout eof]} (safe-read-msg! in)]
                                    (cond
                                      eof (throw (ex-info "Peer disconnected mid-piece" {:peer peer}))
                                      timeout (if (>= timeouts 4)
                                                (throw (ex-info "Too many timeouts waiting for block" {:peer peer}))
                                                (recur (inc timeouts)))

                                      (nil? id) (recur timeouts)

                                      (= (pw/msg-type id) :choke)
                                      (throw (ex-info "Choked mid-piece" {:peer peer :piece piece-idx}))

                                      (= (pw/msg-type id) :unchoke)
                                      (do (reset! choked? false) (recur timeouts))

                                      (= (pw/msg-type id) :piece)
                                      (let [{idx :index b :begin block :block} (pw/parse-piece payload)]
                                        (if (and (= idx piece-idx) (= b expected-begin))
                                          (do
                                            (when-not (= (alength ^bytes block) expected-len)
                                              (throw (ex-info "Bad block length"
                                                              {:got (alength ^bytes block)
                                                               :expected expected-len
                                                               :peer peer})))
                                            (write-block! raf idx (:piece-length torrent) b block)
                                            (swap! (:downloaded stats) + (alength ^bytes block)))
                                          (recur timeouts)))

                                      :else (recur timeouts))))

                                (recur (+ begin0 expected-len)))))

                          ;; sha1 check
                          (let [piece-bytes (read-piece-bytes raf piece-idx (:piece-length torrent) plen)
                                got (pw/sha1 piece-bytes)
                                expected (nth (:piece-hashes torrent) piece-idx)]
                            (if (Arrays/equals ^bytes got ^bytes expected)
                              (swap! (:pieces-done stats) inc)
                              (swap! queue conj piece-idx)))

                          (reset! current-piece nil)

                          (when (= @(:pieces-done stats) pieces-count)
                            (reset! done true)))))

                    (recur))))))))

      (catch Exception e
        (when-let [p @current-piece]
          (swap! queue conj p))
        (swap! (:bad-peers stats) conj peer)
        (println "\n[worker] peer failed:"
                 (:ip peer) (:port peer)
                 "| ex:" (.getName (class e))
                 "| msg:" (or (.getMessage e) "nil")))

      (finally
        (swap! (:peers-active stats) (fn [x] (max 0 (dec x))))))))

(defn peer-manager!
  "Держит target активных воркеров. Если пиры закончились — обновляет у трекера."
  [{:keys [torrent peer-id port stats queue done]} target]
  (let [peers-pool (atom [])
        started?   (atom false)
        in-flight  (atom #{})]
    (future
      (while (not @done)

        ;; 1) если кандидатов нет — просим у трекера ещё раз
        (when (empty? @peers-pool)
          (try
            (let [resp (tr/announce {:announce   (:announce torrent)
                                     :info-hash  (:info-hash torrent)
                                     :peer-id    peer-id
                                     :port       port
                                     :uploaded   0
                                     :downloaded @(:downloaded stats)
                                     :left       (max 0 (- (:length torrent) @(:downloaded stats)))
                                     :numwant    100
                                     :event      (when (compare-and-set! started? false true) "started")})
                  peers (:peers resp)
                  bad   @(:bad-peers stats)
                  peers2 (->> peers
                              (remove bad)
                              (remove @in-flight)
                              vec)]
              (reset! peers-pool peers2)
              (when (empty? peers2)
                (println "\n[tracker] got 0 fresh peers, retrying...")
                (Thread/sleep 2000)))
            (catch Exception e
              (println "\n[tracker] announce failed:" (.getMessage e))
              (Thread/sleep 2000))))

        ;; 2) стартуем воркеры пока не доберёмся до target
        (let [bad @(:bad-peers stats)]
          (while (and (not @done)
                      (< @(:peers-active stats) target)
                      (seq @peers-pool))
            (let [p (first @peers-pool)]
              (swap! peers-pool subvec 1)
              (when (and (not (contains? bad p))
                         (not (contains? @in-flight p)))
                (swap! in-flight conj p)
                (future
                  (try
                    (worker! {:peer p :torrent torrent :peer-id peer-id
                              :stats stats :queue queue :done done})
                    (finally
                      (swap! in-flight disj p))))))))

        ;; 3) если совсем никого нет — крутимся быстрее (чтобы не “повисать”)
        (if (and (zero? @(:peers-active stats)) (empty? @peers-pool))
          (Thread/sleep 500)
          (Thread/sleep 1500))))))

(defn -main [& args]
  (let [[torrent-path out-dir] args
        out-dir (or out-dir "downloads")]
    (when-not torrent-path
      (println "Usage: clj -M -m mini-torrent.core <file.torrent> [out-dir]")
      (System/exit 2))

    (let [t (tor/load-torrent torrent-path)
          peer-id (tr/random-peer-id)  ;; <-- ВАЖНО: peer-id генерится в tracker.clj
          port 6881
          pieces-total (count (:piece-hashes t))
          out-path (str out-dir File/separator (:name t))
          _ (.mkdirs (File. out-dir))
          _ (ensure-file! out-path (:length t))
          stats {:downloaded (atom 0)
                 :pieces-done (atom 0)
                 :peers-active (atom 0)
                 :bad-peers (atom #{})
                 :out-path out-path}
          done (atom false)
          queue (atom (vec (range pieces-total)))]

      (println "announce:" (:announce t))
      (println "name    :" (:name t))
      (println "size    :" (:length t) "bytes")
      (println "pieces  :" pieces-total)
      (println "infohash:" (:info-hash-hex t))
      (println "webseeds:" (count (:webseeds t)))
      (println "piece-hashes type:" (class (:piece-hashes t)))
      (println "first hash type:" (class (first (:piece-hashes t))))

      (start-stats-printer! stats (:length t) pieces-total done)

      ;; webseed (если есть) — пробуем, иначе peer mode
      (when (and (not @done) (seq (:webseeds t)))
        (let [u (first (:webseeds t))]
          (println "Using webseed:" u)
          (try
            (ws/download! {:webseed-url u
                           :out-path out-path
                           :length (:length t)
                           :piece-length (:piece-length t)
                           :piece-hashes (:piece-hashes t)
                           :sha1 pw/sha1}
                          stats done)
            (reset! done true)
            (catch Exception e
              (println "\n[webseed] failed:" (.getMessage e))
              (println "[webseed] fallback to peers...")))))

      (when-not @done
        (peer-manager! {:torrent t :peer-id peer-id :port port
                        :stats stats :queue queue :done done}
                       30))

      (while (not @done) (Thread/sleep 200))
      (println "Done ->" out-path))))
