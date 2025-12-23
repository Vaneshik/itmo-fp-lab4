(ns mini-torrent.tracker
  (:require [mini-torrent.bencode :as ben])
  (:import [java.net URL HttpURLConnection]
           [java.io ByteArrayOutputStream]
           [java.util Random]
           [java.nio.charset StandardCharsets]))

(defn- read-all-bytes ^bytes [^java.io.InputStream in]
  (with-open [in in
              out (ByteArrayOutputStream.)]
    (let [buf (byte-array 8192)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.write out buf 0 n)
            (recur))))
      (.toByteArray out))))

(defn random-peer-id ^bytes []
  ;; 20 байт. Можно “-CLJ0001-” + random
  (let [prefix (.getBytes "-CLJ0001-" StandardCharsets/UTF_8)
        out (byte-array 20)
        rnd (Random.)]
    (System/arraycopy prefix 0 out 0 (alength prefix))
    (dotimes [i (- 20 (alength prefix))]
      (aset-byte out (+ i (alength prefix))
                 (unchecked-byte (.nextInt rnd 256))))
    out))

(defn- urlenc-bytes
  "Побайтовый percent-encoding для info_hash/peer_id (они бинарные!)."
  [^bytes bs]
  (apply str
         (map (fn [b] (format "%%%02X" (bit-and b 0xff))) bs)))

(defn- parse-compact-peers
  "peers field как byte-string: по 6 байт на peer (ipv4+port)."
  [^bytes peers]
  (when-not (zero? (mod (alength peers) 6))
    (throw (ex-info "Bad compact peers length" {:len (alength peers)})))
  (vec
   (for [i (range 0 (alength peers) 6)]
     (let [a (bit-and (aget peers i) 0xff)
           b (bit-and (aget peers (+ i 1)) 0xff)
           c (bit-and (aget peers (+ i 2)) 0xff)
           d (bit-and (aget peers (+ i 3)) 0xff)
           p1 (bit-and (aget peers (+ i 4)) 0xff)
           p2 (bit-and (aget peers (+ i 5)) 0xff)
           port (+ (* p1 256) p2)]
       {:ip (str a "." b "." c "." d)
        :port port}))))

(defn announce
  "Делает GET на tracker и возвращает {:interval n :peers [...]}
   uploaded/downloaded/left — числа в байтах."
  [{:keys [announce info-hash peer-id port uploaded downloaded left numwant event]}]
  (let [numwant (or numwant 80)
        q (str "?info_hash=" (urlenc-bytes info-hash)
               "&peer_id="   (urlenc-bytes peer-id)
               "&port=" port
               "&uploaded=" uploaded
               "&downloaded=" downloaded
               "&left=" left
               "&compact=1"
               "&numwant=" numwant
               (when (seq event) (str "&event=" event)))
        url (URL. (str announce q))
        ^HttpURLConnection conn (.openConnection url)]
    (.setRequestMethod conn "GET")
    (.setConnectTimeout conn 8000)
    (.setReadTimeout conn 8000)
    (let [code (.getResponseCode conn)
          body (read-all-bytes (if (<= 200 code 299)
                                 (.getInputStream conn)
                                 (.getErrorStream conn)))
          meta (first (ben/decode* body 0))]
      (when-let [fail (get meta "failure reason")]
        (throw (ex-info "Tracker failure"
                        {:reason (String. ^bytes fail StandardCharsets/UTF_8)})))
      (let [interval (long (get meta "interval" 120))
            peers (get meta "peers")]
        {:interval interval
         :peers (cond
                  (instance? (Class/forName "[B") peers) (parse-compact-peers peers)
                  :else (throw (ex-info "Non-compact peers not supported" {})))}))))

