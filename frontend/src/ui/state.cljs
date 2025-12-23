(ns ui.state
  (:require [reagent.core :as r]))

;; Application state atoms
(defonce current-page (r/atom :torrents))
(defonce selected-torrent-id (r/atom nil))

;; Navigation functions
(defn navigate-to!
  "Navigate to a specific page"
  [page]
  (reset! current-page page))

(defn select-torrent!
  "Select a torrent and navigate to details page"
  [torrent-id]
  (reset! selected-torrent-id torrent-id)
  (navigate-to! :details))

(defn go-back!
  "Go back to torrents list"
  []
  (reset! selected-torrent-id nil)
  (navigate-to! :torrents))

