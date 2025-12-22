(ns app.torrent.peer.conn
  (:require [app.torrent.codec.binary :as bin]
            [app.torrent.peer.handshake :as hs]
            [app.torrent.peer.wire :as wire])
  (:import [java.net Socket InetSocketAddress]
           [java.io InputStream OutputStream]))

(defn- read-n!
  "Reads exactly n bytes or throws."
  ^bytes [^InputStream in n]
  (let [buf (byte-array n)]
    (loop [off 0]
      (if (= off n)
        buf
        (let [r (.read in buf off (- n off))]
          (when (neg? r)
            (throw (ex-info "EOF" {:expected n :read off})))
          (recur (+ off r)))))))

(defn- read-frame!
  "Reads [len(4)][payload(len)] and returns full frame bytes (incl len prefix)."
  ^bytes [^InputStream in]
  (let [len-bytes (read-n! in 4)
        len (bin/bytes->int32 len-bytes 0)]
    (if (= len 0)
      len-bytes
      (let [payload (read-n! in len)]
        (byte-array (concat (seq len-bytes) (seq payload)))))))

(defn connect-and-handshake!
  "peer {:ip \"1.2.3.4\" :port 6881}
   returns {:socket sock :in in :out out :peer-id bytes}"
  [{:keys [ip port]} ^bytes info-hash ^bytes peer-id {:keys [timeout-ms] :or {timeout-ms 7000}}]
  (let [sock (Socket.)]
    (.connect sock (InetSocketAddress. ^String ip (int port)) (int timeout-ms))
    (.setSoTimeout sock (int timeout-ms))
    (let [in (.getInputStream sock)
          out (.getOutputStream sock)
          hs-bytes (hs/build info-hash peer-id)]
      (.write ^OutputStream out hs-bytes)
      (.flush ^OutputStream out)
      (let [resp (read-n! in 68)
            {:keys [peer-id]} (hs/parse resp)]
        {:socket sock :in in :out out :peer-id peer-id}))))

(defn send-interested! [{:keys [^OutputStream out]}]
  (.write out (wire/encode-interested))
  (.flush out))

(defn read-one-message! [{:keys [^InputStream in]}]
  (wire/decode-message (read-frame! in)))
