(ns freeswitch-clj.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async]
            [manifold.stream :as stream]
            [freeswitch-clj.core :as fc]
            [clojure.string :as str])
  (:import [java.io IOException]
           [java.net InetSocketAddress]))

(defn get-freeswitch-connection-configs
  []
  (let [env               (System/getenv)
        fsa               {:host     (get env "FSA_HOST")
                           :esl-port (->> (get env "FSA_ESL_INBOUND_PORT")
                                          (Integer/parseInt))
                           :esl-pass (get env "FSA_ESL_INBOUND_PASS")}
        fsb               {:host     (get env "FSB_HOST")
                           :esl-port (->> (get env "FSB_ESL_INBOUND_PORT")
                                          (Integer/parseInt))
                           :esl-pass (get env "FSB_ESL_INBOUND_PASS")
                           :sip-port (->> (get env "FSB_SIP_PORT")
                                          (Integer/parseInt))
                           :sip-user (get env "FSB_SIP_USER")
                           :sip-pass (get env "FSB_SIP_PASS")}
        esl-outbound-host (get env "FSCLJ_ESL_OUTBOUND_HOST")
        esl-outbound-port (->> (get env "FSCLJ_ESL_OUTBOUND_PORT")
                               (Integer/parseInt))]
    {:fsa               fsa
     :fsb               fsb
     :esl-outbound-host esl-outbound-host
     :esl-outbound-port esl-outbound-port}))


(deftest test-fs-inbound-session
  (let [{:keys [fsa]} (get-freeswitch-connection-configs)]
    (doseq [thread-type [:thread :go-block]]
      (testing (format "Creating inbound connection to freeswitch host A with thread type %s ..."
                       thread-type)
        (let [conn (fc/connect :host (:host fsa)
                               :port (:esl-port fsa)
                               :password (:esl-pass fsa)
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

            (testing "Trying event capture with generalized handler ..."
              ;; Catch background_job with a generalized handler.
              (let [beacon (promise)]
                ;; Reset event handlers.
                (fc/clear-all-event-handlers conn)

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
                (fc/clear-all-event-handlers conn)

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
  (let [{:keys [fsa
                fsb
                esl-outbound-host
                esl-outbound-port]} (get-freeswitch-connection-configs)]
    (doseq [thread-type [:thread :go-block]]
      (testing (format "Testing outbound connection mode with thread type %s ..."
                       thread-type)
        (testing "Testing if outbound mode works ..."
          (let [connected-with-b? (promise)
                outbound-server   (promise)
                conn              (fc/connect :host (:host fsa)
                                              :port (:esl-port fsa)
                                              :password (:esl-pass fsa)
                                              :async-thread-type :thread)]
            (try
              (testing "Setting up server for outbound connections from host B ..."
                (deliver outbound-server
                         (fc/listen :host esl-outbound-host
                                    :port esl-outbound-port
                                    :handler (fn [conn chan-info]
                                               (deliver connected-with-b? true))
                                    :async-thread-type thread-type)))

              (try
                (testing "Sending originate command to host A to call B ..."
                  (let [originate-cmd (format "originate {ignore_early_media=false,sip_auth_username='%s',sip_auth_password='%s'}sofia/external/%s@%s:%s &socket('%s:%s async full')"
                                              (:sip-user fsb)
                                              (:sip-pass fsb)
                                              "5550001234"
                                              (:host fsb)
                                              (:sip-port fsb)
                                              esl-outbound-host
                                              esl-outbound-port)]
                    (is (= (select-keys (fc/req-api conn originate-cmd) [:ok])
                           {:ok true}))))

                (testing "Checking if outbound connection was made within 10 seconds ..."
                  (is (= (deref connected-with-b? 10000 false) true))))

              (finally
                ;; Closing previous outbound server.
                (.close @outbound-server)))))

        (testing "Testing if :pre-init-fn works as intended ..."
          (let [answer-event-handled? (promise)
                outbound-server       (promise)
                conn                  (fc/connect :host (:host fsa)
                                                  :port (:esl-port fsa)
                                                  :password (:esl-pass fsa)
                                                  :async-thread-type :thread)]
            (try
              ;; Setup outbound connection listener.
              (deliver outbound-server
                       (fc/listen :host esl-outbound-host
                                  :port esl-outbound-port
                                  :pre-init-fn (fn [conn chan-info]
                                                 ;; Note that we are binding event handler before the channel answer.
                                                 (fc/bind-event conn
                                                                (fn [_ event]
                                                                  (when (= (:event-name event) "CHANNEL_ANSWER")
                                                                    (deliver answer-event-handled? :from-pre-init)))
                                                                :event-name "CHANNEL_ANSWER"))

                                  :handler (fn [conn chan-info]
                                             ;; Sleep for five seconds to simulate CPU congestion.
                                             ;; Note that the call is answered after 3 seconds.
                                             (Thread/sleep 5000)

                                             ;; This event handler will not catch the answer event.
                                             (fc/bind-event conn
                                                            (fn [_ event]
                                                              (when (= (:event-name event) "CHANNEL_ANSWER")
                                                                (deliver answer-event-handled? :from-conn-handler)))
                                                            :event-name "CHANNEL_ANSWER")

                                             ;; Wait for answer to close naturally.
                                             @(:closed? conn))
                                  :async-thread-type thread-type))
              (try
                ;; Sending originate command to host A to call B ...
                (let [originate-cmd (format "originate sofia/external/%s@%s:%s/+55512345678 &socket('%s:%s') async full"
                                            (:sip-user fsb)
                                            (:host fsb)
                                            (:sip-port fsb)
                                            esl-outbound-host
                                            esl-outbound-port)]
                  (is (= (select-keys (fc/req-api conn originate-cmd) [:ok])
                         {:ok true})))

                ;; Check if answer event was handled from pre-init binding.
                (is (= (deref answer-event-handled? 10000 :not-handled-at-all)
                       :from-pre-init))

                (finally
                  ;; Closing outbound server.
                  (.close @outbound-server)))

              (finally
                ;; Closing inbound connection.
                (fc/disconnect conn)))))))))


(defn test-fs-inbound-connection-disruption
  []
  (testing "Testing graceful handling of fs-inbound connection disruption ..."
    (let [{:keys [fsa]} (get-freeswitch-connection-configs)
          conn          (fc/connect :host (:host fsa)
                                    :port (:esl-port fsa)
                                    :password (:esl-pass fsa))]

      ;; Close the connection from low level to simulate disruption.
      (stream/close! (:aleph-stream conn))

      ;; Trying to send a status command should raise IOException.
      (is (thrown? IOException
                   (select-keys (fc/req-api conn "status") [:ok]))))))


(deftest test-fs-outbound-connection-disruption
  (testing "Testing graceful handling of fs-outbound connection disruption ..."
    (let [{:keys [fsa
                  fsb
                  esl-outbound-host
                  esl-outbound-port]} (get-freeswitch-connection-configs)]
      (testing "Testing if outbound mode works ..."
        (let [received-exception (promise)
              outbound-server    (promise)
              conn               (fc/connect :host (:host fsa)
                                             :port (:esl-port fsa)
                                             :password (:esl-pass fsa)
                                             :async-thread-type :thread)]
          (try
            (deliver outbound-server
                     (fc/listen :host esl-outbound-host
                                :port esl-outbound-port
                                :handler (fn [conn chan-info]
                                           ;; Deliberately close the connection from
                                           ;; low level to simulate disruption.
                                           (stream/close! (:aleph-stream conn))

                                           ;; Try to send a simple status command.
                                           (try
                                             (fc/req-api conn "status")
                                             (catch IOException _
                                               (deliver received-exception :io-exception))
                                             (catch Exception _
                                               (deliver received-exception :unknown-exception))))))

            (let [originate-cmd (format "originate {ignore_early_media=false}sofia/external/%s@%s:%s &socket('%s:%s') async full"
                                        (:sip-user fsb)
                                        (:host fsb)
                                        (:sip-port fsb)
                                        esl-outbound-host
                                        esl-outbound-port)]
              (is (= (select-keys (fc/req-api conn originate-cmd) [:ok])
                     {:ok true})))

            ;; We should receive io-exception.
            (is (= (deref received-exception 10000 :no-exception)
                   :io-exception))

            (finally
              ;; Closing previous outbound server.
              (.close @outbound-server))))))))


(deftest test-connection-sharing-between-threads
  (testing "Testing connection sharing between threads ..."
    (let [{:keys [fsa]} (get-freeswitch-connection-configs)
          conn          (fc/connect :host (:host fsa)
                                    :port (:esl-port fsa)
                                    :password (:esl-pass fsa)
                                    :async-thread-type :thread)
          n-threads     100
          n-msgs        100
          create-msg    (fn [thread-id msg-id]
                          (str thread-id "-" msg-id))
          spawn-thread  (fn [thread-id]
                          (future
                            [thread-id
                             (vec (for [msg-id (range n-msgs)]
                                    (let [msg (create-msg thread-id msg-id) ]
                                      (:result (fc/req-api conn (str "eval " msg))))))]))
          futures       (doall (map spawn-thread (range n-threads)))]
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
    (let [{:keys [fsa]} (get-freeswitch-connection-configs)
          conn          (fc/connect :host (:host fsa)
                                    :port (:esl-port fsa)
                                    :password (:esl-pass fsa)
                                    :async-thread-type :thread)
          n-msgs        10000]
      (doseq [i (range n-msgs)]
        (let [resp (fc/req-api conn (str "eval " i))]
          (is (= (str i) (str/trim (:result resp)))))))))


(deftest test-on-close-fn-triggering-in-inbound-mode
  (testing "Testing if on-close function works in freeswitch-inbound mode ..."
    (let [{:keys [fsa]}   (get-freeswitch-connection-configs)
          n-on-close-call (atom 0)
          conn            (fc/connect :host (:host fsa)
                                      :port (:esl-port fsa)
                                      :password (:esl-pass fsa)
                                      :async-thread-type :thread
                                      :on-close (fn [conn]
                                                  (swap! n-on-close-call inc)))]
      ;; Close the connection.
      (fc/close conn)

      ;; Wait for connection to close.
      (deref (:closed? conn) 5000 :timed-out)

      ;; Wait for some time.
      (Thread/sleep 1000)

      ;; Check the number of times `on-close` was called.
      (is (= @n-on-close-call 1)))))


(deftest test-fs-close-fn-triggering-in-outbound-mode
  (testing "Testing if on-close function works in freeswitch-inbound mode ..."
    (let [{:keys [fsa
                  fsb
                  esl-outbound-host
                  esl-outbound-port]} (get-freeswitch-connection-configs)]
      (testing "Testing if outbound mode works ..."
        (let [outbound-server  (promise)
              outbound-conn    (promise)
              on-close-called? (promise)
              n-on-close-call  (atom 0)
              conn             (fc/connect :host (:host fsa)
                                           :port (:esl-port fsa)
                                           :password (:esl-pass fsa)
                                           :async-thread-type :thread)]
          (try
            (deliver outbound-server
                     (fc/listen :host esl-outbound-host
                                :port esl-outbound-port
                                :handler (fn [conn chan-info]
                                           (deliver outbound-conn conn)
                                           ;; Deliberately close the connection from
                                           ;; low level to simulate disruption.
                                           (stream/close! (:aleph-stream conn)))
                                :on-close (fn [conn]
                                            (swap! n-on-close-call inc)
                                            (deliver on-close-called? true))))

            (let [originate-cmd (format "originate {ignore_early_media=false,sip_auth_username='%s',sip_auth_password='%s'}sofia/external/%s@%s:%s &socket('%s:%s async full')"
                                        (:sip-user fsb)
                                        (:sip-pass fsb)
                                        "5554441234"
                                        (:host fsb)
                                        (:sip-port fsb)
                                        esl-outbound-host
                                        esl-outbound-port)]
              (is (= (select-keys (fc/req-api conn originate-cmd) [:ok])
                     {:ok true}))


              (is (= (deref on-close-called? 10000 :timed-out) true))

              ;; Wait some time.
              (Thread/sleep 1000)

              ;; Check if `on-close` was called only a single time.
              (is (= @n-on-close-call 1))

              ;; Check if outbound connection was properly closed.
              (is (= true
                     (boolean (and (realized? outbound-conn)
                                   (realized? (:closed? @outbound-conn))))))

              ;; Check if event-chan was properly closed.
              (is (= true
                     (boolean (and (realized? outbound-conn)
                                   (nil? (async/poll! (:event-chan @outbound-conn))))))))

            (finally
              ;; Closing previous outbound server.
              (.close @outbound-server))))))))


;; Callee numbers: 555444xxxx
(deftest test-disconnect-behavior-in-outbound-mode-with-callee-hangup
  (let [{:keys [fsa
                fsb
                esl-outbound-host
                esl-outbound-port]} (get-freeswitch-connection-configs)]
    (let [outbound-server  (promise)
          linger-time-sec  3
          calls-per-second 10
          n-calls          50
          records          (atom {:n-originated    0
                                  :n-connected     0
                                  :n-play-started  0
                                  :n-hanged-up     0
                                  :n-disconnected  0})
          conn             (fc/connect :host (:host fsa)
                                       :port (:esl-port fsa)
                                       :password (:esl-pass fsa)
                                       :async-thread-type :thread)
          outbound-conns   (atom [])]
      (try
        (deliver outbound-server
                 (fc/listen :host esl-outbound-host
                            :port esl-outbound-port
                            :pre-init-fn (fn [conn _]
                                           ;; Bind an event handler for CHANNEL_HANGUP event.
                                           (fc/bind-event conn
                                                          (fn [_ _]
                                                            (swap! records update :n-hanged-up inc))
                                                          :event-name "CHANNEL_HANGUP")
                                           ;; Bind a catch all event handler which silently absorbs other events.
                                           (fc/bind-event conn (fn [_ _])))
                            :custom-init-fn (fn [conn _]
                                              (fc/req-cmd conn (str "linger " linger-time-sec))
                                              (fc/req-cmd conn "myevents"))
                            :on-close (fn [conn]
                                        (swap! records update :n-disconnected inc))
                            :handler (fn [conn chan-info]
                                       (swap! records update :n-connected inc)
                                       (swap! outbound-conns conj conn)

                                       ;; We need to supply media from this side so that codec negotiation may start.
                                       (let [{:keys [ok]} (fc/req-call-execute conn (format "playback %s" "test_audio.wav"))]
                                         (when ok
                                           (swap! records update :n-play-started inc)))

                                       ;; Wait for connection to close.
                                       @(:closed? conn))))
        ;; Originate a large number of calls.
        (doseq [call-no (range n-calls)]
          (let [originate-cmd (format "originate {ignore_early_media=true,continue_on_fail=true,origination_caller_id_number='%s',sip_auth_username='%s',sip_auth_password='%s'}sofia/external/%s@%s:%s &socket('%s:%s async full')"
                                      (format "555111%04d" call-no) ; Caller ID
                                      (:sip-user fsb) ; SIP auth user
                                      (:sip-pass fsb) ; SIP auth pass
                                      (format "555444%04d" call-no) ; Callee number.
                                      (:host fsb) ; Gateway host
                                      (:sip-port fsb) ; Gateway port
                                      esl-outbound-host
                                      esl-outbound-port)]
            (fc/req-bgapi conn
                          (fn [_ {:keys [ok] :as result}]
                            (when ok
                              (swap! records update :n-originated inc)))
                          originate-cmd)
            ;; Sleep for some time so that CPS is under limit.
            (Thread/sleep (quot 1000 calls-per-second))))

        ;; call_time ~= answer_delay + playback_time + hangup_delay + linger_time
        ;;           ~= 1s + 5s + 1s + 3s
        ;;           ~= 10s
        (testing "Waiting for calls to finish ..."
          (let [max-wait-time-sec  120
                check-interval-sec 5
                n-checks           (inc (quot max-wait-time-sec check-interval-sec))]
            (doseq [_      (range n-checks)
                    :while (let [{:keys [n-originated
                                         n-connected
                                         n-play-started
                                         n-hanged-up
                                         n-disconnected]} @records]
                             (not= n-calls
                                   n-originated
                                   n-connected
                                   n-play-started
                                   n-hanged-up
                                   n-disconnected))]
              (let [{:keys [n-originated
                            n-connected
                            n-play-started
                            n-hanged-up
                            n-disconnected]} @records]
                (println (format "n: %s, orig: %s, connected: %s, play-started: %s, hanged-up: %s, disconnected: %s ..."
                                 n-calls
                                 n-originated
                                 n-connected
                                 n-play-started
                                 n-hanged-up
                                 n-disconnected)))
              (Thread/sleep (* 1000 check-interval-sec))))

          ;; Check if all the events were accounted for.
          (let [{:keys [n-originated
                        n-connected
                        n-play-started
                        n-hanged-up
                        n-disconnected]} @records]
            (is (= n-calls
                   n-originated
                   n-connected
                   n-play-started
                   n-hanged-up
                   n-disconnected))))

        (testing "Checking if resources were properly closed ..."
          (is (every? #(realized? (:closed? %)) @outbound-conns))
          (is (every? #(nil? (async/poll! (:event-chan %))) @outbound-conns)))

        (finally
          ;; Closing previous outbound server.
          (.close @outbound-server))))))


;; Callee numbers: 555555xxxx
(deftest test-disconnect-behavior-in-outbound-mode-with-caller-hangup
  (let [{:keys [fsa
                fsb
                esl-outbound-host
                esl-outbound-port]} (get-freeswitch-connection-configs)]
    (let [outbound-server  (promise)
          linger-time-sec  3
          calls-per-second 10
          n-calls          50
          records          (atom {:n-originated    0
                                  :n-connected     0
                                  :n-play-started  0
                                  :n-hanged-up     0
                                  :n-disconnected  0})
          conn             (fc/connect :host (:host fsa)
                                       :port (:esl-port fsa)
                                       :password (:esl-pass fsa)
                                       :async-thread-type :thread)
          outbound-conns   (atom [])]
      (try
        (deliver outbound-server
                 (fc/listen :host esl-outbound-host
                            :port esl-outbound-port
                            :pre-init-fn (fn [conn chan-info]
                                           (let [chan-uuid (:channel-unique-id chan-info)]
                                             ;; Bind an event handler for CHANNEL_HANGUP event.
                                             (fc/bind-event conn
                                                            (fn [_ _]
                                                              (swap! records update :n-hanged-up inc))
                                                            :event-name "CHANNEL_HANGUP"
                                                            :unique-id chan-uuid)
                                             ;; Bind a catch all event handler which silently absorbs other events.
                                             (fc/bind-event conn (fn [_ _]))))
                            :custom-init-fn (fn [conn _]
                                              (fc/req-cmd conn (str "linger " linger-time-sec))
                                              (fc/req-cmd conn "myevents"))
                            :on-close (fn [conn]
                                        (swap! records update :n-disconnected inc))
                            :handler (fn [conn chan-info]
                                       (swap! records update :n-connected inc)
                                       (swap! outbound-conns conj conn)

                                       ;; We need to supply media from this side so that codec negotiation may start.
                                       (let [{:keys [ok]} (fc/req-call-execute conn (format "playback %s" "test_audio.wav"))]
                                         (when ok
                                           (swap! records update :n-play-started inc)

                                           ;; Sleep for five seconds.
                                           (Thread/sleep 5000)

                                           ;; Hangup the call.
                                           (fc/req-call-execute conn "hangup")))

                                       ;; Wait for connection to close.
                                       @(:closed? conn))))
        ;; Originate a large number of calls.
        (doseq [call-no (range n-calls)]
          (let [originate-cmd (format "originate {ignore_early_media=true,continue_on_fail=true,origination_caller_id_number='%s',sip_auth_username='%s',sip_auth_password='%s'}sofia/external/%s@%s:%s &socket('%s:%s async full')"
                                      (format "555111%04d" call-no) ; Caller ID
                                      (:sip-user fsb) ; SIP auth user
                                      (:sip-pass fsb) ; SIP auth pass
                                      (format "555555%04d" call-no) ; Callee number.
                                      (:host fsb) ; Gateway host
                                      (:sip-port fsb) ; Gateway port
                                      esl-outbound-host
                                      esl-outbound-port)]
            (fc/req-bgapi conn
                          (fn [_ {:keys [ok] :as result}]
                            (when ok
                              (swap! records update :n-originated inc)))
                          originate-cmd)
            ;; Sleep for some time so that CPS is under limit.
            (Thread/sleep (quot 1000 calls-per-second))))

        (testing "Waiting for calls to finish ..."
          (let [max-wait-time-sec  120
                check-interval-sec 5
                n-checks           (inc (quot max-wait-time-sec check-interval-sec))]
            (doseq [_      (range n-checks)
                    :while (let [{:keys [n-originated
                                         n-connected
                                         n-play-started
                                         n-hanged-up
                                         n-disconnected]} @records]
                             (not= n-calls
                                   n-originated
                                   n-connected
                                   n-play-started
                                   n-hanged-up
                                   n-disconnected))]
              (let [{:keys [n-originated
                            n-connected
                            n-play-started
                            n-hanged-up
                            n-disconnected]} @records]
                (println (format "n: %s, orig: %s, connected: %s, play-started: %s, hanged-up: %s, disconnected: %s ..."
                                 n-calls
                                 n-originated
                                 n-connected
                                 n-play-started
                                 n-hanged-up
                                 n-disconnected)))
              (Thread/sleep (* 1000 check-interval-sec))))

          ;; Check if all the events were accounted for.
          (let [{:keys [n-originated
                        n-connected
                        n-play-started
                        n-hanged-up
                        n-disconnected]} @records]
            (is (= n-calls
                   n-originated
                   n-connected
                   n-play-started
                   n-hanged-up
                   n-disconnected))))

        (testing "Checking if resources were properly closed ..."
          (is (every? #(realized? (:closed? %)) @outbound-conns))
          (is (every? #(nil? (async/poll! (:event-chan %))) @outbound-conns)))

        (finally
          ;; Closing previous outbound server.
          (.close @outbound-server))))))


(deftest test-rude-rejection-handling
  (let [{:keys [fsb]} (get-freeswitch-connection-configs)]
    (doseq [thread-type [:thread :go-block]]
      (testing (format "Creating inbound connection to freeswitch host A with thread type %s ..."
                       thread-type)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Connection rejected.*"
             (let [conn (fc/connect :host (:host fsb)
                                    :port (:esl-port fsb)
                                    :password (:esl-pass fsb)
                                    :async-thread-type thread-type)]
               (try
                 (testing "Sending 'status' api command ..."
                   ;; Send a simple 'status' api command.
                   (is (= (select-keys (fc/req-api conn "status") [:ok])
                          {:ok true})))

                 (finally
                   (fc/close conn))))))))))


(deftest test-incorrect-esl-password-handling
  (let [{:keys [fsa]} (get-freeswitch-connection-configs)]
    (doseq [thread-type [:thread :go-block]]
      (testing (format "Creating inbound connection to freeswitch host A with thread type %s ..."
                       thread-type)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Failed to authenticate.*"
             (let [conn (fc/connect :host (:host fsa)
                                    :port (:esl-port fsa)
                                    :password "ThisIsNotTheCorrectPass"
                                    :async-thread-type thread-type)]
               (try
                 (testing "Sending 'status' api command ..."
                   ;; Send a simple 'status' api command.
                   (is (= (select-keys (fc/req-api conn "status") [:ok])
                          {:ok true})))

                 (finally
                   (fc/close conn))))))))))


(deftest test-resp-timeout-exception
  (let [{:keys [fsa]} (get-freeswitch-connection-configs)]
    (doseq [thread-type [:thread :go-block]]
      (testing (format "Creating inbound connection to freeswitch host A with thread type %s ..."
                       thread-type)
        (let [conn (fc/connect :host (:host fsa)
                               :port (:esl-port fsa)
                               :password (:esl-pass fsa)
                               :async-thread-type thread-type)]
          (try
            (testing "Sending 'msleep 10000' api command ..."
                   ;; Send a simple 'sleep' api command which will return after 10 seconds.
                   ;; But we set response timeout to 5 seconds, causing a timeout exception.
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Timeout waiting.*"
                                    (println (fc/req-api conn "msleep 10000" :resp-timeout 5.0)))))

            (finally
              (fc/close conn))))))))
