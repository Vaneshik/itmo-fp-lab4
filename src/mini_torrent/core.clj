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

(defn piece-len
  "Length of piece idx (last piece may be shorter)."
  [total piece-length idx pieces-count]
  (let [start (* (long idx) (long piece-length))
        remain (- (long total) start)]
    (long (min (long piece-length) remain))))

(defn ensure-file!
  [out-path total-len]
  (let [f (File. out-path)
        parent (.getParentFile f)]
    (when parent (.mkdirs parent))
    (with-open [raf (RandomAccessFile. f "rw")]
      (.setLength raf (long total-len)))
    out-path))

(defn write-block!
  [^RandomAccessFile raf piece-idx piece-length begin ^bytes block]
  (let [offset (+ (* (long piece-idx) (long piece-length))
                  (long begin))]
    (.seek raf offset)
    (.write raf block 0 (alength block))))

(defn read-piece-bytes
  [^RandomAccessFile raf piece-idx piece-length plen]
  (let [buf (byte-array (int plen))]
    (.seek raf (* (long piece-idx) (long piece-length)))
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

(defn start-stats-printer!
  [stats total-len pieces-total done]
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

(defn- peer-key ^String [{:keys [ip port]}]
  (str ip ":" port))

(defn- peer-fail!
  "Inc fail count for peer. Return new count."
  [stats peer]
  (let [k (peer-key peer)]
    (get (swap! (:peer-fails stats) (fn [m] (update m k (fnil inc 0)))) k)))

(defn- peer-bad?
  "Consider peer 'bad' only after N fails."
  [stats peer]
  (let [k (peer-key peer)
        n (get @(:peer-fails stats) k 0)]
    (>= n 3)))

(defn- handle-msg!
  "Updates have/choked state for common messages."
  [{:keys [pieces-count have have-known? choked?]} id payload]
  (case (pw/msg-type id)
    :unchoke (reset! choked? false)
    :choke   (reset! choked? true)

    :bitfield (let [bf (pw/parse-bitfield payload pieces-count)]
                (dotimes [i pieces-count]
                  (aset-boolean have i (aget bf i)))
                (reset! have-known? true))

    :have (let [idx (pw/parse-have-index payload)]
            (when (< idx pieces-count)
              (aset-boolean have (int idx) true)
              (reset! have-known? true)))
    nil))

(defn- wait-for-block!
  "Wait until we receive :piece for (piece-idx, expected-begin).
   While waiting, handle choke/unchoke/have/bitfield.
   Returns block bytes."
  [in state piece-idx expected-begin expected-len]
  (loop [timeouts 0]
    (let [{:keys [timeout eof keep-alive id payload]} (pw/read-msg! in)]
      (cond
        eof
        (throw (ex-info "Peer disconnected" {}))

        timeout
        (if (>= timeouts 4)
          (throw (ex-info "Too many timeouts waiting block" {:piece piece-idx :begin expected-begin}))
          (recur (inc timeouts)))

        keep-alive
        (recur timeouts)

        (nil? id)
        (recur timeouts)

        :else
        (let [t (pw/msg-type id)]
          (cond
            (= t :choke)
            (do
              (handle-msg! state id payload)
              (throw (ex-info "Choked mid-piece" {:piece piece-idx})))

            (= t :piece)
            (let [{idx :index b :begin block :block} (pw/parse-piece payload)]
              (if (and (= idx piece-idx) (= b expected-begin))
                (do
                  (when-not (= (alength ^bytes block) expected-len)
                    (throw (ex-info "Bad block length" {:got (alength ^bytes block)
                                                        :expected expected-len
                                                        :piece piece-idx
                                                        :begin expected-begin})))
                  block)
                (recur timeouts)))

            :else
            (do
              (handle-msg! state id payload)
              (recur timeouts))))))))

(defn- download-piece!
  "Download one piece from this peer. Returns true if piece verified OK."
  [^RandomAccessFile raf in out stats torrent state piece-idx]
  (let [pieces-count (:pieces-count state)
        plen (piece-len (:length torrent) (:piece-length torrent) piece-idx pieces-count)]
    (loop [begin0 0]
      (when (< begin0 plen)
        (let [expected-begin begin0
              expected-len   (min block-size (- plen begin0))
              req-payload    (pw/build-request-payload piece-idx expected-begin expected-len)]
          (pw/send-msg! out 6 req-payload)
          (let [block (wait-for-block! in state piece-idx expected-begin expected-len)]
            (write-block! raf piece-idx (:piece-length torrent) expected-begin block)
            (swap! (:downloaded stats) + (alength ^bytes block)))
          (recur (+ begin0 expected-len)))))

    ;; sha1 verify
    (let [piece-bytes (read-piece-bytes raf piece-idx (:piece-length torrent) plen)
          got (pw/sha1 piece-bytes)
          expected (nth (:piece-hashes torrent) piece-idx)]
      (Arrays/equals ^bytes got ^bytes expected))))

(defn worker!
  [{:keys [peer torrent peer-id stats queue done]}]
  (let [current-piece (atom nil)]
    (try
      (with-open [sock (pw/connect peer 8000 60000)
                  in  (java.io.DataInputStream. (java.io.BufferedInputStream. (.getInputStream sock)))
                  out (java.io.DataOutputStream. (java.io.BufferedOutputStream. (.getOutputStream sock)))
                  raf (RandomAccessFile. (File. (:out-path stats)) "rw")]

        (swap! (:peers-active stats) inc)

        ;; handshake
        (pw/send-handshake! out (:info-hash torrent) peer-id)
        (let [{:keys [pstr info-hash]} (pw/read-handshake! in)]
          (when-not (= pstr "BitTorrent protocol")
            (throw (ex-info "Bad handshake" {:pstr pstr})))
          (when-not (Arrays/equals ^bytes info-hash ^bytes (:info-hash torrent))
            (throw (ex-info "Wrong info_hash" {}))))

        ;; interested
        (pw/send-msg! out 2 (byte-array 0))

        (let [pieces-count (count (:piece-hashes torrent))
              have (boolean-array pieces-count)
              have-known? (atom false)
              choked? (atom true)
              state {:pieces-count pieces-count
                     :have have
                     :have-known? have-known?
                     :choked? choked?}]

          (loop []
            (when-not @done
              (let [{:keys [timeout eof keep-alive id payload]} (pw/read-msg! in)]

                (cond
                  eof
                  (throw (ex-info "Peer disconnected" {}))

                  ;; timeout = "тик": можно попытаться начать скачивать, даже если нет сообщений
                  timeout
                  (do
                    (when (and (not @choked?) @have-known? (nil? @current-piece))
                      (when-let [piece-idx (pick-piece! queue have)]
                        (reset! current-piece piece-idx)
                        (let [ok? (download-piece! raf in out stats torrent state piece-idx)]
                          (if ok?
                            (swap! (:pieces-done stats) inc)
                            (swap! queue conj piece-idx))
                          (reset! current-piece nil))))
                    (recur))

                  keep-alive
                  (recur)

                  (nil? id)
                  (recur)

                  :else
                  (do
                    (handle-msg! state id payload)

                    ;; после обработки сообщений — если готовы, берём piece
                    (when (and (not @choked?) @have-known? (nil? @current-piece))
                      (when-let [piece-idx (pick-piece! queue have)]
                        (reset! current-piece piece-idx)
                        (let [ok? (download-piece! raf in out stats torrent state piece-idx)]
                          (if ok?
                            (swap! (:pieces-done stats) inc)
                            (swap! queue conj piece-idx))
                          (reset! current-piece nil))))

                    (when (= @(:pieces-done stats) pieces-count)
                      (reset! done true))

                    (recur))))))))

      (catch Exception e
        ;; вернуть кусок в очередь
        (when-let [p @current-piece]
          (swap! queue conj p))

        ;; учесть фейл (но баним только после 3)
        (let [n (peer-fail! stats peer)]
          (when (>= n 3)
            (println "\n[worker] peer marked bad:" (peer-key peer) "(fails:" n ")")))

        (println "\n[worker] peer failed:" (peer-key peer)
                 "| ex:" (.getName (class e))
                 "| msg:" (or (.getMessage e) "nil")))

      (finally
        (swap! (:peers-active stats) (fn [x] (max 0 (dec x))))))))

(defn peer-manager!
  "Держит target активных воркеров. Уважает tracker interval (не спамит).
   Ранний re-announce делаем только если совсем нет пиров."
  [{:keys [torrent peer-id port stats queue done]} target]
  (let [peers-pool (atom [])
        started?   (atom false)
        in-flight  (atom #{})
        last-announce-ms (atom 0)
        next-announce-ms (atom 0)
        last-interval-s  (atom 120)]
    (future
      (while (not @done)
        (let [now (now-ms)
              active @(:peers-active stats)
              pool-empty? (empty? @peers-pool)
              early? (and (= active 0) pool-empty? (>= (- now @last-announce-ms) 15000))
              due? (>= now @next-announce-ms)]

          ;; announce только когда надо
          (when (and pool-empty? (or due? early?))
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
                    interval (long (max 30 (or (:interval resp) 120)))
                    peers (:peers resp)
                    ;; фильтруем только реально bad + уже в flight
                    peers2 (->> peers
                                (remove #(peer-bad? stats %))
                                (remove @in-flight)
                                vec)]
                (reset! last-announce-ms now)
                (reset! last-interval-s interval)
                (reset! next-announce-ms (+ now (* interval 1000)))

                (println (format "\n[tracker] peers raw=%d fresh=%d interval=%ds"
                                 (count peers) (count peers2) interval))

                (when (seq peers2)
                  (swap! peers-pool into peers2)))

              (catch Exception e
                (println "\n[tracker] announce failed:" (.getMessage e))
                ;; если трекер упал — пробуем снова через 15с
                (reset! next-announce-ms (+ now 15000))))))

        ;; запускаем воркеры
        (while (and (not @done)
                    (< @(:peers-active stats) target)
                    (seq @peers-pool))
          (let [p (first @peers-pool)]
            (swap! peers-pool subvec 1)
            (when (and (not (peer-bad? stats p))
                       (not (contains? @in-flight p)))
              (swap! in-flight conj p)
              (future
                (try
                  (worker! {:peer p :torrent torrent :peer-id peer-id
                            :stats stats :queue queue :done done})
                  (finally
                    (swap! in-flight disj p)))))))

        (Thread/sleep 200)))))

(defn -main [& args]
  (let [[torrent-path out-dir] args
        out-dir (or out-dir "downloads")]
    (when-not torrent-path
      (println "Usage: clj -M -m mini-torrent.core <file.torrent> [out-dir]")
      (System/exit 2))

    (let [t (tor/parse-torrent torrent-path)
          peer-id (tr/random-peer-id)
          port 6881
          pieces-total (count (:piece-hashes t))
          out-path (str out-dir File/separator (:name t))
          _ (.mkdirs (File. out-dir))
          _ (ensure-file! out-path (:length t))

          stats {:downloaded   (atom 0)
                 :pieces-done  (atom 0)
                 :peers-active (atom 0)
                 :peer-fails   (atom {})
                 :out-path     out-path}

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

      ;; webseed оставляем как fallback (если появится поддержка url-list в torrent.clj)
      (when (seq (:webseeds t))
        (println "[info] webseeds present, but this torrent parser may not fill them yet."))

      (peer-manager! {:torrent t :peer-id peer-id :port port
                      :stats stats :queue queue :done done}
                     30)

      (while (not @done)
        (Thread/sleep 200))

      (println "\nDone ->" out-path))))
