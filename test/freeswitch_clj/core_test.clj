(ns freeswitch-clj.core-test
  (:require [clojure.test :refer :all]
            [freeswitch-clj.core :refer :all]))

(deftest test-fs-inbound-session
  (let [beacon (atom nil)
        c (connect :host "127.0.0.1" :port 8021 :password "ClueCon")]

    ;; Send a simple 'status' api command.
    (is (= (select-keys (req-api c "status") [:ok])
           {:ok true}))

    ;; Turn on a beacon when a background job is complete.
    (let [beacon (promise)
          resp (req-bgapi c "status" (fn [c m] (deliver beacon true)))]
      (is (= (select-keys resp [:ok])
             {:ok true}))
      ;; 500 milliseconds should be enough for the job to complete.
      (is (= (deref beacon 500 false)
             true)))))
