(ns mini-torrent.peer
  (:import [java.net Socket InetSocketAddress SocketTimeoutException]
           [java.io DataInputStream DataOutputStream EOFException]))

(defn- write-int! [^DataOutputStream out ^long v]
  (.writeInt out (int v)))

(defn- read-int! ^long [^DataInputStream in]
  (long (.readInt in)))

(defn- read-n! ^bytes [^DataInputStream in n]
  (let [buf (byte-array n)]
    (.readFully in buf)
    buf))

(defn- u8 ^long [b]
  (long (bit-and (int b) 0xff)))

(defn- aset-int32-be!
  "Записать int32 big-endian в byte-array."
  [^bytes out ^long off ^long v]
  (let [x (long v)]
    (aset-byte out (int off)       (unchecked-byte (bit-and (bit-shift-right x 24) 0xff)))
    (aset-byte out (int (inc off)) (unchecked-byte (bit-and (bit-shift-right x 16) 0xff)))
    (aset-byte out (int (+ off 2)) (unchecked-byte (bit-and (bit-shift-right x 8) 0xff)))
    (aset-byte out (int (+ off 3)) (unchecked-byte (bit-and x 0xff))))
  out)

(defn- int32-be ^long [^bytes bs ^long off]
  (let [b0 (u8 (aget bs (int off)))
        b1 (u8 (aget bs (int (inc off))))
        b2 (u8 (aget bs (int (+ off 2))))
        b3 (u8 (aget bs (int (+ off 3))))]
    (long (bit-or (bit-shift-left b0 24)
                  (bit-shift-left b1 16)
                  (bit-shift-left b2 8)
                  b3))))

(defn msg-type
  "Преобразует numeric msg id в keyword."
  [^long id]
  (case (long id)
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

(defn connect
  "peer = {:ip \"1.2.3.4\" :port 6881}
   connect-timeout-ms: connect timeout
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
  (.write out (byte-array 8)) ;; reserved
  (.write out info-hash)
  (.write out peer-id)
  (.flush out))

(defn read-handshake!
  [^DataInputStream in]
  (let [pstrlen (.readUnsignedByte in)
        pstr (String. (read-n! in pstrlen))
        _reserved (read-n! in 8)
        info-hash (read-n! in 20)
        peer-id (read-n! in 20)]
    {:pstr pstr :info-hash info-hash :peer-id peer-id}))

(defn send-msg!
  "Отправляет message: <len:4><id:1><payload:len-1>."
  [^DataOutputStream out ^long id ^bytes payload]
  (let [len (+ 1 (alength payload))]
    (write-int! out len)
    (.writeByte out (int id))
    (when (pos? (alength payload))
      (.write out payload))
    (.flush out)))

(defn send-keepalive! [^DataOutputStream out]
  (write-int! out 0)
  (.flush out))

(defn read-msg!
  "Читает length-prefixed сообщение.
   Возвращает одну из форм:
   {:timeout true}
   {:eof true}
   {:keep-alive true}
   {:id <long> :payload <bytes>}  ;; payload включает первый байт id."
  [^DataInputStream in]
  (try
    (let [len (read-int! in)]
      (cond
        (zero? len) {:keep-alive true}
        :else
        (let [payload (read-n! in len)
              id (u8 (aget payload 0))]
          {:id id :payload payload})))
    (catch SocketTimeoutException _
      {:timeout true})
    (catch EOFException _
      {:eof true})))

;; --- payload builders/parsers used by core ---------------------------------

(defn build-request-payload
  "Payload для request (id=6): <index:4><begin:4><length:4>."
  [^long index ^long begin ^long length]
  (doto (byte-array 12)
    (aset-int32-be! 0 index)
    (aset-int32-be! 4 begin)
    (aset-int32-be! 8 length)))

(defn parse-have-index
  "payload (включая id в payload[0]) -> index."
  [^bytes payload]
  (int32-be payload 1))

(defn parse-piece
  "payload (включая id в payload[0]) -> {:index .. :begin .. :block byte[]}."
  [^bytes payload]
  (let [idx (int32-be payload 1)
        begin (int32-be payload 5)
        block-len (- (alength payload) 9)
        block (byte-array (max 0 block-len))]
    (when (pos? block-len)
      (System/arraycopy payload 9 block 0 block-len))
    {:index idx :begin begin :block block}))

(defn parse-bitfield
  "payload (включая id) -> boolean-array длины pieces-count."
  [^bytes payload pieces-count]
  (let [bf-len (dec (alength payload))
        bf (byte-array (max 0 bf-len))]
    (when (pos? bf-len)
      (System/arraycopy payload 1 bf 0 bf-len))
    (let [have (boolean-array pieces-count)]
      (dotimes [i pieces-count]
        (let [byte-idx (quot i 8)
              bit-idx (- 7 (mod i 8))]
          (when (< byte-idx bf-len)
            (aset-boolean have i
                          (pos? (bit-and (u8 (aget bf byte-idx))
                                         (bit-shift-left 1 bit-idx)))))))
      have)))
