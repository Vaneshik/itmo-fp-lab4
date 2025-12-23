(ns ui.mock-data
  (:require [reagent.core :as r]))

;; Mock torrents data
(defonce torrents-db
  (r/atom
   [{:id "1"
     :name "Ubuntu 22.04 LTS Desktop"
     :info-hash "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0"
     :total-size 3774873600
     :downloaded 2831155200
     :uploaded 1887436800
     :progress 0.75
     :status :downloading
     :down-speed 2621440
     :up-speed 1048576
     :peers-connected 12
     :peers-total 45
     :pieces-total 1800
     :pieces-done 1350
     :eta 360
     :added-at "2024-01-15T10:30:00Z"}
    
    {:id "2"
     :name "Debian 12.0 DVD ISO"
     :info-hash "b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1"
     :total-size 4102029312
     :downloaded 4102029312
     :uploaded 8204058624
     :progress 1.0
     :status :completed
     :down-speed 0
     :up-speed 524288
     :peers-connected 8
     :peers-total 23
     :pieces-total 1956
     :pieces-done 1956
     :eta 0
     :added-at "2024-01-14T08:15:00Z"}
    
    {:id "3"
     :name "Arch Linux x86_64 ISO"
     :info-hash "c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2"
     :total-size 838860800
     :downloaded 419430400
     :uploaded 0
     :progress 0.50
     :status :paused
     :down-speed 0
     :up-speed 0
     :peers-connected 0
     :peers-total 34
     :pieces-total 400
     :pieces-done 200
     :eta 0
     :added-at "2024-01-16T14:45:00Z"}
    
    {:id "4"
     :name "Big Buck Bunny (Open Movie Project)"
     :info-hash "d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3"
     :total-size 725614592
     :downloaded 72561459
     :uploaded 36280729
     :progress 0.10
     :status :downloading
     :down-speed 3145728
     :up-speed 1572864
     :peers-connected 28
     :peers-total 156
     :pieces-total 347
     :pieces-done 35
     :eta 210
     :added-at "2024-01-17T09:20:00Z"}
    
    {:id "5"
     :name "Fedora Workstation 39 Live x86_64"
     :info-hash "e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3x4"
     :total-size 2097152000
     :downloaded 2097152000
     :uploaded 4194304000
     :progress 1.0
     :status :seeding
     :down-speed 0
     :up-speed 2097152
     :peers-connected 15
     :peers-total 67
     :pieces-total 1000
     :pieces-done 1000
     :eta 0
     :added-at "2024-01-13T16:30:00Z"}
    
    {:id "6"
     :name "Blender 4.0 - 3D Creation Suite"
     :info-hash "f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3x4y5"
     :total-size 314572800
     :downloaded 282915520
     :uploaded 94305173
     :progress 0.90
     :status :downloading
     :down-speed 1048576
     :up-speed 524288
     :peers-connected 6
     :peers-total 18
     :pieces-total 150
     :pieces-done 135
     :eta 30
     :added-at "2024-01-17T11:45:00Z"}
    
    {:id "7"
     :name "Sintel (Blender Foundation Short Film)"
     :info-hash "g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3x4y5z6"
     :total-size 524288000
     :downloaded 26214400
     :uploaded 0
     :progress 0.05
     :status :downloading
     :down-speed 786432
     :up-speed 0
     :peers-connected 3
     :peers-total 92
     :pieces-total 250
     :pieces-done 13
     :eta 630
     :added-at "2024-01-17T13:10:00Z"}
    
    {:id "8"
     :name "Rocky Linux 9.3 Minimal ISO"
     :info-hash "h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3x4y5z6a7"
     :total-size 1258291200
     :downloaded 377487360
     :uploaded 125829120
     :progress 0.30
     :status :downloading
     :down-speed 4194304
     :up-speed 1048576
     :peers-connected 22
     :peers-total 73
     :pieces-total 600
     :pieces-done 180
     :eta 210
     :added-at "2024-01-17T07:00:00Z"}]))

;; Mock peers data for each torrent
(defonce peers-db
  (r/atom
   {"1" [{:ip "192.168.1.100"
          :port 51413
          :client "qBittorrent 4.5.0"
          :choked false
          :interested true
          :down-speed 524288
          :up-speed 262144
          :progress 0.85
          :last-seen (js/Date.)}
         {:ip "10.0.0.50"
          :port 6881
          :client "Transmission 3.00"
          :choked false
          :interested true
          :down-speed 1048576
          :up-speed 0
          :progress 0.92
          :last-seen (js/Date.)}
         {:ip "172.16.0.25"
          :port 51820
          :client "libtorrent 2.0"
          :choked true
          :interested false
          :down-speed 0
          :up-speed 0
          :progress 0.15
          :last-seen (js/Date.)}]
    
    "2" [{:ip "203.0.113.45"
          :port 6882
          :client "Deluge 2.1.1"
          :choked false
          :interested true
          :down-speed 0
          :up-speed 524288
          :progress 1.0
          :last-seen (js/Date.)}
         {:ip "198.51.100.12"
          :port 51413
          :client "qBittorrent 4.4.5"
          :choked false
          :interested false
          :down-speed 0
          :up-speed 0
          :progress 1.0
          :last-seen (js/Date.)}]
    
    "3" []
    
    "4" [{:ip "45.76.123.45"
          :port 6881
          :client "Transmission 4.0.0"
          :choked false
          :interested true
          :down-speed 1572864
          :up-speed 786432
          :progress 0.35
          :last-seen (js/Date.)}
         {:ip "88.99.45.78"
          :port 51820
          :client "rTorrent 0.9.8"
          :choked false
          :interested true
          :down-speed 1048576
          :up-speed 524288
          :progress 0.28
          :last-seen (js/Date.)}
         {:ip "142.93.67.201"
          :port 6882
          :client "Deluge 2.1.1"
          :choked true
          :interested false
          :down-speed 0
          :up-speed 0
          :progress 0.12
          :last-seen (js/Date.)}]
    
    "5" [{:ip "91.108.56.123"
          :port 51413
          :client "qBittorrent 4.5.5"
          :choked false
          :interested false
          :down-speed 0
          :up-speed 1048576
          :progress 1.0
          :last-seen (js/Date.)}
         {:ip "185.125.190.36"
          :port 6881
          :client "Transmission 3.00"
          :choked false
          :interested false
          :down-speed 0
          :up-speed 1048576
          :progress 1.0
          :last-seen (js/Date.)}]
    
    "6" [{:ip "167.172.34.89"
          :port 51413
          :client "µTorrent 3.5.5"
          :choked false
          :interested true
          :down-speed 524288
          :up-speed 262144
          :progress 0.94
          :last-seen (js/Date.)}
         {:ip "178.62.78.123"
          :port 6882
          :client "Vuze 5.7.6.0"
          :choked false
          :interested true
          :down-speed 524288
          :up-speed 262144
          :progress 0.88
          :last-seen (js/Date.)}]
    
    "7" [{:ip "139.59.145.67"
          :port 51820
          :client "Transmission 3.00"
          :choked false
          :interested true
          :down-speed 393216
          :up-speed 0
          :progress 0.42
          :last-seen (js/Date.)}
         {:ip "46.101.23.156"
          :port 6881
          :client "libtorrent 1.2.14"
          :choked false
          :interested true
          :down-speed 393216
          :up-speed 0
          :progress 0.38
          :last-seen (js/Date.)}]
    
    "8" [{:ip "159.89.178.234"
          :port 51413
          :client "qBittorrent 4.5.0"
          :choked false
          :interested true
          :down-speed 2097152
          :up-speed 524288
          :progress 0.55
          :last-seen (js/Date.)}
         {:ip "134.209.123.89"
          :port 6882
          :client "Deluge 2.0.5"
          :choked false
          :interested true
          :down-speed 2097152
          :up-speed 524288
          :progress 0.48
          :last-seen (js/Date.)}
         {:ip "188.166.45.78"
          :port 51820
          :client "rTorrent 0.9.8"
          :choked true
          :interested false
          :down-speed 0
          :up-speed 0
          :progress 0.15
          :last-seen (js/Date.)}]}))

;; Helper functions
(defn generate-id []
  (str (random-uuid)))

;; API Functions
(defn get-torrents
  "Get all torrents"
  []
  @torrents-db)

(defn get-torrent-by-id
  "Get torrent by ID"
  [id]
  (first (filter #(= (:id %) id) @torrents-db)))

(defn get-peers
  "Get peers for a torrent"
  [torrent-id]
  (get @peers-db torrent-id []))

(defn add-torrent!
  "Add a new torrent"
  [torrent-path out-dir]
  (let [new-torrent {:id (generate-id)
                     :name (str "New Torrent - " torrent-path)
                     :info-hash "d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3"
                     :total-size 1073741824
                     :downloaded 0
                     :uploaded 0
                     :progress 0.0
                     :status :downloading
                     :down-speed 0
                     :up-speed 0
                     :peers-connected 0
                     :peers-total 0
                     :pieces-total 512
                     :pieces-done 0
                     :eta 0
                     :added-at (.toISOString (js/Date.))}]
    (swap! torrents-db conj new-torrent)
    new-torrent))

(defn pause-torrent!
  "Pause a torrent"
  [torrent-id]
  (swap! torrents-db
         (fn [torrents]
           (mapv #(if (= (:id %) torrent-id)
                    (assoc % :status :paused :down-speed 0 :up-speed 0)
                    %)
                 torrents))))

(defn resume-torrent!
  "Resume a paused torrent"
  [torrent-id]
  (swap! torrents-db
         (fn [torrents]
           (mapv #(if (= (:id %) torrent-id)
                    (assoc % :status :downloading)
                    %)
                 torrents))))

(defn stop-torrent!
  "Stop a torrent"
  [torrent-id]
  (swap! torrents-db
         (fn [torrents]
           (mapv #(if (= (:id %) torrent-id)
                    (assoc % :status :stopped :down-speed 0 :up-speed 0 :peers-connected 0)
                    %)
                 torrents))))

(defn remove-torrent!
  "Remove a torrent"
  [torrent-id]
  (swap! torrents-db
         (fn [torrents]
           (filterv #(not= (:id %) torrent-id) torrents)))
  (swap! peers-db dissoc torrent-id))

