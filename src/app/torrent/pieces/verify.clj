(ns app.torrent.pieces.verify
  (:import [java.security MessageDigest]))

(defn sha1 ^bytes [^bytes bs]
  (.digest (MessageDigest/getInstance "SHA-1") bs))

(defn piece-ok?
  "Сравнивает SHA1(piece-bytes) с expected 20-byte hash."
  [^bytes piece-bytes ^bytes expected-sha1]
  (java.util.Arrays/equals (sha1 piece-bytes) expected-sha1))
