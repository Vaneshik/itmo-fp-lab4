(ns app.torrent.peer.handshake-test
  (:require [clojure.test :refer [deftest is testing]]
            [app.torrent.peer.handshake :as hs]))

(deftest handshake-build-parse-test
  (testing "handshake build/parse roundtrip"
    (let [info-hash (byte-array (range 20))
          peer-id (byte-array (range 20 40))
          msg (hs/build info-hash peer-id)
          parsed (hs/parse msg)]
      (is (= 68 (alength ^bytes msg)))
      (is (java.util.Arrays/equals ^bytes info-hash ^bytes (:info-hash parsed)))
      (is (java.util.Arrays/equals ^bytes peer-id ^bytes (:peer-id parsed))))))
