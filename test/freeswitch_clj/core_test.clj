(ns freeswitch-clj.core-test
  (:require [clojure.test :refer :all]
            [freeswitch-clj.core :refer :all]))

(deftest test-fs-inbound-session
  (let [conn (connect :host "127.0.0.1"
                      :port 8021
                      :password "ClueCon")]

    ;; Send a simple 'status' api command.
    (is (= (select-keys (req-api conn "status") [:ok])
           {:ok true}))

    ;; Turn on a beacon when a background job is complete.
    (let [beacon (promise)
          resp (req-bgapi conn
                          (fn [conn event]
                            (deliver beacon true))
                          "status")]
      (is (= (select-keys resp [:ok])
             {:ok true}))

      ;; 500 milliseconds should be enough for the job to complete.
      (is (= (deref beacon 500 false)
             true)))

    ;; Catch background_job with a generalized handler.
    (let [beacon (promise)]
      ;; Reset event handlers.
      (reset! (conn :event-handlers) {})

      ;; Bind a general handler for bgjob.
      (bind-event conn
                  (fn [conn event]
                    (deliver beacon true))
                  :event-name "BACKGROUND_JOB")

      ;; Listen for bgjob event.
      (req conn ["event BACKGROUND_JOB"] {} nil)
      ;; Make a bgapi request.
      (req conn ["bgapi" "status"] {} nil)
      ;; Wait for our beacon to light-up.
      (is (= (deref beacon 500 false)
             true)))

    ;; Catch background_job with a specific handler,
    ;; even when a general and also a catch-all-stray
    ;; handler exists.
    (let [beacon (promise)]
      ;; Reset event handlers.
      (reset! (conn :event-handlers) {})

      ;; Bind a catch-all-stray handler.
      (bind-event conn
                  (fn [conn event]
                    (deliver beacon :catch-all-stray)))

      ;; Bind a general handler for bgjob.
      (bind-event conn
                  (fn [conn event]
                    (deliver beacon :general))
                  :event-name "BACKGROUND_JOB")

      ;; Bind a specific bgjob handler.
      (bind-event conn
                  (fn [conn event]
                    (deliver beacon :specific))
                  :event-name "BACKGROUND_JOB"
                  :job-uuid "foobar")

      ;; Listen for bgjob event.
      (req conn ["event BACKGROUND_JOB"] {} nil)
      ;; Make a bgapi request.
      (req conn ["bgapi" "status"] {:job-uuid "foobar"} nil)
      ;; Wait for our beacon to light-up.
      (is (= (deref beacon 500 false)
             :specific)))))
