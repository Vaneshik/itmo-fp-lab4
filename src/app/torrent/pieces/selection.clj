(ns app.torrent.pieces.selection
  (:require [app.torrent.pieces.state :as st]))

(defn block-len
  "Длина блока по (piece-bytes, block-size, block-idx). Последний блок может быть короче."
  [piece-bytes block-size block-idx]
  (let [start (* (long block-idx) (long block-size))
        remain (- (long piece-bytes) start)]
    (min (long block-size) remain)))

(defn next-block
  "Возвращает следующий блок для скачивания или nil.
   Пока не учитываем peer bitfield — просто sequential."
  [state]
  (let [bs (:block-size state)]
    (loop [i 0]
      (when (< i (count (:pieces state)))
        (let [{:keys [done? blocks-total done-blocks requested-blocks piece-bytes]} (get (:pieces state) i)]
          (if done?
            (recur (inc i))
            (let [block-idx (first (remove (fn [b] (or (contains? done-blocks b)
                                                       (contains? requested-blocks b)))
                                           (range blocks-total)))]
              (if (nil? block-idx)
                (recur (inc i))
                (let [begin (* block-idx bs)
                      len (block-len piece-bytes bs block-idx)]
                  {:piece-idx i
                   :block-idx block-idx
                   :begin begin
                   :len len})))))))))
