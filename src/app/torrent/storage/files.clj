(ns app.torrent.storage.files
  (:require [clojure.java.io :as io])
  (:import [java.io RandomAccessFile File]))

(defn- ensure-dir! [^String dir]
  (let [f (io/file dir)]
    (.mkdirs f)
    f))

(defn open-storage!
  "Создаёт/открывает файл outDir/name и выставляет длину totalBytes.
   Возвращает handle {:raf ... :path ... :total ...}"
  [{:keys [outDir name totalBytes]}]
  (ensure-dir! outDir)
  (let [^File f (io/file outDir name)
        raf (RandomAccessFile. f "rw")]
    (.setLength raf (long totalBytes))
    {:raf raf
     :path (.getAbsolutePath f)
     :total (long totalBytes)}))

(defn close! [{:keys [^RandomAccessFile raf]}]
  (when raf (.close raf)))

(defn write-block!
  "Пишет block в оффсет (байты) в файле."
  [{:keys [^RandomAccessFile raf]} ^long offset ^bytes block]
  (.seek raf offset)
  (.write raf block 0 (alength block))
  true)

(defn read-piece
  "Читает piece-bytes из оффсета."
  ^bytes [{:keys [^RandomAccessFile raf]} ^long offset ^long piece-bytes]
  (.seek raf offset)
  (let [buf (byte-array (int piece-bytes))]
    (.readFully raf buf)
    buf))
