(ns app.torrent.peer.conn-test
  (:require [clojure.test :refer [deftest is testing]]
            [app.torrent.peer.conn :as pc]
            [app.torrent.peer.handshake :as hs])
  (:import [java.net ServerSocket Socket]
           [java.io InputStream OutputStream]))

(defn- read-n! ^bytes [^InputStream in n]
  (let [buf (byte-array n)]
    (loop [off 0]
      (if (= off n)
        buf
        (let [r (.read in buf off (- n off))]
          (when (neg? r)
            (throw (ex-info "EOF" {:expected n :read off})))
          (recur (+ off r)))))))

(deftest connect-and-handshake-test
  (testing "client connects and completes handshake with fake peer"
    (let [info-hash (byte-array (range 20))
          client-peer-id (byte-array (range 20 40))
          server-peer-id (byte-array (range 40 60))
          ss (ServerSocket. 0)
          port (.getLocalPort ss)
          server-fut
          (future
            (with-open [^Socket s (.accept ss)]
              (let [in (.getInputStream s)
                    out (.getOutputStream s)
                    req (read-n! in 68)]
                ;; проверим, что клиент прислал валидный handshake
                (let [{:keys [info-hash peer-id]} (hs/parse req)]
                  (is (java.util.Arrays/equals ^bytes info-hash ^bytes info-hash))
                  (is (java.util.Arrays/equals ^bytes peer-id ^bytes client-peer-id)))
                ;; ответим серверным peer-id
                (.write ^OutputStream out (hs/build info-hash server-peer-id))
                (.flush ^OutputStream out))))]
      (try
        (let [conn (pc/connect-and-handshake! {:ip "127.0.0.1" :port port}
                                              info-hash
                                              client-peer-id
                                              {:timeout-ms 2000})]
          (is (java.util.Arrays/equals ^bytes server-peer-id ^bytes (:peer-id conn)))
          (.close ^Socket (:socket conn)))
        (finally
          (.close ss)
          @server-fut)))))
