(ns ui.components
  (:require [reagent.core :as r]
            [ui.format :as fmt]))

(defn button
  [{:keys [on-click disabled type class]} & children]
  (let [base-classes "px-4 py-2 rounded-lg font-medium transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
        type-classes (case type
                       :primary "bg-blue-600 hover:bg-blue-700 text-white"
                       :danger "bg-red-600 hover:bg-red-700 text-white"
                       :secondary "bg-gray-700 hover:bg-gray-600 text-gray-100"
                       :success "bg-green-600 hover:bg-green-700 text-white"
                       "bg-gray-700 hover:bg-gray-600 text-gray-100")
        all-classes (str base-classes " " type-classes " " (or class ""))]
    [:button
     {:class all-classes
      :on-click on-click
      :disabled disabled}
     children]))

(defn progress-bar
  [{:keys [progress class]}]
  (let [percentage (* progress 100)
        color-class (cond
                      (>= progress 1.0) "bg-green-500"
                      (>= progress 0.5) "bg-blue-500"
                      :else "bg-yellow-500")]
    [:div {:class (str "w-full bg-gray-700 rounded-full h-2.5 " (or class ""))}
     [:div {:class (str "h-2.5 rounded-full transition-all duration-300 " color-class)
            :style {:width (str percentage "%")}}]]))

(defn status-badge
  [status]
  (let [[bg-class text] (case status
                          :downloading ["bg-blue-600" "Downloading"]
                          :paused ["bg-yellow-600" "Paused"]
                          :completed ["bg-green-600" "Completed"]
                          :stopped ["bg-red-600" "Stopped"]
                          :seeding ["bg-purple-600" "Seeding"]
                          ["bg-gray-600" "Unknown"])]
    [:span {:class (str "px-3 py-1 rounded-full text-xs font-semibold " bg-class)}
     text]))

(defn card
  [{:keys [class]} & children]
  [:div {:class (str "bg-gray-800 rounded-lg shadow-lg p-6 " (or class ""))}
   children])

(defn navbar
  [{:keys [title on-add-click]}]
  [:nav {:class "bg-gray-800 border-b border-gray-700 px-6 py-4"}
   [:div {:class "flex items-center justify-between"}
    [:div {:class "flex items-center space-x-4"}
     [:h1 {:class "text-2xl font-bold text-blue-500"} title]
     [:span {:class "text-gray-400 text-sm"} "BitTorrent Client"]]
    (when on-add-click
      [button {:type :primary :on-click on-add-click}
       "➕ Add Torrent"])]])

(defn icon-button
  [{:keys [on-click title icon class]}]
  [:button
   {:class (str "p-2 rounded hover:bg-gray-700 transition-colors duration-200 " (or class ""))
    :on-click on-click
    :title title}
   [:span {:class "text-lg"} icon]])

(defn table
  [{:keys [headers rows class]}]
  (js/console.log "Table component - headers:" headers "rows count:" (count rows))
  (js/console.log "Table rows:" rows)
  [:div {:class (str "overflow-x-auto " (or class ""))}
   [:table {:class "w-full"}
    [:thead {:class "bg-gray-700 text-gray-300 text-sm uppercase"}
     [:tr
      (for [[idx header] (map-indexed vector headers)]
        [:th {:key idx :class "px-4 py-3 text-left"} header])]]
    [:tbody {:class "divide-y divide-gray-700"}
     (doall (for [[idx row] (map-indexed vector rows)]
              (do
                (js/console.log "Rendering table row" idx ":" row)
                [:tr {:key idx :class "hover:bg-gray-750 transition-colors duration-150"}
                 row])))]]])

(defn empty-state
  [{:keys [icon title message action]}]
  [:div {:class "flex flex-col items-center justify-center py-16 text-center"}
   [:div {:class "text-6xl mb-4"} icon]
   [:h3 {:class "text-xl font-semibold text-gray-300 mb-2"} title]
   [:p {:class "text-gray-500 mb-6"} message]
   (when action
     action)])

(defn input-field
  [{:keys [label value on-change placeholder type error class]}]
  [:div {:class (str "mb-4 " (or class ""))}
   (when label
     [:label {:class "block text-sm font-medium text-gray-300 mb-2"} label])
   [:input
    {:type (or type "text")
     :value value
     :on-change #(on-change (-> % .-target .-value))
     :placeholder placeholder
     :class (str "w-full px-4 py-2 bg-gray-700 border rounded-lg text-gray-100 "
                 "focus:outline-none focus:ring-2 focus:ring-blue-500 "
                 (if error "border-red-500" "border-gray-600"))}]
   (when error
     [:p {:class "text-red-500 text-sm mt-1"} error])])

(defn stats-card
  [{:keys [label value icon class]}]
  [:div {:class (str "bg-gray-700 rounded-lg p-4 " (or class ""))}
   [:div {:class "flex items-center justify-between"}
    [:div
     [:p {:class "text-gray-400 text-sm"} label]
     [:p {:class "text-2xl font-bold text-gray-100 mt-1"} value]]
    (when icon
      [:div {:class "text-3xl text-blue-500"} icon])]])

