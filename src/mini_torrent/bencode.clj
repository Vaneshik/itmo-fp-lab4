(ns mini-torrent.bencode
  (:import [java.nio.charset StandardCharsets]))

(defn- digit? [b]
  (let [x (bit-and (int b) 0xff)]
    (<= (int \0) x (int \9))))


(defn- parse-int
  "Парсит i<num>e, возвращает [long new-idx]"
  [^bytes bs ^long i0]
  (loop [i (inc i0) sign 1 acc 0]
    (let [b (aget bs i)]
      (cond
        (= b (byte \-)) (recur (inc i) -1 acc)
        (= b (byte \e)) [(* sign acc) (inc i)]
        :else (recur (inc i) sign (+ (* acc 10) (- b (byte \0))))))))

(defn- parse-bytestring
  "Парсит <len>:<bytes>, возвращает [byte[] new-idx]"
  [^bytes bs ^long i0]
  (loop [i i0 len 0]
    (let [b (aget bs i)]
      (if (= b (byte \:))
        (let [start (inc i)
              end (+ start len)
              out (byte-array len)]
          (System/arraycopy bs start out 0 len)
          [out end])
        (recur (inc i) (+ (* len 10) (- b (byte \0))))))))

(declare decode*)

(defn- parse-list [^bytes bs ^long i0]
  (loop [i (inc i0) acc []]
    (let [b (aget bs i)]
      (if (= b (byte \e))
        [acc (inc i)]
        (let [[v j] (decode* bs i)]
          (recur j (conj acc v)))))))

(defn- parse-dict
  "Возвращает [map new-idx info-range]
   info-range = [start end] байтов подстроки, если встретили ключ \"info\""
  [^bytes bs ^long i0]
  (loop [i (inc i0) m {} info-range nil]
    (let [b (aget bs i)]
      (if (= b (byte \e))
        [m (inc i) info-range]
        (let [[kbytes j] (parse-bytestring bs i)
              k (String. ^bytes kbytes StandardCharsets/UTF_8)]
          (if (= k "info")
            (let [info-start j
                  [v j2] (decode* bs j)
                  info-end j2]
              (recur j2 (assoc m k v) [info-start info-end]))
            (let [[v j2] (decode* bs j)]
              (recur j2 (assoc m k v) info-range))))))))

(defn decode*
  "Возвращает [value new-idx] для любого bencode-типа."
  [^bytes bs ^long i]
  (let [b (aget bs i)]
    (cond
      (= b (byte \i)) (parse-int bs i)
      (= b (byte \l)) (parse-list bs i)
      (= b (byte \d)) (let [[m j _] (parse-dict bs i)] [m j])
      (digit? b)      (parse-bytestring bs i)
      :else (throw (ex-info "Bad bencode" {:idx i :byte b})))))

(defn decode-torrent
  "Декодит корневой dict и возвращает:
   {:meta <map> :info-bytes <byte[]>}
   где :info-bytes — точные байты bencoded info словаря."
  [^bytes bs]
  (let [b0 (aget bs 0)]
    (when-not (= b0 (byte \d))
      (throw (ex-info "Torrent must start with dict" {})))
    (let [[m j info-range] (parse-dict bs 0)]
      (when-not (= j (alength bs))
        ;; допускаем хвост? обычно нет
        )
      (when-not info-range
        (throw (ex-info "No info dict found" {})))
      (let [[s e] info-range
            out (byte-array (- e s))]
        (System/arraycopy bs s out 0 (- e s))
        {:meta m :info-bytes out}))))
