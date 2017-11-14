(ns freeswitch-clj.protocol-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :refer [resource]]
            [freeswitch-clj.protocol :refer :all]

            [digest :refer [md5]]))

(deftest message-decoding
  (let [data (slurp (resource "test_samples/freeswitch_session.txt"))
        [msgs data-rest] (decode-all data)]

    ;; Check for an specific sample session.
    (is (= (md5 data)
           "02fe3b770f113faf1807434b6bb3644f"))

    (is (= data-rest "")
        "All the messages must be decoded.")

    (is (= (msgs 0)
           {:envelope-headers {:Content-Type "auth/request"}
            :envelope-content nil}))

    ;; Test command reply parsing.
    (let [cmd-reply-msg (msgs 1)]
      (is (= cmd-reply-msg
             {:envelope-headers {:Content-Type "command/reply"
                                 :Reply-Text "+OK accepted"}
              :envelope-content nil}))
      (let [cmd-reply (parse-command-reply cmd-reply-msg)]
        (is (= cmd-reply
               {:ok true
                :Reply-Text "+OK accepted"}))))

    ;; Test api-response parsing.
    (let [api-resp-msg (msgs 2)]
      (is (= (api-resp-msg :envelope-headers)
             {:Content-Type "api/response"
              :Content-Length "330"}))
      (is (= (count (api-resp-msg :envelope-content))
             330))
      (let [api-resp (parse-api-response api-resp-msg)]
        (is (= (api-resp :ok)
               true))
        (is (= (count (api-resp :result))
               330))))

    ;; Another command reply parsing test. This time having a Job-UUID attached..
    (let [cmd-reply (parse-command-reply (msgs 4))]
      (is (= cmd-reply
             {:ok true
              :Job-UUID "5caf00ab-e476-4e5a-8bea-2749d428dea2"
              :Reply-Text "+OK Job-UUID: 5caf00ab-e476-4e5a-8bea-2749d428dea2"})));; Let's parse a plain BACKGROUND_JOB event.

    (let [bgjob-plain (parse-event (msgs 5))]
      (is (= (dissoc bgjob-plain :body)
             {:Event-Name "BACKGROUND_JOB"
              :Core-UUID "35a647e7-c4e9-42f5-97cc-73606bbaa258"
              :FreeSWITCH-Hostname "oscar-delu"
              :FreeSWITCH-Switchname "oscar-delu"
              :FreeSWITCH-IPv4 "192.168.250.70"
              :FreeSWITCH-IPv6 "::1"
              :Event-Date-Local "2017-11-10 13:54:48"
              :Event-Date-GMT "Fri, 10 Nov 2017 07:54:48 GMT"
              :Event-Date-Timestamp "1510300488443268"
              :Event-Calling-File "mod_event_socket.c"
              :Event-Calling-Function "api_exec"
              :Event-Calling-Line-Number "1557"
              :Event-Sequence "8061"
              :Job-UUID "5caf00ab-e476-4e5a-8bea-2749d428dea2"
              :Job-Command "status"}))
      (is (= (count (bgjob-plain :body))
             331)))

    ;; Parse xml BACKGROUND_JOB event.
    (let [bgjob-xml (parse-event (msgs 8))]
      (is (= (dissoc bgjob-xml :body)
             {:Event-Name "BACKGROUND_JOB"
              :Core-UUID "35a647e7-c4e9-42f5-97cc-73606bbaa258"
              :FreeSWITCH-Hostname "oscar-delu"
              :FreeSWITCH-Switchname "oscar-delu"
              :FreeSWITCH-IPv4 "192.168.250.70"
              :FreeSWITCH-IPv6 "::1"
              :Event-Date-Local "2017-11-10 13:55:02"
              :Event-Date-GMT "Fri, 10 Nov 2017 07:55:02 GMT"
              :Event-Date-Timestamp "1510300502603269"
              :Event-Calling-File "mod_event_socket.c"
              :Event-Calling-Function "api_exec"
              :Event-Calling-Line-Number "1557"
              :Event-Sequence "8065"
              :Job-UUID "ed3dd1a8-3301-4ed4-be1a-4045ac3dd247"
              :Job-Command "status"}))
      (is (= (count (bgjob-xml :body))
             331)))

    ;; Parse json BACKGROUND_JOB event.
    (let [bgjob-json (parse-event (msgs 11))]
      (is (= (dissoc bgjob-json :body)
             {:Event-Name "BACKGROUND_JOB"
              :Core-UUID "35a647e7-c4e9-42f5-97cc-73606bbaa258"
              :FreeSWITCH-Hostname "oscar-delu"
              :FreeSWITCH-Switchname "oscar-delu"
              :FreeSWITCH-IPv4 "192.168.250.70"
              :FreeSWITCH-IPv6 "::1"
              :Event-Date-Local "2017-11-10 13:55:13"
              :Event-Date-GMT "Fri, 10 Nov 2017 07:55:13 GMT"
              :Event-Date-Timestamp "1510300513943270"
              :Event-Calling-File "mod_event_socket.c"
              :Event-Calling-Function "api_exec"
              :Event-Calling-Line-Number "1557"
              :Event-Sequence "8069"
              :Job-UUID "fac71fdb-86f1-4b44-84c1-ae383321388d"
              :Job-Command "status"}))
      (is (= (count (bgjob-json :body))
             331)))

    ;; Parse a json HEARTBEAT event, which does not have a body.
    (let [event (parse-event (msgs 13))]
      (is (= (dissoc event :body)
             {:Event-Name "HEARTBEAT"
              :Core-UUID "35a647e7-c4e9-42f5-97cc-73606bbaa258"
              :FreeSWITCH-Hostname "oscar-delu"
              :FreeSWITCH-Switchname "oscar-delu"
              :FreeSWITCH-IPv4 "192.168.250.70"
              :FreeSWITCH-IPv6 "::1"
              :Event-Date-Local "2017-11-10 13:57:52"
              :Event-Date-GMT "Fri, 10 Nov 2017 07:57:52 GMT"
              :Event-Date-Timestamp "1510300672183268"
              :Event-Calling-File "switch_core.c"
              :Event-Calling-Function "send_heartbeat"
              :Event-Calling-Line-Number "74"
              :Event-Sequence "8087"
              :Event-Info "System Ready"
              :Up-Time "0 years, 0 days, 17 hours, 44 minutes, 26 seconds, 550 milliseconds, 931 microseconds"
              :FreeSWITCH-Version "1.6.19~64bit"
              :Uptime-msec "63866550"
              :Session-Count "0"
              :Max-Sessions "1000"
              :Session-Per-Sec "30"
              :Session-Per-Sec-Last "0"
              :Session-Per-Sec-Max "0"
              :Session-Per-Sec-FiveMin "0"
              :Session-Since-Startup "0"
              :Session-Peak-Max "0"
              :Session-Peak-FiveMin "0"
              :Idle-CPU "98.033333"}))
      (is (nil? (event :body))))))
