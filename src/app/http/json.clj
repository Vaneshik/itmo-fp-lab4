(ns app.http.json
  (:require [jsonista.core :as j]))

(def mapper
  (j/object-mapper
   {:decode-key-fn keyword
    :encode-key-fn name}))

(defn encode [x] (j/write-value-as-string x mapper))
(defn decode [s] (j/read-value s mapper))

(defn wrap-json-response [handler]
  (fn [req]
    (let [resp (handler req)]
      (cond
        (nil? resp) resp
        (string? (:body resp)) (update resp :headers merge {"content-type" "application/json; charset=utf-8"})
        :else (-> resp
                  (update :headers merge {"content-type" "application/json; charset=utf-8"})
                  (update :body encode))))))

(defn wrap-json-body [handler]
  (fn [req]
    (let [ct (get-in req [:headers "content-type"] "")]
      (if (and ct (clojure.string/includes? ct "application/json"))
        (let [body-str (slurp (:body req))
              data (when (seq body-str) (decode body-str))]
          (handler (assoc req :json-body data)))
        (handler req)))))
