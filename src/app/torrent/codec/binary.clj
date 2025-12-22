(ns app.torrent.codec.binary)

(defn int32->bytes ^bytes [^long n]
  (byte-array [(unchecked-byte (bit-and (bit-shift-right n 24) 0xFF))
               (unchecked-byte (bit-and (bit-shift-right n 16) 0xFF))
               (unchecked-byte (bit-and (bit-shift-right n 8) 0xFF))
               (unchecked-byte (bit-and n 0xFF))]))

(defn bytes->int32
  "Reads big-endian int32 from bs at offset."
  ^long [^bytes bs ^long off]
  (let [b0 (bit-and (aget bs off) 0xFF)
        b1 (bit-and (aget bs (+ off 1)) 0xFF)
        b2 (bit-and (aget bs (+ off 2)) 0xFF)
        b3 (bit-and (aget bs (+ off 3)) 0xFF)]
    (+ (bit-shift-left b0 24)
       (bit-shift-left b1 16)
       (bit-shift-left b2 8)
       b3)))
