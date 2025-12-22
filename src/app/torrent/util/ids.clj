(ns app.torrent.util.ids
  (:import [java.security SecureRandom]
           [java.nio.charset StandardCharsets]))

(def ^SecureRandom rng (SecureRandom.))

(defn peer-id-bytes
  "prefix: строка типа \"-CLJ001-\". Возвращает ровно 20 байт."
  [^String prefix]
  (let [pref-bytes (.getBytes prefix StandardCharsets/UTF_8)
        out (byte-array 20)]
    (when (> (alength pref-bytes) 20)
      (throw (ex-info "peer-id prefix too long" {:prefix prefix})))
    (System/arraycopy pref-bytes 0 out 0 (alength pref-bytes))
    (dotimes [i (- 20 (alength pref-bytes))]
      ;; заполняем остаток ASCII цифрами
      (aset-byte out (+ (alength pref-bytes) i)
                 (byte (+ (int \0) (.nextInt rng 10)))))
    out))
