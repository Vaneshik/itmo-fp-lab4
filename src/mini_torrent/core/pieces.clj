(ns mini-torrent.core.pieces)

(defn piece-len
  "Length of piece idx (last piece may be shorter)."
  [total piece-length idx pieces-count]
  (let [start (* (long idx) (long piece-length))
        remain (- (long total) start)]
    (if (= idx (dec pieces-count))
      remain
      (long piece-length))))

(defn pick-piece!
  "Берёт из очереди первый piece, который есть у этого пира. Если нет — nil."
  [queue ^booleans have]
  (let [v @queue
        n (count v)]
    (loop [i 0]
      (when (< i n)
        (let [p (nth v i)]
          (if (aget have (int p))
            (do
              (swap! queue (fn [vv]
                             (vec (concat (subvec vv 0 i) (subvec vv (inc i))))))
              p)
            (recur (inc i))))))))
