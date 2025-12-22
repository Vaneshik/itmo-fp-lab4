(ns app.torrent.codec.bencode
  (:import [java.io ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]))

;; -----------------------
;; decode
;; -----------------------

(defn- b->char ^Character [^long b]
  (char (bit-and b 0xFF)))

(defn- digit? [^long b]
  (<= (int \0) (int (b->char b)) (int \9)))

(defn- parse-long-from-bytes
  "Парсит long из ASCII байт в диапазоне [start,end) (end не включительно)."
  ^long [^bytes bs ^long start ^long end]
  (let [neg? (= (aget bs start) (byte (int \-)))
        i0 (if neg? (inc start) start)]
    (loop [i i0 acc 0]
      (if (= i end)
        (if neg? (- acc) acc)
        (let [c (aget bs i)
              d (- (int (b->char c)) (int \0))]
          (recur (inc i) (+ (* acc 10) d)))))))

(defn- decode*
  [^bytes bs ^long idx]
  (let [b (aget bs idx)]
    (cond
      ;; integer: i<digits>e
      (= b (byte (int \i)))
      (let [j (loop [k (inc idx)]
                (if (= (aget bs k) (byte (int \e))) k (recur (inc k))))
            n (parse-long-from-bytes bs (inc idx) j)]
        [n (inc j)])

      ;; list: l ... e
      (= b (byte (int \l)))
      (loop [k (inc idx) acc []]
        (if (= (aget bs k) (byte (int \e)))
          [acc (inc k)]
          (let [[v k2] (decode* bs k)]
            (recur k2 (conj acc v)))))

      ;; dict: d ... e (ключи — byte strings, возвращаем ключи как String)
      (= b (byte (int \d)))
      (loop [k (inc idx) m {}]
        (if (= (aget bs k) (byte (int \e)))
          [m (inc k)]
          (let [[kraw k2] (decode* bs k)
                ;; ключ в bencode — строка, значит kraw будет byte[]
                kstr (String. ^bytes kraw StandardCharsets/UTF_8)
                [v k3] (decode* bs k2)]
            (recur k3 (assoc m kstr v)))))

      ;; byte string: <len>:<bytes>
      (digit? b)
      (let [colon (loop [k idx]
                    (if (= (aget bs k) (byte (int \:))) k (recur (inc k))))
            len (parse-long-from-bytes bs idx colon)
            start (inc colon)
            end (+ start len)
            out (java.util.Arrays/copyOfRange bs start end)]
        [out end])

      :else
      (throw (ex-info "Invalid bencode at index"
                      {:idx idx :byte (int (bit-and b 0xFF))})))))

(defn decode
  "Декодит bencode из byte[]. Строки возвращаются как byte[], списки как vector, словари как map с String ключами."
  [^bytes bs]
  (let [[v idx] (decode* bs 0)]
    (when (not= idx (alength bs))
      ;; допустим, но обычно значит мусор в конце
      v)
    v))

;; -----------------------
;; encode
;; -----------------------

(defn- ->bytes ^bytes [x]
  (cond
    (nil? x) (throw (ex-info "Cannot bencode nil" {}))
    (instance? (Class/forName "[B") x) x
    (string? x) (.getBytes ^String x StandardCharsets/UTF_8)
    (keyword? x) (.getBytes (name x) StandardCharsets/UTF_8)
    :else (throw (ex-info "Unsupported bencode string type" {:type (type x)}))))

(defn- write-bytes! [^ByteArrayOutputStream out ^bytes bs]
  (.write out bs 0 (alength bs)))

(defn- write-ascii! [^ByteArrayOutputStream out ^String s]
  (write-bytes! out (.getBytes s StandardCharsets/UTF_8)))

(defn- cmp-byte-arrays
  "Лексикографическое сравнение byte[] по unsigned байтам."
  [^bytes a ^bytes b]
  (let [la (alength a)
        lb (alength b)
        l (min la lb)]
    (loop [i 0]
      (if (= i l)
        (compare la lb)
        (let [aa (bit-and (aget a i) 0xFF)
              bb (bit-and (aget b i) 0xFF)]
          (if (= aa bb)
            (recur (inc i))
            (compare aa bb)))))))

(declare encode-into!)

(defn- encode-int! [^ByteArrayOutputStream out n]
  (write-ascii! out "i")
  (write-ascii! out (str (long n)))
  (write-ascii! out "e"))

(defn- encode-bytes! [^ByteArrayOutputStream out ^bytes bs]
  (write-ascii! out (str (alength bs)))
  (write-ascii! out ":")
  (write-bytes! out bs))

(defn- encode-list! [^ByteArrayOutputStream out xs]
  (write-ascii! out "l")
  (doseq [v xs] (encode-into! out v))
  (write-ascii! out "e"))

(defn- encode-map! [^ByteArrayOutputStream out m]
  (write-ascii! out "d")
  ;; сортируем по байтовому представлению ключа
  (let [entries (->> m
                     (map (fn [[k v]] [(->bytes k) v]))
                     (sort (fn [[ka _] [kb _]] (neg? (cmp-byte-arrays ka kb)))))]
    (doseq [[kbytes v] entries]
      (encode-bytes! out kbytes)
      (encode-into! out v)))
  (write-ascii! out "e"))

(defn- encode-into! [^ByteArrayOutputStream out x]
  (cond
    (integer? x) (encode-int! out x)
    (number? x) (encode-int! out (long x))

    (instance? (Class/forName "[B") x) (encode-bytes! out x)
    (string? x) (encode-bytes! out (->bytes x))
    (keyword? x) (encode-bytes! out (->bytes x))

    (map? x) (encode-map! out x)
    (sequential? x) (encode-list! out x)

    :else (throw (ex-info "Unsupported bencode type" {:type (type x)}))))

(defn encode
  "Кодирует Clojure данные в bencode byte[].
  Поддержка: numbers, byte[], String/keyword (как byte string), sequential (list), map (dict)."
  ^bytes [x]
  (let [out (ByteArrayOutputStream.)]
    (encode-into! out x)
    (.toByteArray out)))
