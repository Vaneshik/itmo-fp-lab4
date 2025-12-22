(ns app.http.api-test
  (:require [clojure.test :refer [deftest is]]
            [app.http.handlers :as h]))

(deftest health-test
  (let [resp (h/health {})]
    (is (= 200 (:status resp)))
    (is (= "ok" (get-in resp [:body :status])))))
