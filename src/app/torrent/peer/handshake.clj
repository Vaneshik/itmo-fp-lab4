(ns app.torrent.peer.handshake
  (:require [app.torrent.codec.binary :as bin])
  (:import [java.nio.charset StandardCharsets]))

(def pstr "BitTorrent protocol")

(defn build
  "info-hash: 20 bytes, peer-id: 20 bytes"
  ^bytes [^bytes info-hash ^bytes peer-id]
  (let [pstr-bytes (.getBytes pstr StandardCharsets/ISO_8859_1)
        buf (byte-array (+ 1 (alength pstr-bytes) 8 20 20))]
    (aset-byte buf 0 (byte (alength pstr-bytes)))
    (System/arraycopy pstr-bytes 0 buf 1 (alength pstr-bytes))
    ;; reserved 8 bytes already 0
    (System/arraycopy info-hash 0 buf (+ 1 (alength pstr-bytes) 8) 20)
    (System/arraycopy peer-id 0 buf (+ 1 (alength pstr-bytes) 8 20) 20)
    buf))

(defn parse
  "returns {:info-hash bytes :peer-id bytes} or throws"
  [^bytes hs]
  (let [len (bit-and (aget hs 0) 0xFF)
        pstr-bytes (java.util.Arrays/copyOfRange hs 1 (+ 1 len))
        pstr* (String. ^bytes pstr-bytes StandardCharsets/ISO_8859_1)]
    (when (not= pstr pstr*)
      (throw (ex-info "Invalid handshake pstr" {:pstr pstr*})))
    (let [info-off (+ 1 len 8)
          peer-off (+ info-off 20)
          info-hash (java.util.Arrays/copyOfRange hs info-off (+ info-off 20))
          peer-id (java.util.Arrays/copyOfRange hs peer-off (+ peer-off 20))]
      {:info-hash info-hash :peer-id peer-id})))
