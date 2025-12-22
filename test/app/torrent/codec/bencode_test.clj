(ns app.torrent.codec.bencode-test
  (:require [clojure.test :refer [deftest is testing]]
            [app.torrent.codec.bencode :as ben])
  (:import [java.nio.charset StandardCharsets]))

(defn utf8-bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(deftest decode-integer-test
  (testing "decode integer"
    (is (= 42 (ben/decode (utf8-bytes "i42e"))))
    (is (= -7 (ben/decode (utf8-bytes "i-7e"))))))

(deftest decode-bytestring-test
  (testing "decode byte string returns byte[]"
    (let [v (ben/decode (utf8-bytes "4:spam"))]
      (is (instance? (Class/forName "[B") v))
      (is (= "spam" (String. ^bytes v StandardCharsets/UTF_8))))))

(deftest decode-list-test
  (testing "decode list"
    (let [v (ben/decode (utf8-bytes "l4:spam4:eggse"))]
      (is (= 2 (count v)))
      (is (= "spam" (String. ^bytes (first v) StandardCharsets/UTF_8)))
      (is (= "eggs" (String. ^bytes (second v) StandardCharsets/UTF_8))))))

(deftest decode-dict-test
  (testing "decode dict keys are strings"
    (let [m (ben/decode (utf8-bytes "d3:cow3:moo4:spam4:eggse"))]
      (is (= #{"cow" "spam"} (set (keys m))))
      (is (= "moo" (String. ^bytes (get m "cow") StandardCharsets/UTF_8)))
      (is (= "eggs" (String. ^bytes (get m "spam") StandardCharsets/UTF_8))))))

(deftest encode-roundtrip-test
  (testing "encode+decode roundtrip for basic structures"
    (let [data {"a" 1
                "b" [(utf8-bytes "x") (utf8-bytes "y")]
                "c" {"z" (utf8-bytes "ok")}}
          encoded (ben/encode data)
          decoded (ben/decode encoded)]
      (is (= 1 (get decoded "a")))
      (is (= "x" (String. ^bytes (get (get decoded "b") 0) StandardCharsets/UTF_8)))
      (is (= "ok" (String. ^bytes (get-in decoded ["c" "z"]) StandardCharsets/UTF_8))))))

(deftest encode-map-keys-sorted-test
  (testing "bencode dict keys are sorted lexicographically"
    ;; В bencode словарь обязан быть с отсортированными ключами.
    (let [encoded (String. ^bytes (ben/encode {"b" 1 "a" 2}) StandardCharsets/UTF_8)]
      ;; должно быть d1:a i2e 1:b i1e e
      (is (= "d1:ai2e1:bi1ee" encoded)))))
