(ns mini-torrent.webseed
  (:import [java.net URL HttpURLConnection]
           [java.io RandomAccessFile]
           [java.util Arrays]))

(defn- read-exact! [^java.io.InputStream in ^bytes buf]
  (loop [off 0]
    (if (= off (alength buf))
      buf
      (let [n (.read in buf off (- (alength buf) off))]
        (when (neg? n)
          (throw (ex-info "Unexpected EOF from webseed" {:got off :need (alength buf)})))
        (recur (+ off n))))))

(defn download!
  "Качает single-file торрент через webseed (HTTP Range), проверяя SHA1 каждого piece.
   Возвращает true если докачал до конца, иначе бросает исключение."
  [{:keys [webseed-url out-path length piece-length piece-hashes sha1]} stats done]
  (with-open [raf (RandomAccessFile. out-path "rw")]
    (let [pieces-total (count piece-hashes)]
      (dotimes [piece-idx pieces-total]
        (when @done (throw (ex-info "Stopped" {})))
        (let [plen (if (= piece-idx (dec pieces-total))
                     (long (- length (* (dec pieces-total) piece-length)))
                     (long piece-length))
              offset (* piece-idx piece-length)
              end (dec (+ offset plen))
              url (URL. webseed-url)
              ^HttpURLConnection conn (.openConnection url)]
          (.setRequestMethod conn "GET")
          (.setConnectTimeout conn 8000)
          (.setReadTimeout conn 8000)
          (.setRequestProperty conn "Range" (str "bytes=" offset "-" end))

          (let [code (.getResponseCode conn)]
            ;; для Range ожидаем 206, но некоторые источники могут дать 200 (тогда придётся читать/скипать; не делаем)
            (when-not (= code 206)
              (throw (ex-info "Webseed did not honor Range" {:code code :url webseed-url}))))

          (with-open [in (.getInputStream conn)]
            (let [buf (byte-array plen)]
              (read-exact! in buf)
              ;; пишем в файл
              (.seek raf offset)
              (.write raf buf)
              (swap! (:downloaded stats) + plen)

              ;; sha1 check piece
              (let [got (sha1 buf)
                    expected (nth piece-hashes piece-idx)]
                (when-not (Arrays/equals ^bytes got ^bytes expected)
                  (throw (ex-info "Piece hash mismatch (webseed)" {:piece piece-idx}))))
              (swap! (:pieces-done stats) inc)))))))
  true)