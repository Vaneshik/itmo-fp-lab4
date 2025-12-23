(ns mini-torrent.core
  (:gen-class)
  (:require [mini-torrent.torrent :as tor]
            [mini-torrent.tracker :as tr]
            [mini-torrent.peer :as pw]
            [mini-torrent.bytes :as bx]
            [clojure.tools.logging :as log])
  (:import [java.io RandomAccessFile File]
           [java.util Arrays]
           [java.net InetAddress Inet6Address]))

(def block-size 16384)
(def pipeline-depth 12)

(def ^:private peers-pool-cap 5000)
(def ^:private pex-batch-cap 200)

(defn- control-state
  "Возвращает :running/:paused/:stopped. Если control не передали — считаем :running."
  [control]
  (try
    (or (get @control :state) :running)
    (catch Exception _ :running)))

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
    (if (= idx (dec pieces-count))
      remain
      (long piece-length))))

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

(defn- read-piece-bytes
  "Read piece idx from file."
  [^RandomAccessFile raf piece-idx piece-length plen]
  (let [buf (byte-array plen)]
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

(defn verify-piece?
  [torrent ^RandomAccessFile raf piece-idx]
  (let [plen (piece-len (:length torrent) (:piece-length torrent) piece-idx (:pieces-count torrent))
        piece-bytes (read-piece-bytes raf piece-idx (:piece-length torrent) plen)
        got (bx/sha1 piece-bytes)
        expected (nth (:piece-hashes torrent) piece-idx)]
    (Arrays/equals got expected)))

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
          (log/info "Downloaded" pct "|" (fmt-bytes b) "/" (fmt-bytes total-len) "bytes"
                    "peers" @(:peers-active stats)
                    "speed" (fmt-bytes speed) "B/s")
          (recur b t))))))

(defn- valid-port? [p]
  (and (number? p) (<= 1024 (long p) 65535)))

(defn- inet6-ula? [^InetAddress a]
  ;; Unique Local Address: fc00::/7 (fc.. or fd..)
  (when (instance? Inet6Address a)
    (let [b0 (bit-and (int (aget (.getAddress a) 0)) 0xff)]
      (= (bit-and b0 0xfe) 0xfc))))

(defn- public-ip? [^String ip]
  (try
    (let [^InetAddress a (InetAddress/getByName ip)]
      (not (or (.isAnyLocalAddress a)
               (.isLoopbackAddress a)
               (.isLinkLocalAddress a)
               (.isSiteLocalAddress a)   ;; covers 10/8, 172.16/12, 192.168/16, etc
               (.isMulticastAddress a)
               (inet6-ula? a))))
    (catch Exception _
      false)))

(defn- good-peer?
  "Фильтр от мусора из трекера/PEX (private IP, loopback, link-local, multicast, невалидный порт)."
  [{:keys [ip port]}]
  (and (string? ip)
       (valid-port? port)
       (public-ip? ip)))

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

(defn- add-peers-to-pool!
  "Добавляет peers в peers-pool:
   - фильтрует мусор
   - убирает bad и in-flight
   - дедуп
   - капает общий пул и одну порцию"
  [peers-pool stats in-flight peers]
  (let [fresh (->> peers
                   (filter good-peer?)
                   (remove #(peer-bad? stats %))
                   (remove @in-flight)
                   distinct
                   (take pex-batch-cap)
                   vec)]
    (when (seq fresh)
      (swap! peers-pool
             (fn [v]
               (->> (concat v fresh)
                    distinct
                    (take peers-pool-cap)
                    vec))))))

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

(defn- blocks-for-piece
  "Returns vector of [begin len] blocks for a piece length plen."
  [^long plen]
  (loop [begin 0 acc []]
    (if (>= begin plen)
      acc
      (let [l (long (min block-size (- plen begin)))]
        (recur (+ begin l) (conj acc [begin l]))))))

(defn- download-piece-pipelined!
  "Download piece using request pipelining.
   Returns true if verified OK.
   handle-extended! вызывается на extended-сообщениях (PEX).
   May throw ExceptionInfo with {:reason :choked} to signal 'not fatal, just retry later'."
  [^RandomAccessFile raf in out stats torrent state handle-extended! piece-idx]
  (let [pieces-count (:pieces-count state)
        plen (piece-len (:length torrent) (:piece-length torrent) piece-idx pieces-count)
        blocks (blocks-for-piece plen)
        total (count blocks)
        len-by-begin (into {} blocks)]

    (when (zero? total)
      (throw (ex-info "Bad piece length" {:piece piece-idx :plen plen})))

    (let [next-send (atom 0)
          pending   (atom (into #{} (map first blocks)))]

      (letfn [(send-next! []
                (when (< @next-send total)
                  (let [[b l] (nth blocks @next-send)]
                    (pw/send-msg! out 6 (pw/build-request-payload piece-idx b l))
                    (swap! next-send inc)
                    true)))]

        (dotimes [_ (min pipeline-depth total)]
          (send-next!))

        (loop [remaining total timeouts 0]
          (when (pos? remaining)
            (let [{:keys [timeout eof keep-alive id payload]} (pw/read-msg! in)]
              (cond
                eof
                (throw (ex-info "Peer disconnected" {}))

                timeout
                (if (>= timeouts 6)
                  (throw (ex-info "Too many timeouts waiting piece blocks" {:piece piece-idx}))
                  (recur remaining (inc timeouts)))

                keep-alive
                (recur remaining timeouts)

                (nil? id)
                (recur remaining timeouts)

                :else
                (let [t (pw/msg-type id)]
                  (cond
                    (= t :extended)
                    (do
                      (handle-extended! payload)
                      (recur remaining timeouts))

                    (= t :choke)
                    (do
                      (handle-msg! state id payload)
                      (throw (ex-info "Choked mid-piece" {:reason :choked :piece piece-idx})))

                    (= t :piece)
                    (let [{idx :index b :begin block :block} (pw/parse-piece payload)]
                      (if (and (= idx piece-idx) (contains? @pending b))
                        (let [expected-len (long (get len-by-begin b -1))]
                          (when-not (= expected-len (alength ^bytes block))
                            (throw (ex-info "Bad block length"
                                            {:piece piece-idx :begin b
                                             :got (alength ^bytes block) :expected expected-len})))
                          (write-block! raf piece-idx (:piece-length torrent) b block)
                          (swap! (:downloaded stats) + (alength ^bytes block))
                          (swap! pending disj b)
                          (send-next!)
                          (recur (dec remaining) 0))
                        (recur remaining timeouts)))

                    :else
                    (do
                      (handle-msg! state id payload)
                      (recur remaining timeouts))))))))

        (let [piece-bytes (read-piece-bytes raf piece-idx (:piece-length torrent) plen)
              got (bx/sha1 piece-bytes)
              expected (nth (:piece-hashes torrent) piece-idx)]
          (Arrays/equals ^bytes got ^bytes expected))))))

(defn worker!
  [{:keys [peer torrent peer-id stats queue done peers-pool port in-flight control peers]}]
  (let [current-piece (atom nil)
        k (peer-key peer)
        touch! (fn []
                 (when peers
                   (swap! peers update k (fn [m]
                                           (assoc (or m {})
                                                  :lastSeenMs (now-ms))))))]
    (try
      ;; регистрируем peer в реестре (если включён)
      (when peers
        (swap! peers assoc k {:peer k
                              :ip (:ip peer)
                              :port (:port peer)
                              :startedAtMs (now-ms)
                              :lastSeenMs (now-ms)}))

      (with-open [sock (pw/connect peer 3000 20000)
                  in  (java.io.DataInputStream. (java.io.BufferedInputStream. (.getInputStream sock)))
                  out (java.io.DataOutputStream. (java.io.BufferedOutputStream. (.getOutputStream sock)))
                  raf (RandomAccessFile. (File. (:out-path stats)) "rw")]

        ;; handshake
        (pw/send-handshake! out (:info-hash torrent) peer-id)
        (let [{:keys [pstr info-hash]} (pw/read-handshake! in)]
          (when-not (= pstr "BitTorrent protocol")
            (throw (ex-info "Bad handshake" {:pstr pstr})))
          (when-not (Arrays/equals ^bytes info-hash ^bytes (:info-hash torrent))
            (throw (ex-info "Wrong info_hash" {}))))

        ;; BEP-10: extended handshake (ut_pex)
        (pw/send-ext-handshake! out (long port))

        ;; interested
        (pw/send-msg! out 2 (byte-array 0))

        (let [pieces-count (count (:piece-hashes torrent))
              have (boolean-array pieces-count)
              have-known? (atom false)
              choked? (atom true)
              state {:pieces-count pieces-count
                     :have have
                     :have-known? have-known?
                     :choked? choked?}

              ut-pex-id (atom nil)

              handle-extended!
              (fn [^bytes payload]
                (let [{:keys [ext-id data]} (pw/parse-extended payload)]
                  (cond
                    (= ext-id 0)
                    (let [{id :ut-pex-id} (pw/parse-ext-handshake data)]
                      (when id (reset! ut-pex-id id)))

                    (and @ut-pex-id (= ext-id @ut-pex-id))
                    (let [{:keys [added]} (pw/parse-ut-pex data)]
                      (when (seq added)
                        (add-peers-to-pool! peers-pool stats in-flight added)))

                    :else nil)))]

          (loop []
            (when-not @done
              ;; периодически трогаем lastSeen
              (touch!)

              ;; stop => выходим аккуратно
              (when (= :stopped (control-state control))
                (reset! done true)
                (throw (ex-info "Stopped" {:reason :stopped})))

              (let [{:keys [timeout eof keep-alive id payload]} (pw/read-msg! in)]
                (cond
                  eof
                  (throw (ex-info "Peer disconnected" {}))

                  timeout
                  (do
                    (touch!)
                    (when (and (not @choked?) @have-known? (nil? @current-piece))
                      (when (= :running (control-state control))
                        (when-let [piece-idx (pick-piece! queue have)]
                          (reset! current-piece piece-idx)
                          (try
                            (let [ok? (download-piece-pipelined! raf in out stats torrent state handle-extended! piece-idx)]
                              (if ok?
                                (swap! (:pieces-done stats) inc)
                                (swap! queue conj piece-idx)))
                            (catch clojure.lang.ExceptionInfo ex
                              (if (= (:reason (ex-data ex)) :choked)
                                (swap! queue conj piece-idx)
                                (throw ex)))
                            (finally
                              (reset! current-piece nil)))

                          (when (= @(:pieces-done stats) pieces-count)
                            (reset! done true)))))

                    (recur))

                  keep-alive
                  (do (touch!) (recur))

                  (nil? id)
                  (do (touch!) (recur))

                  :else
                  (do
                    (touch!)

                    (when (= (pw/msg-type id) :extended)
                      (handle-extended! payload))

                    (handle-msg! state id payload)

                    (when (and (not @choked?) @have-known? (nil? @current-piece))
                      (when (= :running (control-state control))
                        (when-let [piece-idx (pick-piece! queue have)]
                          (reset! current-piece piece-idx)
                          (try
                            (let [ok? (download-piece-pipelined! raf in out stats torrent state handle-extended! piece-idx)]
                              (if ok?
                                (swap! (:pieces-done stats) inc)
                                (swap! queue conj piece-idx)))
                            (catch clojure.lang.ExceptionInfo ex
                              (if (= (:reason (ex-data ex)) :choked)
                                (swap! queue conj piece-idx)
                                (throw ex)))
                            (finally
                              (reset! current-piece nil)))

                          (when (= @(:pieces-done stats) pieces-count)
                            (reset! done true)))))

                    (recur))))))))

      (catch Exception e
        (when-let [p @current-piece]
          (swap! queue conj p))

        (if (= (:reason (ex-data e)) :stopped)
          (log/debug (format "[worker] stopped: %s" (peer-key peer)))
          (do
            (let [n (peer-fail! stats peer)]
              (when (>= n 3)
                (log/debug (format "[worker] peer marked bad: %s (fails: %d)" (peer-key peer) n))))
            (log/debug (format "[worker] peer failed: %s | ex: %s | msg: %s"
                           (peer-key peer)
                           (.getName (class e))
                           (or (.getMessage e) "nil"))))))

      (finally
        ;; снимаем peer из реестра активных
        (when peers
          (swap! peers dissoc k))))))


(defn peer-manager!
  "Держит target активных воркеров."
  [{:keys [torrent peer-id port stats queue done control peers]} target]
  (let [peers-pool (atom [])
        started?   (atom false)
        in-flight  (atom #{})
        last-announce-ms (atom 0)
        next-announce-ms (atom 0)]
    (future
      (loop []
        (while (not @done)
          (let [st (control-state control)]
            (cond
              (= st :stopped)
              (reset! done true)

              (= st :paused)
              (Thread/sleep 200)

              :else
              (do

                (let [now (now-ms)
                      running (count @in-flight)
                      pool-empty? (empty? @peers-pool)
                      early? (and (= running 0) pool-empty? (>= (- now @last-announce-ms) 15000))
                      due? (>= now @next-announce-ms)]

                  (when (and pool-empty? (or due? early?))
                    (try
                      (let [extra-trackers
                            ["udp://tracker.opentrackr.org:1337/announce"
                             "udp://open.stealth.si:80/announce"
                             "udp://open.demonii.com:1337/announce"
                             "udp://tracker.torrent.eu.org:451/announce"
                             "udp://tracker.dler.org:6969/announce"]
                            trackers (vec (distinct (concat (or (:announce-list torrent) [(:announce torrent)])
                                                            extra-trackers)))
                            event* (when (compare-and-set! started? false true) :started)

                            results
                            (map (fn [url]
                                   (try
                                     {:url url :ok true
                                      :resp (tr/announce {:announce   url
                                                          :info-hash  (:info-hash torrent)
                                                          :peer-id    peer-id
                                                          :port       port
                                                          :uploaded   0
                                                          :downloaded @(:downloaded stats)
                                                          :left       (max 0 (- (:length torrent) @(:downloaded stats)))
                                                          :numwant    400
                                                          :event      event*})}
                                     (catch Exception e
                                       (log/debug (str "[tracker] failed: " url " | " (.getMessage e)
                                                   (when (ex-data e) (str " | data=" (pr-str (ex-data e))))))
                                       {:url url :ok false :err e})))
                                 trackers)

                            good (filter :ok results)
                            peers (->> good (mapcat (comp :peers :resp)) vec)
                            interval (long (max 30 (or (some->> good (map (comp :interval :resp)) (remove nil?) (apply min))
                                                       120)))

                            peers2 (->> peers
                                        (filter good-peer?)
                                        (remove #(peer-bad? stats %))
                                        (remove @in-flight)
                                        distinct
                                        vec)]

                        (reset! last-announce-ms now)
                        (reset! next-announce-ms (+ now (* interval 1000)))

                        (let [pool-before (count @peers-pool)]
                          (log/debug (format
                                  "[tracker] trackers=%d ok=%d raw=%d fresh=%d pool(before)=%d active=%d interval=%ds"
                                  (count trackers) (count good) (count peers) (count peers2)
                                  pool-before @(:peers-active stats) interval)))

                        (when (seq peers2)
                          (add-peers-to-pool! peers-pool stats in-flight peers2))
                        (log/debug (format "[tracker] pool(after)=%d" (count @peers-pool))))
                      (catch Exception e
                        (log/debug "\n[tracker] announce failed:" (.getMessage e)
                               (when (ex-data e) (str " | data=" (pr-str (ex-data e)))))
                        (reset! next-announce-ms (+ now 15000))))))

                ;; запускаем воркеры
                (while (and (not @done)
                            (< (count @in-flight) target)
                            (seq @peers-pool))
                  (let [p (first @peers-pool)]
                    (swap! peers-pool subvec 1)
                    (when (and (good-peer? p)
                               (not (peer-bad? stats p))
                               (not (contains? @in-flight p)))
                      (swap! in-flight conj p)
                      (swap! (:peers-active stats) inc)
                      (log/debug (format "[manager] starting worker for %s (running=%d active=%d pool=%d)"
                                     (peer-key p) (count @in-flight) @(:peers-active stats) (count @peers-pool)))
                      (future
                        (try
                          (worker! {:peer p :torrent torrent :peer-id peer-id
                                    :stats stats :queue queue :done done
                                    :peers-pool peers-pool :port port
                                    :in-flight in-flight
                                    :control control
                                    :peers peers})
                          (finally
                            (swap! in-flight disj p)
                            (swap! (:peers-active stats) (fn [x] (max 0 (dec x))))
                            (log/debug (format "[manager] worker finished for %s (running=%d active=%d)"
                                           (peer-key p) (count @in-flight) @(:peers-active stats)))))))))

                (Thread/sleep 200)))))
        (recur)))))

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
      (println "trackers:" (or (:announce-list t) [(:announce t)]))

      (start-stats-printer! stats (:length t) pieces-total done)

      (peer-manager! {:torrent t :peer-id peer-id :port port
                      :stats stats :queue queue :done done}
                     100)

      (while (not @done)
        (Thread/sleep 200))

      (println "\nDone ->" out-path))))
