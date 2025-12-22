(ns app.torrent.pieces.verify-test
  (:require [clojure.test :refer [deftest is testing]]
            [app.torrent.pieces.verify :as v])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(defn sha1 ^bytes [^bytes bs]
  (.digest (MessageDigest/getInstance "SHA-1") bs))

(deftest piece-ok-test
  (testing "piece-ok? matches correct sha1"
    (let [data (.getBytes "hello" StandardCharsets/UTF_8)
          exp (sha1 data)]
      (is (true? (v/piece-ok? data exp)))
      (let [bad (byte-array exp)]
        (aset-byte bad 0 (byte (bit-xor (aget bad 0) 0x01)))
        (is (false? (v/piece-ok? data bad)))))))
