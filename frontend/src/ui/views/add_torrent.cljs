(ns ui.views.add-torrent
  (:require [reagent.core :as r]
            [ui.components :as c]
            [ui.mock-data :as mock]
            [ui.state :as state]))

(defn add-torrent-view
  []
  (let [torrent-path (r/atom "")
        out-dir (r/atom "./downloads")
        errors (r/atom {})]
    (fn []
      [:div {:class "min-h-screen bg-gray-900"}
       [c/navbar {:title "Add Torrent"}]
       
       [:div {:class "container mx-auto px-6 py-8"}
        ;; Back button
        [:div {:class "mb-6"}
         [c/button {:type :secondary
                    :on-click state/go-back!}
          "← Back to Torrents"]]
        
        [:div {:class "max-w-2xl mx-auto"}
         [c/card
          [:h2 {:class "text-2xl font-bold text-gray-100 mb-6"} "Add New Torrent"]
          [:p {:class "text-gray-400 mb-6"} 
           "Enter the path to a .torrent file and specify where you want to save the downloaded files."]
          
          [:form {:on-submit (fn [e]
                               (.preventDefault e)
                               (reset! errors {})
                               
                               ;; Validation
                               (let [errs (cond-> {}
                                            (empty? @torrent-path)
                                            (assoc :torrent-path "Torrent path is required")
                                            
                                            (empty? @out-dir)
                                            (assoc :out-dir "Output directory is required"))]
                                 
                                 (if (seq errs)
                                   (reset! errors errs)
                                   ;; Submit
                                   (do
                                     (mock/add-torrent! @torrent-path @out-dir)
                                     (js/alert "Torrent added successfully!")
                                     (state/navigate-to! :torrents)))))}
           
           [c/input-field
            {:label "Torrent File Path"
             :value @torrent-path
             :on-change #(reset! torrent-path %)
             :placeholder "/path/to/file.torrent or http://example.com/file.torrent"
             :error (:torrent-path @errors)}]
           
           [c/input-field
            {:label "Output Directory"
             :value @out-dir
             :on-change #(reset! out-dir %)
             :placeholder "/path/to/download/directory"
             :error (:out-dir @errors)}]
           
           [:div {:class "bg-blue-900 bg-opacity-20 border border-blue-700 rounded-lg p-4 mb-6"}
            [:h3 {:class "text-blue-400 font-semibold mb-2"} "ℹ️ Note"]
            [:p {:class "text-gray-300 text-sm mb-2"}
             "This is a mock interface. In production, you would upload a .torrent file "
             "or provide a magnet link. The backend would handle parsing and starting the download."]
            [:p {:class "text-purple-300 text-xs font-mono mt-2"}
             ";; Written in pure Clojure - no mutations, only transformations! 🦜"]]
           
           [:div {:class "flex space-x-4"}
            [c/button {:type :primary}
             "Add Torrent"]
            [c/button {:type :secondary
                       :on-click state/go-back!}
             "Cancel"]]]]]]])))

