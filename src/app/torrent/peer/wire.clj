(ns app.torrent.peer.wire
  (:require [app.torrent.codec.binary :as bin]))

;; message ids
(def ^:const choke 0)
(def ^:const unchoke 1)
(def ^:const interested 2)
(def ^:const not-interested 3)
(def ^:const have 4)
(def ^:const bitfield 5)
(def ^:const request 6)
(def ^:const piece 7)
(def ^:const cancel 8)
(def ^:const port 9)

(defn encode-keep-alive ^bytes []
  (bin/int32->bytes 0))

(defn encode-simple
  "id -> [len=1][id]"
  ^bytes [id]
  (byte-array (concat (seq (bin/int32->bytes 1)) [(byte id)])))

(defn encode-interested ^bytes []
  (encode-simple interested))

(defn decode-message
  "input: byte[] that starts with 4-byte length prefix.
   returns {:type ... :id ... :payload bytes} or {:type :keep-alive}"
  [^bytes frame]
  (let [len (bin/bytes->int32 frame 0)]
    (cond
      (= len 0) {:type :keep-alive}
      :else
      (let [id (bit-and (aget frame 4) 0xFF)
            payload-len (dec len)
            payload (when (pos? payload-len)
                      (java.util.Arrays/copyOfRange frame 5 (+ 5 payload-len)))]
        {:type :msg :id id :payload payload}))))

(defn encode-request
  "piece-index, begin, length -> request message"
  ^bytes [^long piece-idx ^long begin ^long len]
  (let [payload (byte-array (concat
                             (seq (bin/int32->bytes piece-idx))
                             (seq (bin/int32->bytes begin))
                             (seq (bin/int32->bytes len))))
        total-len (+ 1 (alength payload))]
    (byte-array (concat (seq (bin/int32->bytes total-len))
                        [(byte request)]
                        (seq payload)))))

(defn parse-piece-payload
  "payload of msg id=7: [index(4)][begin(4)][block(*)]"
  [^bytes payload]
  (let [idx (bin/bytes->int32 payload 0)
        begin (bin/bytes->int32 payload 4)
        block (java.util.Arrays/copyOfRange payload 8 (alength payload))]
    {:piece-idx idx :begin begin :block block}))
