(ns mini-torrent.peer
  (:import [java.net Socket InetSocketAddress]
           [java.io DataInputStream DataOutputStream]
           [java.security MessageDigest]))

(defn sha1 ^bytes [^bytes bs]
  (.digest (MessageDigest/getInstance "SHA-1") bs))

(defn bytes->hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bs)))

(defn- write-int! [^DataOutputStream out ^long v]
  (.writeInt out (int v)))

(defn- read-int! ^long [^DataInputStream in]
  (long (.readInt in)))

(defn- read-n! ^bytes [^DataInputStream in n]
  (let [buf (byte-array n)]
    (.readFully in buf)
    buf))

(defn connect
  "connect-timeout-ms: TCP connect timeout
   read-timeout-ms: SO_TIMEOUT for reads"
  ([peer connect-timeout-ms]
   (connect peer connect-timeout-ms 60000))
  ([{:keys [ip port]} connect-timeout-ms read-timeout-ms]
   (let [sock (Socket.)]
     (.connect sock (InetSocketAddress. ip (int port)) (int connect-timeout-ms))
     (.setTcpNoDelay sock true)
     (.setSoTimeout sock (int read-timeout-ms))
     sock)))

(defn send-handshake!
  [^DataOutputStream out ^bytes info-hash ^bytes peer-id]
  (.writeByte out 19)
  (.write out (.getBytes "BitTorrent protocol"))
  (.write out (byte-array 8))
  (.write out info-hash)
  (.write out peer-id)
  (.flush out))

(defn read-handshake!
  [^DataInputStream in]
  (let [pstrlen (.readUnsignedByte in)
        pstr (String. (read-n! in pstrlen))
        reserved (read-n! in 8)
        info-hash (read-n! in 20)
        peer-id (read-n! in 20)]
    {:pstr pstr :reserved reserved :info-hash info-hash :peer-id peer-id}))

(defn send-msg!
  "length-prefix (4) + id (1) + payload"
  [^DataOutputStream out ^long id ^bytes payload]
  (let [len (+ 1 (alength payload))]
    (write-int! out len)
    (.writeByte out (int id))
    (when (pos? (alength payload))
      (.write out payload))
    (.flush out)))

(defn read-msg!
  "Returns one of:
   - {:timeout true}
   - {:eof true}
   - {:keep-alive true}
   - {:id <int> :payload <bytes>}"
  [^DataInputStream in]
  (try
    (let [len (read-int! in)]
      (if (zero? len)
        {:keep-alive true}
        (let [id (.readUnsignedByte in)
              payload (read-n! in (dec len))]
          {:id id :payload payload})))
    (catch java.net.SocketTimeoutException _
      {:timeout true})
    (catch java.io.EOFException _
      {:eof true})))

(defn msg-type [id]
  (case id
    0 :choke
    1 :unchoke
    2 :interested
    3 :not-interested
    4 :have
    5 :bitfield
    6 :request
    7 :piece
    8 :cancel
    :unknown))

(defn- u8 ^long [b] (long (bit-and (int b) 0xff)))

(defn- int32-be ^long [^bytes bs ^long off]
  (let [b0 (u8 (aget bs off))
        b1 (u8 (aget bs (+ off 1)))
        b2 (u8 (aget bs (+ off 2)))
        b3 (u8 (aget bs (+ off 3)))]
    (+ (bit-shift-left b0 24)
       (bit-shift-left b1 16)
       (bit-shift-left b2 8)
       b3)))

(defn parse-have-index ^long [^bytes payload]
  (int32-be payload 0))

(defn parse-bitfield
  "bitfield payload -> boolean-array pieces-count"
  [^bytes payload pieces-count]
  (let [out (boolean-array pieces-count)]
    (dotimes [i pieces-count]
      (let [byte-idx (quot i 8)
            bit-idx  (- 7 (mod i 8))]
        (when (< byte-idx (alength payload))
          (let [b (u8 (aget payload byte-idx))]
            (aset-boolean out i (not (zero? (bit-and b (bit-shift-left 1 bit-idx)))))))))
    out))

(defn build-request-payload ^bytes [^long piece-idx ^long begin ^long length]
  (let [p (byte-array 12)]
    (doseq [[off v] [[0 piece-idx] [4 begin] [8 length]]]
      (aset-byte p (+ off 0) (unchecked-byte (bit-and (bit-shift-right v 24) 0xff)))
      (aset-byte p (+ off 1) (unchecked-byte (bit-and (bit-shift-right v 16) 0xff)))
      (aset-byte p (+ off 2) (unchecked-byte (bit-and (bit-shift-right v 8) 0xff)))
      (aset-byte p (+ off 3) (unchecked-byte (bit-and v 0xff))))
    p))

(defn parse-piece
  "payload: <index 4><begin 4><block ...>"
  [^bytes payload]
  (let [idx   (int32-be payload 0)
        begin (int32-be payload 4)
        block-len (- (alength payload) 8)
        block (byte-array block-len)]
    (System/arraycopy payload 8 block 0 block-len)
    {:index idx :begin begin :block block}))
