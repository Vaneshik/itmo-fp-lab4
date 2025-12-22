(ns app.torrent.tracker.response
  (:require [app.torrent.codec.bencode :as bencode])
  (:import [java.nio.charset StandardCharsets]))

(defn- u8 ^long [b]
  (bit-and (int b) 0xFF))

(defn- bytes->str [x]
  (cond
    (nil? x) nil
    (string? x) x
    (bytes? x) (String. ^bytes x StandardCharsets/UTF_8)
    :else (str x)))

(defn- parse-compact-peers
  "peers bytes: 6*N where each entry = ip(4) + port(2)"
  [^bytes bs]
  (let [n (quot (alength bs) 6)]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [off (* i 6)
              a (u8 (aget bs off))
              b (u8 (aget bs (+ off 1)))
              c (u8 (aget bs (+ off 2)))
              d (u8 (aget bs (+ off 3)))
              p1 (u8 (aget bs (+ off 4)))
              p2 (u8 (aget bs (+ off 5)))
              ip (str a "." b "." c "." d)
              port (+ (bit-shift-left p1 8) p2)]
          (recur (inc i) (conj acc {:ip ip :port port})))))))

(defn- parse-dict-peers
  "peers as list of dicts: [{\"ip\" .. \"port\" ..} ...]"
  [peers]
  (->> peers
       (keep (fn [m]
               (when (map? m)
                 (let [ip (bytes->str (get m "ip"))
                       port (get m "port")]
                   (when (and ip port)
                     {:ip ip :port (long port)})))))
       vec))

(defn parse-response
  "Takes raw HTTP body bytes from tracker (bencoded).
   Returns {:interval long :peers [{:ip .. :port ..} ...]} or throws on failure."
  [^bytes body]
  (let [m (bencode/decode body)]
    (when-let [reason (or (get m "failure reason") (get m "failure_reason"))]
      (throw (ex-info "Tracker failure" {:reason (bytes->str reason)})))

    (let [interval (long (or (get m "interval") 1800))
          peers-val (get m "peers")
          peers (cond
                  (bytes? peers-val) (parse-compact-peers peers-val)
                  (sequential? peers-val) (parse-dict-peers peers-val)
                  :else [])]
      {:interval interval
       :peers peers
       ;; иногда полезно:
       :complete (when (contains? m "complete") (long (get m "complete")))
       :incomplete (when (contains? m "incomplete") (long (get m "incomplete")))})))
