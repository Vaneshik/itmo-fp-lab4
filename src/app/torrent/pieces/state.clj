(ns app.torrent.pieces.state)

(defn piece-size
  "Размер piece по индексу (последний может быть короче)."
  [{:keys [total-bytes piece-length]} piece-idx]
  (let [pl (long piece-length)
        total (long total-bytes)
        full-pieces (quot total pl)
        last-size (- total (* full-pieces pl))]
    (if (< piece-idx full-pieces)
      pl
      (if (zero? last-size) pl last-size))))

(defn blocks-total
  [piece-bytes block-size]
  (long (Math/ceil (/ (double piece-bytes) (double block-size)))))

(defn init-state
  "metainfo fields needed: total-bytes, piece-length, pieces-total; block-size from config.
   returns {:block-size ... :pieces [...]}"
  [{:keys [total-bytes piece-length pieces-total] :as mi} block-size]
  {:total-bytes (long total-bytes)
   :piece-length (long piece-length)
   :pieces-total (long pieces-total)
   :block-size (long block-size)
   :pieces
   (vec
    (for [i (range (long pieces-total))]
      (let [psz (piece-size mi i)
            bt (blocks-total psz block-size)]
        {:piece-idx i
         :piece-bytes psz
         :blocks-total bt
         :done-blocks #{}
         :requested-blocks #{}
         :done? false})))})

(defn piece-done? [piece]
  (= (:blocks-total piece) (count (:done-blocks piece))))

(defn mark-requested
  "Marks (piece-idx, block-idx) as requested."
  [state piece-idx block-idx]
  (update-in state [:pieces piece-idx :requested-blocks] conj block-idx))

(defn mark-received
  "Marks (piece-idx, block-idx) as done, clears it from requested, updates :done?."
  [state piece-idx block-idx]
  (let [state' (-> state
                   (update-in [:pieces piece-idx :done-blocks] conj block-idx)
                   (update-in [:pieces piece-idx :requested-blocks] disj block-idx))
        done? (piece-done? (get-in state' [:pieces piece-idx]))]
    (assoc-in state' [:pieces piece-idx :done?] done?)))

(defn pieces-done-count [state]
  (count (filter :done? (:pieces state))))

(defn progress
  "0..1 by done pieces (simple)."
  [state]
  (let [total (double (:pieces-total state))
        done (double (pieces-done-count state))]
    (if (zero? total) 0.0 (/ done total))))

(defn reset-piece
  "Сбрасывает прогресс piece (если verify не прошёл)."
  [state piece-idx]
  (-> state
      (assoc-in [:pieces piece-idx :done-blocks] #{})
      (assoc-in [:pieces piece-idx :requested-blocks] #{})
      (assoc-in [:pieces piece-idx :done?] false)))
