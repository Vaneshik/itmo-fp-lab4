(ns app.torrent.pieces.selection-test
  (:require [clojure.test :refer [deftest is testing]]
            [app.torrent.pieces.state :as st]
            [app.torrent.pieces.selection :as sel]))

(deftest init-and-next-block-test
  (testing "init-state and next-block pick first missing block"
    (let [mi {:total-bytes 10
              :piece-length 6
              :pieces-total 2}
          state (st/init-state mi 4)
          b1 (sel/next-block state)]
      (is (= {:piece-idx 0 :block-idx 0 :begin 0 :len 4} b1))

      ;; mark requested -> should pick next block in same piece
      (let [state2 (st/mark-requested state 0 0)
            b2 (sel/next-block state2)]
        (is (= {:piece-idx 0 :block-idx 1 :begin 4 :len 2} b2)))

      ;; mark received both blocks -> piece 0 done, next piece
      (let [state3 (-> state
                       (st/mark-received 0 0)
                       (st/mark-received 0 1))
            b3 (sel/next-block state3)]
        (is (= {:piece-idx 1 :block-idx 0 :begin 0 :len 4} b3))))))
