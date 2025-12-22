(ns app.torrent.peer.pipeline
  (:require [app.torrent.peer.conn :as pc]
            [app.torrent.peer.wire :as wire]
            [app.torrent.pieces.selection :as sel]
            [app.torrent.pieces.state :as st]
            [app.torrent.metainfo :as meta])
  (:import [java.net Socket]
           [java.io OutputStream]))

(defn- send! [{:keys [^OutputStream out]} ^bytes bs]
  (.write out bs)
  (.flush out))

(defn download-one-block!
  [{:keys [peer metainfo pieces-state peer-id timeout-ms]}]
  (try
    (let [info-hash (meta/info-hash metainfo)
          conn (pc/connect-and-handshake! peer info-hash peer-id {:timeout-ms (or timeout-ms 7000)})]
      (try
        ;; часто peer сначала шлёт bitfield/have, прочитаем немного "мусора"
        (dotimes [_ 20]
          (let [{:keys [type]} (pc/read-one-message! conn)]
            (when (= type :keep-alive) nil)))

        ;; теперь interested
        (pc/send-interested! conn)

        ;; ждём unchoke
        (loop [i 0]
          (when (> i 300)
            (throw (ex-info "no unchoke" {:peer peer})))
          (let [{:keys [type id]} (pc/read-one-message! conn)]
            (cond
              (= type :keep-alive) (recur (inc i))
              (and (= type :msg) (= id wire/unchoke)) :ok
              :else (recur (inc i)))))

        ;; выбираем блок
        (let [req (sel/next-block @pieces-state)]
          (when-not req
            (throw (ex-info "no blocks to request" {})))

          (swap! pieces-state st/mark-requested (:piece-idx req) (:block-idx req))
          (send! conn (wire/encode-request (:piece-idx req) (:begin req) (:len req)))

          ;; ждём piece
          (loop [i 0]
            (when (> i 500)
              (throw (ex-info "no piece response" {:peer peer :req req})))
            (let [{:keys [type id payload]} (pc/read-one-message! conn)]
              (cond
                (= type :keep-alive)
                (recur (inc i))

                (and (= type :msg) (= id wire/piece))
                (let [{:keys [piece-idx begin block]} (wire/parse-piece-payload payload)]
                  (if (and (= piece-idx (:piece-idx req))
                           (= begin (:begin req)))
                    {:ok true :req req :block block}
                    (recur (inc i))))

                :else
                (recur (inc i))))))

        (finally
          (when-let [^Socket s (:socket conn)]
            (.close s)))))
    (catch Throwable t
      {:ok false :error (or (ex-message t) (str t))})))

