(ns app.torrent.tracker.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [app.torrent.tracker.response :as r]
            [app.torrent.codec.bencode :as ben]))

(deftest compact-peers-parse-test
  (testing "compact peers decode"
    (let [peers-bytes (byte-array
                       [(unchecked-byte 1) (unchecked-byte 2) (unchecked-byte 3) (unchecked-byte 4)
                        (unchecked-byte 0x1A) (unchecked-byte 0xE1)          ;; 6881 = 0x1AE1
                        (unchecked-byte 127) (unchecked-byte 0) (unchecked-byte 0) (unchecked-byte 1)
                        (unchecked-byte 0xC8) (unchecked-byte 0xD5)])        ;; 51413 = 0xC8D5
          decoded (ben/decode (ben/encode {"interval" 1800
                                           "peers" peers-bytes}))
          parsed (r/parse-response decoded)]
      (is (= 1800 (:interval parsed)))
      (is (= [{:ip "1.2.3.4" :port 6881}
              {:ip "127.0.0.1" :port 51413}]
             (:peers parsed))))))
