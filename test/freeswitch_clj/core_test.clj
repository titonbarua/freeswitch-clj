(ns freeswitch-clj.core-test
  (:require [clojure.test :refer :all]
            [taoensso.timbre :as log]
            [manifold.stream :as stream]
            [freeswitch-clj.core :as fc]
            [clojure.string :as str])
  (:import [java.io IOException]))

(log/merge-config! {:level :warn})

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
        (let [conn (fc/connect :host (:host fs-a)
                               :port (:esl-port fs-a)
                               :password (:esl-pass fs-a)
                               :async-thread-type thread-type)]
          (try
            (testing "Sending 'status' api command ..."
              ;; Send a simple 'status' api command.
              (is (= (select-keys (fc/req-api conn "status") [:ok])
                     {:ok true})))

            (testing "Sending 'status' api command with 'bgapi' ..."
              ;; Turn on a beacon when a background job is complete.
              (let [beacon (promise)
                    resp   (fc/req-bgapi conn
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
                (fc/bind-event conn
                               (fn [conn event]
                                 (deliver beacon true))
                               :event-name "BACKGROUND_JOB")

                ;; Listen for bgjob event.
                (fc/req conn ["event BACKGROUND_JOB"] {} nil)
                ;; Make a bgapi request.
                (fc/req conn ["bgapi" "status"] {} nil)
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
                (fc/bind-event conn
                               (fn [conn event]
                                 (deliver beacon :catch-all-stray)))

                ;; Bind a general handler for bgjob.
                (fc/bind-event conn
                               (fn [conn event]
                                 (deliver beacon :general))
                               :event-name "BACKGROUND_JOB")

                ;; Bind a specific bgjob handler.
                (fc/bind-event conn
                               (fn [conn event]
                                 (deliver beacon :specific))
                               :event-name "BACKGROUND_JOB"
                               :job-uuid "foobar")

                ;; Listen for bgjob event.
                (fc/req conn ["event BACKGROUND_JOB"] {} nil)
                ;; Make a bgapi request.
                (fc/req conn ["bgapi" "status"] {:job-uuid "foobar"} nil)
                ;; Wait for our beacon to light-up.
                (is (= (deref beacon 500 false)
                       :specific))))

            (finally
              (fc/close conn))))))))


(deftest test-fs-outbound-session
  (let [{:keys [fs-a fs-b esl-outbound-port]} (get-freeswitch-esl-connection-configs)]
    (doseq [thread-type [:thread :go-block]]
      (testing (format "Testing outbound connection mode with thread type %s ..."
                       thread-type)
        (testing "Testing if outbound mode works ..."
          (let [connected-with-b? (promise)
                b-outbound-server (promise)
                conn-a            (fc/connect :host (:host fs-a)
                                              :port (:esl-port fs-a)
                                              :password (:esl-pass fs-a)
                                              :async-thread-type :thread)]
            (try
              (testing "Setting up server for outbound connections from host B ..."
                (deliver b-outbound-server
                         (fc/listen :host "127.0.0.1"
                                    :port esl-outbound-port
                                    :handler (fn [conn-b chan-info]
                                               (deliver connected-with-b? true))
                                    :async-thread-type thread-type)))

              (try
                (testing "Sending originate command to host A to call B ..."
                  (let [originate-cmd (format "originate {ignore_early_media=false}sofia/external/%s@%s:%s &socket('127.0.0.1:%s') async full"
                                              (:sip-user fs-b)
                                              (:host fs-b)
                                              (:sip-port fs-b)
                                              esl-outbound-port)]
                    (is (= (select-keys (fc/req-api conn-a originate-cmd) [:ok])
                           {:ok true}))))

                (testing "Checking if outbound connection was made within 10 seconds ..."
                  (is (= (deref connected-with-b? 10000 false) true))))

              (finally
                ;; Closing previous outbound server.
                (.close @b-outbound-server)))))

        (testing "Testing if :pre-init-fn works as intended ..."
          (let [answer-event-handled? (promise)
                b-outbound-server     (promise)
                conn-a                (fc/connect :host (:host fs-a)
                                                  :port (:esl-port fs-a)
                                                  :password (:esl-pass fs-a)
                                                  :async-thread-type :thread)]
            (try
              ;; Setup outbound connection listener.
              (deliver b-outbound-server
                       (fc/listen :host "127.0.0.1"
                                  :port esl-outbound-port
                                  :pre-init-fn (fn [conn-b chan-info]
                                                 ;; Note that we are binding event handler before the channel answer.
                                                 (fc/bind-event conn-b
                                                                (fn [_ event]
                                                                  (when (= (:event-name event) "CHANNEL_ANSWER")
                                                                    (deliver answer-event-handled? :from-pre-init)))
                                                                :event-name "CHANNEL_ANSWER"))

                                  :handler (fn [conn-b chan-info]
                                             ;; Sleep for five seconds to simulate CPU congestion.
                                             ;; Note that the call is answered after 3 seconds.
                                             (Thread/sleep 5000)

                                             ;; This event handler will not catch the answer event.
                                             (fc/bind-event conn-b
                                                            (fn [_ event]
                                                              (when (= (:event-name event) "CHANNEL_ANSWER")
                                                                (deliver answer-event-handled? :from-conn-handler)))
                                                            :event-name "CHANNEL_ANSWER")

                                             ;; Wait for answer to close naturally.
                                             @(:closed? conn-b))
                                  :async-thread-type thread-type))
              (try
                ;; Sending originate command to host A to call B ...
                (let [originate-cmd (format "originate sofia/external/%s@%s:%s/+55512345678 &socket('127.0.0.1:%s') async full"
                                            (:sip-user fs-b)
                                            (:host fs-b)
                                            (:sip-port fs-b)
                                            esl-outbound-port)]
                  (is (= (select-keys (fc/req-api conn-a originate-cmd) [:ok])
                         {:ok true})))

                ;; Check if answer event was handled from pre-init binding.
                (is (= (deref answer-event-handled? 10000 :not-handled-at-all)
                       :from-pre-init))

                (finally
                  ;; Closing outbound server.
                  (.close @b-outbound-server)))

              (finally
                ;; Closing inbound connection.
                (fc/disconnect conn-a)))))))))


(defn test-fs-inbound-connection-disruption
  []
  (testing "Testing graceful handling of fs-inbound connection disruption ..."
    (let [{:keys [fs-a]} (get-freeswitch-esl-connection-configs)
          conn           (fc/connect :host (:host fs-a)
                                     :port (:esl-port fs-a)
                                     :password (:esl-pass fs-a))]

      ;; Close the connection from low level to simulate disruption.
      (stream/close! (:aleph-stream conn))

      ;; Trying to send a status command should raise IOException.
      (is (thrown? IOException
                   (select-keys (fc/req-api conn "status") [:ok]))))))


(deftest test-fs-outbound-connection-disruption
  (testing "Testing graceful handling of fs-outbound connection disruption ..."
    (let [{:keys [fs-a fs-b esl-outbound-port]} (get-freeswitch-esl-connection-configs)]
      (testing "Testing if outbound mode works ..."
        (let [received-exception (promise)
              b-outbound-server  (promise)
              conn-a             (fc/connect :host (:host fs-a)
                                             :port (:esl-port fs-a)
                                             :password (:esl-pass fs-a)
                                             :async-thread-type :thread)]
          (try
            (deliver b-outbound-server
                     (fc/listen :host "127.0.0.1"
                                :port esl-outbound-port
                                :handler (fn [conn-b chan-info]
                                           ;; Deliberately close the connection from
                                           ;; low level to simulate disruption.
                                           (stream/close! (:aleph-stream conn-b))

                                           ;; Try to send a simple status command.
                                           (try
                                             (fc/req-api conn-b "status")
                                             (catch IOException _
                                               (deliver received-exception :io-exception))
                                             (catch Exception _
                                               (deliver received-exception :unknown-exception))))))

            (let [originate-cmd (format "originate {ignore_early_media=false}sofia/external/%s@%s:%s &socket('127.0.0.1:%s') async full"
                                        (:sip-user fs-b)
                                        (:host fs-b)
                                        (:sip-port fs-b)
                                        esl-outbound-port)]
              (is (= (select-keys (fc/req-api conn-a originate-cmd) [:ok])
                     {:ok true})))

            ;; We should receive io-exception.
            (is (= (deref received-exception 10000 :no-exception)
                   :io-exception))

            (finally
              ;; Closing previous outbound server.
              (.close @b-outbound-server))))))))


(deftest test-connection-sharing-between-threads
  (testing "Testing connection sharing between threads ..."
    (let [{:keys [fs-a]} (get-freeswitch-esl-connection-configs)
          conn           (fc/connect :host (:host fs-a)
                                     :port (:esl-port fs-a)
                                     :password (:esl-pass fs-a)
                                     :async-thread-type :thread)
          n-threads      100
          n-msgs         100
          create-msg     (fn [thread-id msg-id]
                           (str thread-id "-" msg-id))
          spawn-thread   (fn [thread-id]
                           (future
                             [thread-id
                              (vec (for [msg-id (range n-msgs)]
                                     (let [msg (create-msg thread-id msg-id) ]
                                       (:result (fc/req-api conn (str "eval " msg))))))]))
          futures        (doall (map spawn-thread (range n-threads)))]
      ;; Wait for 5 seconds for all the messages to be delivered.
      (Thread/sleep 5000)

      ;; Check if all futures were delivered.
      (is (= (boolean (every? realized? futures)) true)
          "Not every thread completed within allocated time.")

      ;; Check if own msgs were received in order in every thread and no thread
      ;; received msg from other ones.
      (doseq [f futures]
        (let [[thread-id received-msgs] (deref f)]
          (is (= (vec (for [mid (range n-msgs)]
                        (create-msg thread-id mid)))
                 received-msgs)))))))


(deftest test-connection-hammering
  (testing "Testing connection hammering ..."
    ;; This test can fail if responses are not received in order.
    (let [{:keys [fs-a]} (get-freeswitch-esl-connection-configs)
          conn           (fc/connect :host (:host fs-a)
                                     :port (:esl-port fs-a)
                                     :password (:esl-pass fs-a)
                                     :async-thread-type :thread)
          n-msgs         10000]
      (doseq [i (range n-msgs)]
        (let [resp (fc/req-api conn (str "eval " i))]
          (is (= (str i) (str/trim (:result resp)))))))))


(deftest test-on-close-fn-triggering-in-inbound-mode
  (testing "Testing if on-close function works in freeswitch-inbound mode ..."
    (let [{:keys [fs-a]}   (get-freeswitch-esl-connection-configs)
          on-close-called? (atom false)
          conn             (fc/connect :host (:host fs-a)
                                       :port (:esl-port fs-a)
                                       :password (:esl-pass fs-a)
                                       :async-thread-type :thread
                                       :on-close (fn [fscon]
                                                   (reset! on-close-called? true)))]

      ;; Close the connection.
      (fc/close conn)

      ;; Wait for connection to close.
      (deref (:closed? conn) 5000 :timed-out)

      (is (= @on-close-called? true)))))


(deftest test-fs-close-fn-triggering-in-outbound-mode
  (testing "Testing if on-close function works in freeswitch-inbound mode ..."
    (let [{:keys [fs-a fs-b esl-outbound-port]} (get-freeswitch-esl-connection-configs)]
      (testing "Testing if outbound mode works ..."
        (let [b-outbound-server (promise)
              on-close-called?  (promise)
              conn-a            (fc/connect :host (:host fs-a)
                                            :port (:esl-port fs-a)
                                            :password (:esl-pass fs-a)
                                            :async-thread-type :thread)]
          (try
            (deliver b-outbound-server
                     (fc/listen :host "127.0.0.1"
                                :port esl-outbound-port
                                :handler (fn [conn-b chan-info]
                                           ;; Deliberately close the connection from
                                           ;; low level to simulate disruption.
                                           (stream/close! (:aleph-stream conn-b)))
                                :on-close (fn [fscon]
                                            (deliver on-close-called? true))))

            (let [originate-cmd (format "originate {ignore_early_media=false}sofia/external/%s@%s:%s &socket('127.0.0.1:%s') async full"
                                        (:sip-user fs-b)
                                        (:host fs-b)
                                        (:sip-port fs-b)
                                        esl-outbound-port)]
              (is (= (select-keys (fc/req-api conn-a originate-cmd) [:ok])
                     {:ok true}))


              (is (= (deref on-close-called? 10000 :timed-out) true)))

            (finally
              ;; Closing previous outbound server.
              (.close @b-outbound-server))))))))
