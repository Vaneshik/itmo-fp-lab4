(ns runner
  (:require [clojure.test :as t]
            [mini-torrent.bytes-test]
            [mini-torrent.bencode-test]
            [mini-torrent.torrent-test]
            [mini-torrent.peer-test]
            [mini-torrent.tracker-test]
            [mini-torrent.session-test]
            [mini-torrent.http-api-test]))

(defn -main [& _]
  (let [res (t/run-tests
             'mini-torrent.bytes-test
             'mini-torrent.bencode-test
             'mini-torrent.torrent-test
             'mini-torrent.peer-test
             'mini-torrent.tracker-test
             'mini-torrent.session-test
             'mini-torrent.http-api-test)]
    (System/exit (if (t/successful? res) 0 1))))