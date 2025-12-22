(ns app.torrent.metainfo
  (:require [clojure.java.io :as io]
            [app.torrent.codec.bencode :as ben])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(defn- sha1 ^bytes [^bytes bs]
  (.digest (MessageDigest/getInstance "SHA-1") bs))

(defn parse-torrent-bytes
  "Декодит .torrent содержимое из byte[]"
  [^bytes bs]
  (ben/decode bs))

(defn parse-torrent-file
  "Декодит .torrent по пути"
  [path]
  (with-open [in (io/input-stream path)]
    (parse-torrent-bytes (.readAllBytes in))))

(defn info-map [metainfo]
  (get metainfo "info"))

(defn info-hash
  "info_hash = SHA1(bencode(info))"
  ^bytes [metainfo]
  (sha1 (ben/encode (info-map metainfo))))

(defn- as-utf8-string [^bytes bs]
  (String. bs StandardCharsets/UTF_8))

(defn torrent-name [metainfo]
  (-> metainfo info-map (get "name") as-utf8-string))

(defn piece-length [metainfo]
  (long (get (info-map metainfo) "piece length")))

(defn total-bytes [metainfo]
  (let [info (info-map metainfo)]
    (if-let [len (get info "length")]
      (long len)
      ;; multi-file: "files" = list of dicts with "length"
      (reduce + (map (comp long #(get % "length")) (get info "files"))))))

(defn pieces-bytes
  "Raw bytes of info->pieces (20*N)."
  ^bytes [metainfo]
  (get (info-map metainfo) "pieces"))

(defn piece-hash
  "Returns 20-byte expected SHA1 for piece idx."
  ^bytes [metainfo piece-idx]
  (let [^bytes ps (pieces-bytes metainfo)
        off (* 20 (long piece-idx))]
    (when (> (+ off 20) (alength ps))
      (throw (ex-info "piece idx out of range" {:piece-idx piece-idx})))
    (java.util.Arrays/copyOfRange ps off (+ off 20))))

(defn pieces-count [metainfo]
  (let [^bytes bs (pieces-bytes metainfo)]
    (quot (alength bs) 20)))

(defn announce-bytes ^bytes [metainfo]
  (get metainfo "announce"))

(defn- bytes->str [x]
  (cond
    (nil? x) nil
    (string? x) x
    (bytes? x) (String. ^bytes x StandardCharsets/UTF_8)
    :else (str x)))

(defn announce-url [metainfo]
  (or (get metainfo "announce")
      (get metainfo :announce)))

(defn announce-urls
  "Возвращает список announce URL:
   - metainfo[announce]
   - metainfo[announce-list] (tiered list)"
  [metainfo]
  (let [a (announce-url metainfo)
        al (or (get metainfo "announce-list")
               (get metainfo :announce-list)
               (get metainfo "announce_list")
               (get metainfo :announce_list))
        tier-urls (when (sequential? al)
                    (mapcat (fn [tier]
                              (cond
                                (sequential? tier) tier
                                (nil? tier) []
                                :else [tier]))
                            al))]
    (->> (concat (when a [a]) (or tier-urls []))
         (remove nil?)
         (map bytes->str)
         (remove #(= "" %))
         distinct
         vec)))