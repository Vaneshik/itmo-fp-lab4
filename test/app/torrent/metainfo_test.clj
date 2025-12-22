(ns app.torrent.metainfo-test
  (:require [clojure.test :refer [deftest is testing]]
            [app.torrent.codec.bencode :as ben]
            [app.torrent.metainfo :as meta])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(defn sha1 ^bytes [^bytes bs]
  (.digest (MessageDigest/getInstance "SHA-1") bs))

(defn utf8-bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(deftest info-hash-test
  (testing "info_hash is SHA1(bencode(info))"
    (let [pieces (byte-array 20) ;; один piece hash (20 байт)
          info {"name" (utf8-bytes "file.txt")
                "piece length" 16384
                "length" 123
                "pieces" pieces}
          metainfo {"announce" (utf8-bytes "http://tracker/announce")
                    "info" info}
          expected (sha1 (ben/encode info))
          actual (meta/info-hash metainfo)]
      (is (= 20 (alength ^bytes actual)))
      (is (java.util.Arrays/equals ^bytes expected ^bytes actual)))))

(deftest extract-fields-test
  (testing "extract name/total/piece-length/pieces-count"
    (let [pieces (byte-array 40) ;; два piece hash (2*20)
          info {"name" (utf8-bytes "linux.iso")
                "piece length" 262144
                "length" 1000
                "pieces" pieces}
          metainfo {"info" info}]
      (is (= "linux.iso" (meta/torrent-name metainfo)))
      (is (= 1000 (meta/total-bytes metainfo)))
      (is (= 262144 (meta/piece-length metainfo)))
      (is (= 2 (meta/pieces-count metainfo))))))
