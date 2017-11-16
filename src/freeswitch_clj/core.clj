;; Filename: src/freeswitch_clj/core.clj
;; Author: Titon Barua <titanix88@gmail.com>
;; Copyright: 2017 Messrs Concitus, Dhaka, BD. <contact@concitus.com>
;;
;; This work is distributed under MIT Public License.
;; Please see the attached LICENSE file in project root.
(ns ^{:doc "Contains functions to communicate with freeswitch using ESL."
      :author "Titon Barua"}
 freeswitch-clj.core
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.set :as set]

            [clj-uuid :as uuid]
            [aleph.tcp :as tcp]
            [manifold.stream :as stream]
            [taoensso.timbre :as log]

            [freeswitch-clj.protocol :refer [decode-all
                                             encode
                                             parse-command-reply
                                             parse-api-response
                                             parse-bgapi-response
                                             parse-event]]))

(def
  ^{:private true
    :doc "This events are auto-handled by some high-level functions."}
  special-events
  #{"LOG"
    "BACKGROUND_JOB"
    "CHANNEL_EXECUTE"
    "CHANNEL_EXECUTE_COMPLETE"
    "CHANNEL_HANGUP"
    "CHANNEL_HANGUP_COMPLETE"})

(defn- log-with-conn
  "Log something with the context of conenction."
  [{:keys [mode aleph-stream] :as conn} lvl msg & args]
  (let [sdesc (.description aleph-stream)]
    (log/logf lvl
              "[%s L%s <-> R%s] %s"
              (name mode)
              (str (get-in sdesc [:sink :connection :local-address]))
              (str (get-in sdesc [:sink :connection :remote-address]))
              (str/join " " (cons msg args)))))

(defn- log-wc-debug
  "Log a debug level message with connection context."
  [conn msg & args]
  (apply log-with-conn conn :debug msg args))

(defn- log-wc-info
  "Log an info level message with connection context."
  [conn msg & args]
  (apply log-with-conn conn :info msg args))

(defn- send-str
  "Send some string data to freeswitch."
  [{:keys [connected? aleph-stream] :as conn} data]
  (if @connected?
    (stream/put! aleph-stream data)
    (throw (Exception. "Can't send data to unconnected session."))))

(defn- norm-token
  "Normalize a token, by trimming and upper-casing it."
  [tok]
  (str/upper-case (str/trim tok)))

(defn- norm-hkey
  "Normalize an event handler key."
  [hkey]
  (if (string? hkey)
    (norm-token hkey)
    (map norm-token hkey)))

(defn- detect-special-events
  "Inspect outgoing event un/subscription commands to keep tabs on special events."
  [{enabled :enabled-special-events :as conn} cmd & cmd-args]
  (let [[cmd & cmd-args] (as-> (cons cmd cmd-args) $
                               (apply str $)
                               (str/trim $)
                               (str/upper-case $)
                               (str/split $ #"\s+"))
        cmd-args' (set cmd-args)]
    (let [found (set/intersection special-events cmd-args')]
      ;; One fun fact about freeswitch protocol:
      ;; You can prefix the first token with arbitrary junk.
      ;; For example - both 'event' and 'eventsarefunny' are
      ;; acceptable. To maintain compatibility, we are doing
      ;; a starts-with? based match.
      (cond
        (str/starts-with? cmd "EVENT")
        (swap! enabled merge (zipmap found (repeat true)))

        (str/starts-with? cmd "NIXEVENT")
        (swap! enabled merge (zipmap found (repeat false)))

        (str/starts-with? cmd "NOEVENTS")
        (swap! enabled merge (zipmap special-events (repeat false)))

        :default nil))))

(defn req
  "Make a request to freeswitch.

  Args:
  * conn - Freeswitch connection.

  Returns:
  An `async/promise-chan` which returns the response when available.

  NOTE:
  This is a low level function, intended to be used by other
  high-level functions like `req-cmd` etc."
  [conn
   cmd-line
   cmd-hdrs
   cmd-body]
  (let [{:keys [rslt-chans req-index]} conn]
    (apply detect-special-events conn cmd-line)
    (log-wc-debug conn
                  (format "Sending request; cmd-line: %s, cmd-hdrs: %s, cmd-body: %s"
                          (pr-str cmd-line)
                          (pr-str cmd-hdrs)
                          (pr-str cmd-body)))
    (dosync
     (let [rchan (async/promise-chan)]
       ;; If we don't record the order of our requests, there is no way to
       ;; know which resp is for which request. We must infer the association
       ;; from the order at which responses are received, as freeswitch serves
       ;; requests on a fifo basis.
       (swap! rslt-chans assoc @req-index rchan)
       (send-str conn (encode cmd-line cmd-hdrs cmd-body))
       (alter req-index inc)
       rchan))))

(defn- init-inbound
  "Do some initiation rites in inbound mode."
  [conn])

(defn- init-outbound
  "Do some initiation rites in outbound mode."
  [conn]
  (log-wc-debug conn "Initiation rites starting ...")
  (req conn ["connect"] {} nil)
  (req conn ["linger"] {} nil)
  (req conn ["myevents"] {} nil)
  (log-wc-debug conn "Initiation rites complete."))

(declare handle-pending-events)
(defn bind-event
  "Bind a handler function to the event.

  Args:
  * conn - The connection map.
  * event-name - Name of the event.
  * handler - The event handler function. It's signature should be:
              `(fn [conn event-map])`. Handler return value does not
              matter.

  Returns:
  nil

  Note:
  * This does not send an 'event' command to freeswitch.
  * Generally, you should use it's higher-level cousin: `req-event`.
  * Only one event handler is allowed per event. New bindings
    override the old ones.
  * Events are put into a limited-sized, sliding queue. The queue
    is ordered according to the 'Event-Sequence' header."
  [conn event-name handler]
  {:pre [(fn? handler)]}
  (let [hkey (norm-hkey event-name)
        hkey (if (= hkey "ALL") :any-event hkey)]
    (swap! (:event-handlers conn) assoc hkey handler)
    (handle-pending-events conn)))

(defn unbind-event
  "Unbind the associated handler for an event.

  Args:
  * conn - The connection map.
  * event-name - Name of the event.

  Returns:
  nil"
  [conn event-name]
  (let [hkey (norm-hkey event-name)
        hkey (if (= hkey "ALL") :any-event hkey)]
    (swap! (:event-handlers conn) dissoc hkey)))

(defn bind-custom-event
  "Bind a handler function for CUSTOM event with given subclass.

  Args:
  * conn - Connection map.
  * subclass-name - Subclass name of the CUSTOM event.
  * handler - Event handler function.

  Returns:
  nil"
  [conn subclass-name handler]
  (bind-event conn (norm-hkey ["CUSTOM" subclass-name]) handler))

(defn unbind-custom-event
  "Remove handler function for CUSTOM event with given subclass.

  Args:
  * conn - Connection map.
  * subclass-name - Subclass name of the CUSTOM event.

  Returns:
  nil"
  [conn subclass-name]
  (unbind-event conn (norm-hkey ["CUSTOM" subclass-name])))

(declare disconnect)
(defn- send-password
  [{:keys [password authenticated?] :as conn} msg]
  (async/go (let [{:keys [ok]} (async/<! (req conn ["auth" password] {} nil))]
              (if-not ok
                (do (log-with-conn conn :error "Failed to authenticate.")
                    (disconnect conn)
                    (deliver authenticated? false))
                (do (log-wc-debug conn "Authenticated."
                                 (deliver authenticated? true)))))))

(defn- fulfil-result
  [{:keys [rslt-chans resp-index] :as conn} result]
  (dosync
   (async/put! (@rslt-chans @resp-index) result)
   (swap! rslt-chans dissoc @resp-index)
   (alter resp-index inc)))

(defn- enqueue-event
  [{:keys [event-queue event-queue-size] :as conn} event]
  (let [evseq (Integer/parseInt (event :Event-Sequence))]
    (swap! event-queue assoc evseq event)))

(defn- handle-event
  [{:keys [event-handlers] :as conn}
   {name :Event-Name :as event}]
  (let [hkey (case name
               "BACKGROUND_JOB" [name (event :Job-UUID)]
               "CUSTOM" [name (event :Event-Subclass)]
               "CHANNEL_EXECUTE" [name (event :Unique-ID) (event :Application-UUID)]
               "CHANNEL_EXECUTE_COMPLETE" [name (event :Unique-ID) (event :Application-UUID)]
               "CHANNEL_HANGUP" [name (event :Application-UUID)]
               "CHANNEL_HANGUP_COMPLETE" [name (event :Application-UUID)]
               name)

        ;; _ (println "Handler key is: " (norm-hkey hkey))
        handler (get @event-handlers
                     (norm-hkey hkey)
                     (get @event-handlers :any-event))]
    (if handler
      (do (handler conn event)
          true)
      (do (log-with-conn conn :warn "No handler bound for event:" (pr-str event))
          false))))

(defn- handle-pending-events
  [{:keys [event-queue event-queue-size] :as conn}]
  (let [newq (->> @event-queue
                  (remove (fn [[eseq event]]
                            (handle-event conn event)))
                  (take-last event-queue-size)
                  (into (sorted-map)))]
    (reset! event-queue newq)))

(defn- handle-disconnect-notice
  [{:keys [connected? aleph-stream] :as conn} msg]
  (log-wc-debug conn "Received disconnect-notice.")
  (dosync
   (if @connected?
     (do (.close aleph-stream)
         (ref-set connected? false)))))

(defn- create-aleph-data-consumer
  "Create a data consumer to process incoming data in an aleph stream."
  [{:keys [rx-buff aleph-stream connected? event-queue] :as conn}]
  (fn [data-bytes]
    (if (nil? data-bytes)
      ;; Handle disconnection.
      (do (log-wc-debug conn "Disconnected.")
          (dosync
           (if @connected?
             (.close aleph-stream)
             (ref-set connected? false))))

      ;; Handle incoming data.
      (let [data (String. data-bytes)
            [msgs data-rest] (decode-all (str @rx-buff data))]

        ;; Do different things based on message received.
        (doseq [m msgs]
          (let [ctype (get-in m [:envelope-headers :Content-Type])]
            (log-wc-debug conn "Received msg:" (pr-str m))
            (case ctype
              "auth/request" (send-password conn m)
              "command/reply" (fulfil-result conn (parse-command-reply m))
              "api/response" (fulfil-result conn (parse-api-response m))
              "text/event-plain" (enqueue-event conn (parse-event m))
              "text/event-json" (enqueue-event conn (parse-event m))
              "text/event-xml" (enqueue-event conn (parse-event m))
              "text/disconnect-notice" (handle-disconnect-notice conn m)
              (println "Ignoring unexpected content-type: " ctype))))
        (if-not (empty? @event-queue)
          (handle-pending-events conn))
        (reset! rx-buff data-rest)))))

(defn- create-aleph-conn-handler
  "Create an incoming connection handler to use with aleph/start-server."
  [handler event-queue-size]
  (fn [strm info]
    (let [conn {:aleph-conn-info info
                :channel-data (promise)
                :mode :fs-outbound

                :connected? (ref true)
                :aleph-stream strm
                :rx-buff (atom "")
                :req-index (ref 0)
                :resp-index (ref 0)
                :rslt-chans (atom {})

                :event-handlers (atom {})
                :event-queue (atom (sorted-map))
                :event-queue-size event-queue-size}]
      (log-wc-debug conn "Connected.")
      ;; Bind a handler for channel_data event.
      (bind-event conn
                  "CHANNEL_DATA"
                  (fn [conn event]
                    (deliver (conn :channel-data) event)))
      ;; Bind a consumer for incoming data bytes.
      (stream/consume (create-aleph-data-consumer conn)
                      (conn :aleph-stream))
      ;; Block until channel_data event is received.
      (when @(conn :channel-data)
        (init-outbound conn)
        (handler conn)))))

(defn connect
  "Make an inbound connection to freeswitch.

  Keyword args:
  * :host - (optional) Hostname or ipaddr of the freeswitch ESL server.
            Defaults to 127.0.0.1.
  * :port - (optional) Port where freeswitch is listening.
            Defaults to 8021.
  * :password - (optional) Password for freeswitch inbound connection.
                Defaults to ClueCon.
  * :event-queue-size - (Optional) Size of the event queue.
                        Defaults to 1000.

  Returns:
  A map describing the connection.

  Note:
  Blocks until authentication step is complete."
  [& {:keys [host port password event-queue-size]
      :or {host "127.0.0.1"
           port 8021
           password "ClueCon"
           event-queue-size 1000}
      :as kwargs}]
  (let [strm @(tcp/client {:host host :port port})]
    (let [conn {:host host
                :port port
                :password password
                :authenticated? (promise)
                :mode :fs-inbound

                :connected? (ref true)
                :aleph-stream strm
                :rx-buff (atom "")
                :req-index (ref 0)
                :resp-index (ref 0)
                :rslt-chans (atom {})

                :event-handlers (atom {})
                :event-queue (atom (sorted-map))
                :event-queue-size event-queue-size

                :enabled-special-events (atom (zipmap special-events (repeat false)))}]

      ;; Hook-up incoming data handler.
      (log-wc-debug conn "Connected.")
      (stream/consume (create-aleph-data-consumer conn)
                      (conn :aleph-stream))

      ;; Block until authentication step is complete.
      (if @(conn :authenticated?)
        (do (init-inbound conn)
            conn)
        (throw (ex-info "Failed to authenticate."
                        {:host (conn :host)
                         :port (conn :port)}))))))

(defn listen
  "Listen for outbound connections from freeswitch.

  Keyword args:
  * :port - Port to listen for freeswitch connections.
  * :handler - A function with signature: `(fn [conn])`. `conn`
               is a connection map which can be used with any
               requester function, like: `req-cmd`, `req-api` etc.
  * :event-queue-size - (optional) Size of the incoming event queue.
                        Defaults to 1000.

  Returns:
  An aleph server object.

  Notes:
  * Channel data is bound to `:channel-data` key of `conn`.
  * Connection auto listens for 'myevents'. But no event handler is bound.
  * To stop listening, call `.close` method of the returned server object."
  [& {:keys [port handler event-queue-size]
      :or [event-queue-size 1000]
      :as kwargs}]
  {:pre [(integer? port)
         (fn? handler)]}
  (log/info "Listening for freeswitch at port: " port)
  (tcp/start-server (create-aleph-conn-handler handler event-queue-size)
                    {:port port}))

(defn disconnect
  "Gracefully disconnect from freeswitch by sending an 'exit' command.

  Args:
  * conn - The connection map.

  Returns:
  nil"
  [conn]
  (let [{:keys [connected?]} conn]
    (if @connected?
      (do (log-wc-debug conn "Sending exit request ...")
          (req conn ["exit"] {} nil))
      (log-with-conn conn :warn "Disconnected already."))))

(defn req-cmd
  "Send a simple command request.

  Args:
  * conn - The connection map.
  * cmd - The command string including additional arguments.

  Returns:
  A response map with key `:ok` bound to a boolean value
  describing success of the operation.

  Example:
      ;; Send a 'noevents' command.
      (req-cmd conn \"noevents\")

  Note:
  Don't use this function to send special commands, like -
  'bgapi', 'sendmsg' etc. Rather use the high level functions
  provided for each."
  [conn
   cmd]
  (let [m (re-find #"(?i)^\s*(bgapi|sendmsg|sendevent)" cmd)]
    (if m
      (throw
       (IllegalArgumentException.
        (format "Please use req-%s function instead." (m 1))))
      (async/<!! (req conn [cmd] {} nil)))))

(defn req-api
  "Convenience function to make an api request.

  Args:
  * conn - The connection map.
  * api-cmd - Api command string with arguments.

  Returns:
  A response map with following keys:
      * :ok - Whether the operation succeeded.
      * :result - The result of the api request.

  Example:
      ;; Send a 'status' api request.
      (println (req-api conn \"status\"))
  "
  [conn
   api-cmd]
  (let [cmd-line ["api" api-cmd]]
    (async/<!! (req conn cmd-line {} nil))))

(defn req-bgapi
  "Make a background api request.

  Args:
  * conn - The connection map.
  * api-cmd : Api command string with arguments.
  * handler - Result handler function. Signature is: `(fn [conn rslt])`.
              `rslt` is a map with following keys:
                * :ok - Designates success of api operation.
                * :result - Result of the api command.
                * :event - The event which delivered the result.

  Returns:
  The command response (not the api result).

  Example:
      ;; Execute a 'status' api request in background.
      (req-bgapi
        conn
        \"status\"
        (fn [conn rslt] (println rslt)))
  "
  [conn
   api-cmd
   handler]
  (let [{:keys [enabled-special-events]} conn]
    ;; Ask freeswitch to send us BACKGROUND_JOB events.
    (if-not (@enabled-special-events "BACKGROUND_JOB")
      (req conn ["event" "BACKGROUND_JOB"] {} nil))
    (let [job-uuid (str (uuid/v1))
          cmd-line ["bgapi" api-cmd]
          cmd-hdrs {:Job-UUID job-uuid}
          handler' (fn [con event]
                     (handler conn (parse-bgapi-response event)))]
      ;; By providing our own generated uuid, we can bind an
      ;; event handler before the response is generated. Relieing on
      ;; freeswitch generated uuid results in event handler function
      ;; being ran twich for jobs which complete too fast.
      (bind-event conn ["BACKGROUND_JOB" job-uuid]  handler')
      (let [{:keys [Job-UUID] :as rslt} (async/<!! (req conn cmd-line cmd-hdrs nil))]
        (if Job-UUID
          ;; Just a sanity check.
          (assert (= (norm-token Job-UUID)
                     (norm-token job-uuid)))
          ;; Remove the binding for a failed command.
          (unbind-event conn ["BACKGROUND_JOB" job-uuid]))
        rslt))))

(defn req-event
  "Request to listen for an event and bind a handler for it.

  Args:
  * conn - The connection map.
  * event-name - Name of the event.
  * subclass-name - (optional) Subclass name. Applicable only if
                    `event-name` is set to `CUSTOM`.
  * handler - Event handler function with signature:
              `(fn [conn event-map])`.

  Returns:
  Response of the event command.

  Note:
  Setting an event handler for event `ALL` will change the default
  event handler. This handler does not receive all events; rather
  it receives events missing an explicit handler.

  Examples:
     ;; Listen for a regular event.
     (req-event
       conn
       \"CALL_UPDATE\"
       (fn [conn event] (println \"Got a call update!\")))

     ;; Listen for a custom event.
     (req-event
       conn
       \"CUSTOM\"
       \"menu:enter\"
       (fn [conn event] (println \"Inside a menu!\")))

     ;; Listen for all events and setup a default event handler.
     (req-event
       conn
       \"ALL\"
       (fn [conn event] (println event)))
  "
  ([conn event-name handler]
   {:pre [(fn? handler)]}
   (bind-event conn event-name handler)
   (let [cmd-line ["event" event-name]
         {:keys [ok] :as rslt} (async/<!! (req conn cmd-line {} nil))]
     ;; Unbind event handler if 'event' command failed.
     (when-not ok
       (unbind-event conn event-name))
     rslt))

  ([conn
    event-name
    subclass-name
    handler]
   {:pre [(fn? handler)
          (= (norm-token event-name) "CUSTOM")]}
   (bind-custom-event conn subclass-name handler)
   (let [cmd-line ["event" event-name subclass-name]
         {:keys [ok] :as rslt} (async/<!! (req conn cmd-line {} nil))]
     ;; Unbind event handler if 'event' command failed.
     (when-not ok
       (unbind-custom-event conn subclass-name))
     rslt)))

(defn req-sendevent
  "Send a generated event to freeswitch.

  Args:
  * conn - The connection map.
  * event-name - The name of the event.

  Keyword args:
  * :body - (optional) The body of the event.
  * Any other keyword arguments are treated as headers for the event.

  Returns:
  Response of the command.
  "
  [conn
   event-name
   & {:keys [body] :as event-headers}]
  (let [cmd-line ["sendevent" event-name]
        cmd-hdrs (dissoc event-headers :body)
        cmd-body body]
    (async/<!! (req cmd-line cmd-hdrs cmd-body))))

(defn req-sendmsg
  "Make a 'sendmsg' request to control a call.

  Args:
  * conn - The connection map.

  Keyword args:
  * :chan-uuid - The UUID of target channel. Not required in outbound mode.
  * :body - (optional) Body of the message.
  * Any other keyword arguments are treated as headers for the message.

  Returns:
  Reponse of the command.

  Note:
  To execute a dialplan app or hangup the call, use higher
  level funcs like `req-call-execute` which provide automated
  event listener setup.
  "
  [conn
   & {:keys [chan-uuid body event-lock]
      :or [event-lock false]
      :as headers}]
  (let [cmd-line (if chan-uuid
                   ["sendmsg" chan-uuid]
                   ["sendmsg"])
        cmd-hdrs (dissoc headers :body :chan-uuid)
        cmd-body body]
    (async/<!! (req conn cmd-line cmd-hdrs cmd-body))))

(defn req-call-execute
  "Send a 'sendmsg' request to a channel (or current channel, in case
  of freeswitch-outbound mode) to execute a dialplan application.

  Args:
  * app-name - Name of the dialplan app to execute, i.e. 'playback'.
  * app-arg - Argument data to pass to the app. Pass `nil` if no data is present.

  Keyword args:
  * :chan-uuid - The UUID of the target channel. Unnecessary in outbound mode.
  * :start-handler - (optional) Function to process the CHANNEL_EXECUTE event.
  * :end-handler - (optional) Function to process the CHANNEL_EXECUTE_COMPLETE event.
  * :event-lock - (optional) Whether to execute apps in sync. Defaults to false.
  * :repeat - (optional) The number of times the app will be executed. Defaults to 1.

  Returns:
  Command response.
  "
  [conn
   app-name
   app-arg
   {:keys [chan-uuid
           start-handler
           end-handler
           event-lock
           repeat]
    :or [event-lock false
         repeat 1]
    :as kwargs}]

  (let [event-uuid (str (uuid/v1))
        {:keys [enabled-special-events]} conn]
    (when start-handler
      (when-not @(enabled-special-events "CHANNEL_EXECUTE")
        (assert (:ok (req-cmd "event" "CHANNEL_EXECUTE"))))
      (bind-event conn
                  ["CHANNEL_EXECUTE" chan-uuid event-uuid]
                  start-handler))
    (when end-handler
      (when-not @(enabled-special-events "CHANNEL_EXECUTE")
        (assert (:ok (req-cmd "event" "CHANNEL_EXECUTE"))))
      (bind-event conn
                  ["CHANNEL_EXECUTE_COMPLETE" chan-uuid event-uuid]
                  end-handler))
    (let [{:keys [ok Event-UUID] :as rslt} (req-sendmsg :chan-uuid chan-uuid
                                                        :call-command "EXECUTE"
                                                        :execute-app-name app-name
                                                        :Event-UUID event-uuid
                                                        :loops repeat
                                                        :body app-arg)]
      (assert (= (norm-token Event-UUID)
                 (norm-token event-uuid)))
      rslt)))

;; TODO: req-call-hangup
;; TODO: req-call-nomedia
