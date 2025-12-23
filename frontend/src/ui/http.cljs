(ns ui.http
  (:require [reagent.core :as r]))

(def api-base-url
  (if (= js/window.location.port "3000")
    "http://localhost:3002/api"
    (str js/window.location.protocol "//" js/window.location.hostname ":" js/window.location.port "/api")))

(defonce torrents (r/atom []))
(defonce loading? (r/atom false))
(defonce error (r/atom nil))

(defn fetch-torrents! []
  (js/console.log "Fetching torrents from:" (str api-base-url "/torrents"))
  (reset! loading? true)
  (reset! error nil)
  (-> (js/fetch (str api-base-url "/torrents"))
      (.then (fn [response]
               (js/console.log "Response status:" (.-status response))
               (if (.-ok response)
                 (.json response)
                 (throw (js/Error. "Failed to fetch torrents")))))
      (.then (fn [data]
               (js/console.log "Raw data from backend:" data)
               (let [torrents-data (js->clj data :keywordize-keys true)
                     _ (js/console.log "Converted to cljs:" torrents-data)
                     processed (mapv #(update % :status keyword) torrents-data)
                     _ (js/console.log "Processed torrents:" processed)]
                 (reset! torrents processed)
                 (js/console.log "Torrents atom now contains:" @torrents)
                 (reset! loading? false))))
      (.catch (fn [err]
                (js/console.error "Fetch error:" err)
                (reset! error (str "Error: " (.-message err)))
                (reset! loading? false)))))

(defn fetch-torrents-silent! []
  (-> (js/fetch (str api-base-url "/torrents"))
      (.then (fn [response]
               (if (.-ok response)
                 (.json response)
                 (throw (js/Error. "Failed to fetch torrents")))))
      (.then (fn [data]
               (let [torrents-data (js->clj data :keywordize-keys true)
                     processed (mapv #(update % :status keyword) torrents-data)]
                 (reset! torrents processed))))
      (.catch (fn [err]
                (js/console.error "Silent fetch error:" err)))))

(defn check-health! []
  (-> (js/fetch (str api-base-url "/health"))
      (.then (fn [response] (.json response)))
      (.then (fn [data]
               (js/console.log "Health check:" (js->clj data))))
      (.catch (fn [err]
                (js/console.error "Health check failed:" err)))))

(defn pause-torrent! [torrent-id]
  (-> (js/fetch (str api-base-url "/torrents/" torrent-id "/pause")
                #js {:method "POST"})
      (.then (fn [response]
               (if (.-ok response)
                 (do
                   (js/console.log "Torrent paused:" torrent-id)
                   (fetch-torrents-silent!))
                 (throw (js/Error. "Failed to pause torrent")))))
      (.catch (fn [err]
                (js/console.error "Pause error:" err)))))

(defn resume-torrent! [torrent-id]
  (-> (js/fetch (str api-base-url "/torrents/" torrent-id "/resume")
                #js {:method "POST"})
      (.then (fn [response]
               (if (.-ok response)
                 (do
                   (js/console.log "Torrent resumed:" torrent-id)
                   (fetch-torrents-silent!))
                 (throw (js/Error. "Failed to resume torrent")))))
      (.catch (fn [err]
                (js/console.error "Resume error:" err)))))

(defn remove-torrent! [torrent-id]
  (-> (js/fetch (str api-base-url "/torrents/" torrent-id)
                #js {:method "DELETE"})
      (.then (fn [response]
               (if (.-ok response)
                 (do
                   (js/console.log "Torrent removed:" torrent-id)
                   (fetch-torrents-silent!))
                 (throw (js/Error. "Failed to remove torrent")))))
      (.catch (fn [err]
                (js/console.error "Remove error:" err)))))

