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
           {:envelope-headers {:content-type "auth/request"}
            :envelope-content nil}))

    ;; Test command reply parsing.
    (let [cmd-reply-msg (msgs 1)]
      (is (= cmd-reply-msg
             {:envelope-headers {:content-type "command/reply"
                                 :reply-text "+OK accepted"}
              :envelope-content nil}))
      (let [cmd-reply (parse-command-reply cmd-reply-msg)]
        (is (= cmd-reply
               {:ok true
                :reply-text "+OK accepted"}))))

    ;; Test api-response parsing.
    (let [api-resp-msg (msgs 2)]
      (is (= (api-resp-msg :envelope-headers)
             {:content-type "api/response"
              :content-length "330"}))
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
              :job-uuid "5caf00ab-e476-4e5a-8bea-2749d428dea2"
              :reply-text "+OK Job-UUID: 5caf00ab-e476-4e5a-8bea-2749d428dea2"})));; Let's parse a plain BACKGROUND_JOB event.

    (let [bgjob-plain (parse-event (msgs 5))]
      (is (= (dissoc bgjob-plain :body)
             {:event-name "BACKGROUND_JOB"
              :core-uuid "35a647e7-c4e9-42f5-97cc-73606bbaa258"
              :freeswitch-hostname "oscar-delu"
              :freeswitch-switchname "oscar-delu"
              :freeswitch-ipv4 "192.168.250.70"
              :freeswitch-ipv6 "::1"
              :event-date-local "2017-11-10 13:54:48"
              :event-date-gmt "Fri, 10 Nov 2017 07:54:48 GMT"
              :event-date-timestamp "1510300488443268"
              :event-calling-file "mod_event_socket.c"
              :event-calling-function "api_exec"
              :event-calling-line-number "1557"
              :event-sequence "8061"
              :job-uuid "5caf00ab-e476-4e5a-8bea-2749d428dea2"
              :job-command "status"}))
      (is (= (count (bgjob-plain :body))
             331)))

    ;; Parse xml BACKGROUND_JOB event.
    (let [bgjob-xml (parse-event (msgs 8))]
      (is (= (dissoc bgjob-xml :body)
             {:event-name "BACKGROUND_JOB"
              :core-uuid "35a647e7-c4e9-42f5-97cc-73606bbaa258"
              :freeswitch-hostname "oscar-delu"
              :freeswitch-switchname "oscar-delu"
              :freeswitch-ipv4 "192.168.250.70"
              :freeswitch-ipv6 "::1"
              :event-date-local "2017-11-10 13:55:02"
              :event-date-gmt "Fri, 10 Nov 2017 07:55:02 GMT"
              :event-date-timestamp "1510300502603269"
              :event-calling-file "mod_event_socket.c"
              :event-calling-function "api_exec"
              :event-calling-line-number "1557"
              :event-sequence "8065"
              :job-uuid "ed3dd1a8-3301-4ed4-be1a-4045ac3dd247"
              :job-command "status"}))
      (is (= (count (bgjob-xml :body))
             331)))

    ;; Parse json BACKGROUND_JOB event.
    (let [bgjob-json (parse-event (msgs 11))]
      (is (= (dissoc bgjob-json :body)
             {:event-name "BACKGROUND_JOB"
              :core-uuid "35a647e7-c4e9-42f5-97cc-73606bbaa258"
              :freeswitch-hostname "oscar-delu"
              :freeswitch-switchname "oscar-delu"
              :freeswitch-ipv4 "192.168.250.70"
              :freeswitch-ipv6 "::1"
              :event-date-local "2017-11-10 13:55:13"
              :event-date-gmt "Fri, 10 Nov 2017 07:55:13 GMT"
              :event-date-timestamp "1510300513943270"
              :event-calling-file "mod_event_socket.c"
              :event-calling-function "api_exec"
              :event-calling-line-number "1557"
              :event-sequence "8069"
              :job-uuid "fac71fdb-86f1-4b44-84c1-ae383321388d"
              :job-command "status"}))
      (is (= (count (bgjob-json :body))
             331)))

    ;; Parse a json HEARTBEAT event, which does not have a body.
    (let [event (parse-event (msgs 13))]
      (is (= (dissoc event :body)
             {:event-name "HEARTBEAT"
              :core-uuid "35a647e7-c4e9-42f5-97cc-73606bbaa258"
              :freeswitch-hostname "oscar-delu"
              :freeswitch-switchname "oscar-delu"
              :freeswitch-ipv4 "192.168.250.70"
              :freeswitch-ipv6 "::1"
              :event-date-local "2017-11-10 13:57:52"
              :event-date-gmt "Fri, 10 Nov 2017 07:57:52 GMT"
              :event-date-timestamp "1510300672183268"
              :event-calling-file "switch_core.c"
              :event-calling-function "send_heartbeat"
              :event-calling-line-number "74"
              :event-sequence "8087"
              :event-info "System Ready"
              :up-time "0 years, 0 days, 17 hours, 44 minutes, 26 seconds, 550 milliseconds, 931 microseconds"
              :freeswitch-version "1.6.19~64bit"
              :uptime-msec "63866550"
              :session-count "0"
              :max-sessions "1000"
              :session-per-sec "30"
              :session-per-sec-last "0"
              :session-per-sec-max "0"
              :session-per-sec-fivemin "0"
              :session-since-startup "0"
              :session-peak-max "0"
              :session-peak-fivemin "0"
              :idle-cpu "98.033333"}))
      (is (nil? (event :body))))))

(deftest message-encoding
  ;; Test header encoding.
  (let [hdrs {:A "a", :B "hello  \n  world   \n \n", "C" "c"}]
    (is (= (encode-headers hdrs)
           (str "A: a"
                "\nB: hello world"
                "\nC: c"))))

  ;; Encode a simple command, without any headers.
  (is (= (encode ["only \n  " "  thing   \n   \n    "] {} nil)
         "only thing\n\n"))

  ;; Encode a command with some headers, without content.
  (is (= (encode ["something"]
                 {:foo "bar", :alice "bob"}
                 nil)
         (str "something"
              "\nalice: bob"
              "\nfoo: bar"
              "\n\n")))

  ;; Encode a command with headers and content.)
  (is (= (encode ["something"]
                 {:foo "bar", :alice "bob"}
                 "some content")
         (str "something"
              "\nalice: bob"
              "\nfoo: bar"
              "\nContent-Length: 12"
              "\n\nsome content")))

  ;; Encode a (theoretical) command without headers, with content.
  (is (= (encode ["something"]
                 {}
                 "some \n content\n")
         (str "something"
              "\nContent-Length: 15"
              "\n\nsome \n content\n")))

  ;; Send an unicode char in body.
  ;; Although, freeswitch is not unicode aware, AFAIK.
  (is (= (encode ["something"]
                 {:foo "bar", :alice "bob"}
                 "bangla letter ka: \u2453")
         (str "something"
              "\nalice: bob"
              "\nfoo: bar"
              "\nContent-Length: 21"
              "\n\nbangla letter ka: \u2453"))))
