(ns app.torrent.session.model
  (:import [java.time Instant]))

(defn now-ms []
  (.toEpochMilli (Instant/now)))

(defn new-session
  [{:keys [id name total-bytes out-dir piece-length pieces-total info-hash-hex]}]
  {:id id
   :name (or name "unnamed")
   :status :running
   :createdAt (now-ms)

   :outDir out-dir
   :totalBytes (long (or total-bytes 0))
   :pieceLength (long (or piece-length 0))
   :piecesTotal (long (or pieces-total 0))
   :infoHash info-hash-hex

   :downloadedBytes 0
   :progress 0.0
   :piecesDone 0

   :download {:lastError nil
              :loopError nil
              :lastVerifiedPiece nil
              :lastVerifyFailPiece nil}

   ;; важно: чтобы ключ был всегда
   :tracker {:status "init"}
   :peersList []
   :peers {:connected 0 :active 0 :known 0}})


(defn snapshot [sess]
  (select-keys sess
               [:id :name :status :outDir
                :totalBytes :downloadedBytes :progress
                :pieceLength :piecesTotal :piecesDone
                :infoHash :peers
                :tracker
                :download]))


