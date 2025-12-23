(ns mini-torrent.bytes
  (:import [java.security MessageDigest]))

(def ^:private ^:const byte-array-class
  ;; JVM class for byte[]
  (Class/forName "[B"))

(defn bytes?
  "True если x — byte[] (массив примитивных байтов)."
  [x]
  (instance? byte-array-class x))

(defn ubyte
  "Преобразует signed byte (или число) в 0..255 int."
  [b]
  (bit-and (int b) 0xff))

(defn sha1
  "SHA-1 от массива байтов. Возвращает byte[]."
  ^bytes
  [^bytes bs]
  (.digest (MessageDigest/getInstance "SHA-1") bs))

(defn bytes->hex
  "Печатает байты в hex (нижний регистр), удобно для логов."
  [^bytes bs]
  (apply str (map #(format "%02x" (ubyte %)) bs)))
