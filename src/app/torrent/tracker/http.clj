(ns app.torrent.tracker.http
  (:require [clojure.string :as str]
            [app.torrent.codec.bencode :as ben]
            [app.torrent.tracker.response :as resp])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse HttpResponse$BodyHandlers]
           [java.time Duration]))

(defn- unreserved? [b]
  (or (<= (int \A) b (int \Z))
      (<= (int \a) b (int \z))
      (<= (int \0) b (int \9))
      (= b (int \-))
      (= b (int \_))
      (= b (int \.))
      (= b (int \~))))

(defn- hex2 [b]
  (format "%02X" (bit-and b 0xFF)))

(defn url-encode-bytes
  "Percent-encoding по байтам (важно для info_hash/peer_id)."
  [^bytes bs]
  (let [sb (StringBuilder.)]
    (dotimes [i (alength bs)]
      (let [b (bit-and (aget bs i) 0xFF)]
        (if (unreserved? b)
          (.append sb (char b))
          (.append sb (str "%" (hex2 b))))))
    (.toString sb)))

(defn- qparam [k v]
  (str k "=" v))

(defn build-announce-url
  [{:keys [announce-url info-hash peer-id port uploaded downloaded left event numwant]}]
  (let [base announce-url
        qs (->> [(qparam "info_hash" (url-encode-bytes info-hash))
                 (qparam "peer_id" (url-encode-bytes peer-id))
                 (qparam "port" (str (long port)))
                 (qparam "uploaded" (str (long (or uploaded 0))))
                 (qparam "downloaded" (str (long (or downloaded 0))))
                 (qparam "left" (str (long (or left 0))))
                 (qparam "compact" "1")
                 (qparam "numwant" (str (long (or numwant 50))))
                 (when event (qparam "event" (name event)))]
                (remove nil?)
                (str/join "&"))]
    (str base (if (str/includes? base "?") "&" "?") qs)))

(defn announce!
  "returns {:interval n :peers [...]} or throws"
  [{:keys [timeout-ms] :as args}]
  (let [url (build-announce-url args)
        timeout (Duration/ofMillis (long (or timeout-ms 7000)))
        client (-> (HttpClient/newBuilder)
                   (.followRedirects HttpClient$Redirect/NORMAL)
                   (.connectTimeout timeout)
                   (.build))
        req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout timeout)
                (.header "User-Agent" "clj-torrent-client/0.1")
                (.header "Accept" "*/*")
                (.GET)
                (.build))
        resp-bytes (.body (.send client req (HttpResponse$BodyHandlers/ofByteArray)))
        decoded (ben/decode resp-bytes)]
    (resp/parse-response decoded)))
