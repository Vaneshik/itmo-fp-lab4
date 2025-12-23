(ns ui.views.torrent-details
  (:require [reagent.core :as r]
            [ui.components :as c]
            [ui.format :as fmt]
            [ui.mock-data :as mock]
            [ui.state :as state]))

(defn peer-row
  [peer]
  [:tr
   [:td {:class "px-4 py-3"} (str (:ip peer) ":" (:port peer))]
   [:td {:class "px-4 py-3"} (:client peer)]
   [:td {:class "px-4 py-3"}
    [:span {:class (str "px-2 py-1 rounded text-xs "
                        (if (:choked peer) "bg-red-900 text-red-200" "bg-green-900 text-green-200"))}
     (if (:choked peer) "Choked" "Unchoked")]]
   [:td {:class "px-4 py-3"}
    [:span {:class (str "px-2 py-1 rounded text-xs "
                        (if (:interested peer) "bg-blue-900 text-blue-200" "bg-gray-700 text-gray-400"))}
     (if (:interested peer) "Interested" "Not Interested")]]
   [:td {:class "px-4 py-3"} (fmt/format-speed (:down-speed peer))]
   [:td {:class "px-4 py-3"} (fmt/format-speed (:up-speed peer))]
   [:td {:class "px-4 py-3"} (fmt/format-progress (:progress peer))]])

(defn torrent-details-view
  []
  (let [torrent (r/atom nil)
        peers (r/atom nil)]
    (r/create-class
     {:component-did-mount
      (fn []
        (when-let [id @state/selected-torrent-id]
          (reset! torrent (mock/get-torrent-by-id id))
          (reset! peers (mock/get-peers id))))
      
      :reagent-render
      (fn []
        (if-not @torrent
          [:div {:class "min-h-screen bg-gray-900 flex items-center justify-center"}
           [:div {:class "text-center"}
            [:h2 {:class "text-2xl text-gray-400"} "Torrent not found"]
            [c/button {:type :secondary
                       :on-click state/go-back!
                       :class "mt-4"}
             "← Back to Torrents"]]]
          
          [:div {:class "min-h-screen bg-gray-900"}
           [c/navbar {:title (:name @torrent)}]
           
           [:div {:class "container mx-auto px-6 py-8"}
            ;; Back button
            [:div {:class "mb-6"}
             [c/button {:type :secondary
                        :on-click state/go-back!}
              "← Back to Torrents"]]
            
            [:div {:class "space-y-6"}
             ;; Overview section
             [c/card
              [:h2 {:class "text-xl font-bold text-gray-100 mb-4"} "Overview"]
              [:div {:class "grid grid-cols-1 md:grid-cols-2 gap-6"}
               [:div
                [:h3 {:class "text-sm text-gray-400 mb-1"} "Name"]
                [:p {:class "text-gray-100"} (:name @torrent)]]
               [:div
                [:h3 {:class "text-sm text-gray-400 mb-1"} "Status"]
                [:div [c/status-badge (:status @torrent)]]]
               [:div
                [:h3 {:class "text-sm text-gray-400 mb-1"} "Info Hash"]
                [:p {:class "text-gray-100 font-mono text-sm break-all"} (:info-hash @torrent)]]
               [:div
                [:h3 {:class "text-sm text-gray-400 mb-1"} "Total Size"]
                [:p {:class "text-gray-100"} (fmt/format-bytes (:total-size @torrent))]]
               [:div
                [:h3 {:class "text-sm text-gray-400 mb-1"} "Downloaded"]
                [:p {:class "text-gray-100"} (fmt/format-bytes (:downloaded @torrent))]]
               [:div
                [:h3 {:class "text-sm text-gray-400 mb-1"} "Uploaded"]
                [:p {:class "text-gray-100"} (fmt/format-bytes (:uploaded @torrent))]]
               [:div
                [:h3 {:class "text-sm text-gray-400 mb-1"} "Pieces"]
                [:p {:class "text-gray-100"} 
                 (str (:pieces-done @torrent) " / " (:pieces-total @torrent))]]
               [:div
                [:h3 {:class "text-sm text-gray-400 mb-1"} "ETA"]
                [:p {:class "text-gray-100"} (fmt/format-time (:eta @torrent))]]]]
             
             ;; Progress section
             [c/card
              [:h2 {:class "text-xl font-bold text-gray-100 mb-4"} "Progress"]
              [:div {:class "space-y-2"}
               [:div {:class "flex justify-between text-sm text-gray-400"}
                [:span (fmt/format-progress (:progress @torrent))]
                [:span (str (fmt/format-bytes (:downloaded @torrent)) 
                            " / " 
                            (fmt/format-bytes (:total-size @torrent)))]]
               [c/progress-bar {:progress (:progress @torrent)}]
               [:div {:class "flex justify-between text-sm text-gray-400 mt-4"}
                [:span "↓ " (fmt/format-speed (:down-speed @torrent))]
                [:span "↑ " (fmt/format-speed (:up-speed @torrent))]]]]
             
             ;; Peers section
             [c/card
              [:h2 {:class "text-xl font-bold text-gray-100 mb-4"} 
               (str "Peers (" (:peers-connected @torrent) " connected)")]
              (if (empty? @peers)
                [c/empty-state
                 {:icon "👥"
                  :title "No Peers"
                  :message "No peers are currently connected to this torrent."}]
                
                [c/table
                 {:headers ["Address" "Client" "Choked" "Interested" "Down Speed" "Up Speed" "Progress"]
                  :rows (for [peer @peers]
                          [peer-row peer])}])]
             
             ;; Actions section
             [c/card
              [:h2 {:class "text-xl font-bold text-gray-100 mb-4"} "Actions"]
              [:div {:class "flex space-x-4"}
               (if (= (:status @torrent) :paused)
                 [c/button {:type :success
                            :on-click #(mock/resume-torrent! (:id @torrent))}
                  "▶️ Resume"]
                 [c/button {:type :secondary
                            :on-click #(mock/pause-torrent! (:id @torrent))}
                  "⏸ Pause"])
               [c/button {:type :danger
                          :on-click #(mock/stop-torrent! (:id @torrent))}
                "⏹ Stop"]
               [c/button {:type :danger
                          :on-click #(when (js/confirm "Remove this torrent?")
                                       (mock/remove-torrent! (:id @torrent))
                                       (state/go-back!))}
                "🗑 Remove"]]]]]]))})))

