(ns freeswitch-clj.core-test
  (:require [clojure.test :refer :all]
            [taoensso.timbre :as log]
            [freeswitch-clj.core :refer :all]))

(log/merge-config! {:level :info})

(defn get-freeswitch-esl-connection-configs
  []
  (let [env               (System/getenv)
        fs-a              {:host     (get env "FS_A_HOST" "127.0.0.1")
                           :esl-port (Integer/parseInt (get env "FS_A_PORT" "8021"))
                           :esl-pass (get env "FS_A_PASS" "ClueCon")}
        fs-b              {:host     (get env "FS_B_HOST" "127.0.0.1")
                           :esl-port (Integer/parseInt (get env "FS_B_PORT" "8022"))
                           :esl-pass (get env "FS_B_PASS" "ClueCon")
                           :sip-port (Integer/parseInt (get env "FS_B_SIP_PORT" "5080"))
                           :sip-user (get env "FS_B_SIP_USER" "callblackholeuser")
                           :sip-pass (get env "FS_B_SIP_PASS" "callblackholepass")}
        esl-outbound-port (Integer/parseInt (get env "ESL_OUTBOUND_PORT" "4321"))]
    {:fs-a              fs-a
     :fs-b              fs-b
     :esl-outbound-port esl-outbound-port})) 

(deftest test-fs-inbound-session
  (let [{:keys [fs-a]} (get-freeswitch-esl-connection-configs)]
    (doseq [thread-type [:thread :go-block]]
      (testing (format "Creating inbound connection to freeswitch host A with thread type %s ..."
                       thread-type)
        (let [conn (connect :host (:host fs-a)
                            :port (:esl-port fs-a)
                            :password (:esl-pass fs-a)
                            :async-thread-type thread-type)]

          (testing "Sending 'status' api command ..."
            ;; Send a simple 'status' api command.
            (is (= (select-keys (req-api conn "status") [:ok])
                   {:ok true})))

          (testing "Sending 'status' api command with 'bgapi' ..."
            ;; Turn on a beacon when a background job is complete.
            (let [beacon (promise)
                  resp   (req-bgapi conn
                                    (fn [conn event]
                                      (deliver beacon true))
                                    "status")]
              (is (= (select-keys resp [:ok])
                     {:ok true}))

              ;; 1000 milliseconds should be enough for the job to complete.
              (is (= (deref beacon 1000 false)
                     true))))

          (testing "Trying event capture with generalized handller ..."
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
                     true))))

          (testing "Trying event capture with specific handler ..."
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
                     :specific)))))))))


(deftest test-fs-outbound-session
  (let [{:keys [fs-a fs-b esl-outbound-port] :as config} (get-freeswitch-esl-connection-configs)]
    (doseq [thread-type [:thread :go-block]]
      (testing (format "Testing outbound connection mode with thread type %s ..."
                       thread-type)
        (testing "Testing if outbound mode works ..."
          (let [connected-with-b? (promise)
                b-outbound-server (promise)
                conn-a            (connect :host (:host fs-a)
                                           :port (:esl-port fs-a)
                                           :password (:esl-pass fs-a)
                                           :async-thread-type :thread)]

            (testing "Setting up server for outbound connections from host B ..."
              (deliver b-outbound-server
                       (listen :host "127.0.0.1"
                               :port esl-outbound-port
                               :handler (fn [conn-b chan-info]
                                          (deliver connected-with-b? true))
                               :async-thread-type thread-type)))

            (testing "Sending originate command to host A to call B ..."
              (let [originate-cmd (format "originate {ignore_early_media=false}sofia/external/%s@%s:%s &socket('127.0.0.1:%s') async full"
                                          (:sip-user fs-b)
                                          (:host fs-b)
                                          (:sip-port fs-b)
                                          esl-outbound-port)]
                (is (= (select-keys (req-api conn-a originate-cmd) [:ok])
                       {:ok true}))))

            (testing "Checking if outbound connection was made within 10 seconds ..."
              (is (= (deref connected-with-b? 10000 false) true)))

            ;; Closing previous outbound server.
            (.close @b-outbound-server)))

        (testing "Testing if :pre-init-fn works as intended ..."
          (let [answer-event-handled? (promise)
                b-outbound-server     (promise)
                conn-a                (connect :host (:host fs-a)
                                               :port (:esl-port fs-a)
                                               :password (:esl-pass fs-a)
                                               :async-thread-type :thread)]
            ;; Setup outbound connection listener.
            (deliver b-outbound-server
                     (listen :host "127.0.0.1"
                             :port esl-outbound-port
                             :pre-init-fn (fn [conn-b chan-info]
                                            ;; Note that we are binding event handler before the channel answer.
                                            (bind-event conn-b
                                                        (fn [_ event]
                                                          (when (= (:event-name event) "CHANNEL_ANSWER")
                                                            (deliver answer-event-handled? :from-pre-init)))
                                                        :event-name "CHANNEL_ANSWER"))

                             :handler (fn [conn-b chan-info]
                                        ;; Sleep for five seconds to simulate CPU congestion.
                                        ;; Note that the call is answered after 3 seconds.
                                        (Thread/sleep 5000)

                                        ;; This event handler will not catch the answer event.
                                        (bind-event conn-b
                                                    (fn [_ event]
                                                      (when (= (:event-name event) "CHANNEL_ANSWER")
                                                        (deliver answer-event-handled? :from-conn-handler)))
                                                    :event-name "CHANNEL_ANSWER")

                                        ;; Wait for answer to close naturally.
                                        @(:closed? conn-b))
                             :async-thread-type thread-type))

            ;; Sending originate command to host A to call B ...
            (let [originate-cmd (format "originate sofia/external/%s@%s:%s/+55512345678 &socket('127.0.0.1:%s') async full"
                                        (:sip-user fs-b)
                                        (:host fs-b)
                                        (:sip-port fs-b)
                                        esl-outbound-port)]
              (is (= (select-keys (req-api conn-a originate-cmd) [:ok])
                     {:ok true})))

            ;; Check if answer event was handled from pre-init binding.
            (is (= (deref answer-event-handled? 10000 :not-handled-at-all)
                   :from-pre-init))

            ;; Closing outbound server.
            (.close @b-outbound-server)

            ;; Closing inbound connection.
            (disconnect conn-a)))))))
