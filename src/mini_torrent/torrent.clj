(ns mini-torrent.torrent
  (:require [mini-torrent.bencode :as ben])
  (:import [java.io File]
           [java.nio.file Files]
           [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(defn sha1 ^bytes [^bytes bs]
  (.digest (MessageDigest/getInstance "SHA-1") bs))

(defn bytes->hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- bstr->utf8 [^bytes b]
  (String. b StandardCharsets/UTF_8))

(defn load-torrent
  "Single-file torrent v1. pieces = byte-string of 20-byte SHA1 hashes."
  [torrent-path]
  (let [bs (Files/readAllBytes (.toPath (File. torrent-path)))
        {:keys [meta info-bytes]} (ben/decode-torrent bs)
        info-hash (sha1 info-bytes)
        announce  (some-> (get meta "announce") bstr->utf8)
        info      (get meta "info")
        name      (some-> (get info "name") bstr->utf8)
        piece-length (long (get info "piece length"))
        length    (long (get info "length"))
        pieces-bytes ^bytes (get info "pieces")]
    (when-not (and announce name piece-length length pieces-bytes)
      (throw (ex-info "Missing required fields" {:have (keys info)})))

    (when-not (zero? (mod (alength pieces-bytes) 20))
      (throw (ex-info "Bad pieces field length" {:len (alength pieces-bytes)})))

    (let [n (quot (alength pieces-bytes) 20)
          piece-hashes
          (vec
           (for [i (range n)]
             (let [h (byte-array 20)]
               (System/arraycopy pieces-bytes (* i 20) h 0 20)
               h)))]
      {:announce announce
       :name name
       :piece-length piece-length
       :length length
       :piece-hashes piece-hashes
       :info-hash info-hash
       :info-hash-hex (bytes->hex info-hash)})))
