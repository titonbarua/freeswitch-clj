;; Filename: src/freeswitch_clj/core.clj
;; Author: Titon Barua <titon@vimmaniac.com>
;; Copyright: 2021 Titon Barua <titon@vimmaniac.com>
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
            [manifold.deferred :as deferred]
            [clojure.tools.logging :as log]

            [freeswitch-clj.protocol :refer [decode-all
                                             encode
                                             url-decode
                                             parse-command-reply
                                             parse-api-response
                                             parse-bgapi-response
                                             parse-event]])
  (:import [java.io IOException]
           [java.lang Throwable]
           [clojure.lang PersistentQueue]))


(def warn-on-handler-less-event? (atom true))
(def default-server-response-timeout 30)

(declare connect)


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
  (let [sdesc (stream/description aleph-stream)]
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


(defn- norm-token
  "Normalize a token, by trimming and upper-casing it."
  [tok]
  (str/upper-case (str/trim (str tok))))


(defn- norm-kv
  "Convert a key-val pair into a normalized string, joined by colon."
  [[k v]]
  (str (norm-token (name k))
       ":"
       (norm-token (str v))))


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

        (str/starts-with? cmd "MYEVENTS")
        (swap! enabled merge (zipmap special-events (repeat true)))

        :else nil))))


(defn- create-outgoing-stream
  "Returns a new stream which take a vector of form `[data resp-chan]`,
  puts the `resp-chan` into back of `resp-chans-queue-atom` and forwards
  the data to `aleph-stream`."
  [resp-chans-queue-atom aleph-stream]
  (let [new-stream (stream/stream 0
                                  (map (fn [[data resp-chan]]
                                         (swap! resp-chans-queue-atom conj resp-chan)
                                         data)))]
    (stream/connect new-stream aleph-stream {:downstream? true
                                             :upstream?   true})
    new-stream))


(defn close
  "Close a freeswitch connection.

  __Note:__

  Normally, you should use [[disconnect]] function to
  gracefully disconnect, which sends protocol epilogue."
  [{:keys [aleph-stream
           outgoing-stream
           event-chan
           resp-chans-queue-atom
           on-close-fn
           closed?] :as conn}]
  ;; Close event channel.
  (async/close! event-chan)

  ;; Close all response channels.
  (doseq [resp-chan @resp-chans-queue-atom]
    (async/close! resp-chan))

  ;; Close aleph stream, if not already closed.
  (when-not (realized? closed?)
    (deliver closed? true)
    (stream/close! aleph-stream)
    (stream/close! outgoing-stream)
    (when on-close-fn
      (try
        (on-close-fn conn)
        (catch Exception e
          (log/warn e "Ignored exception in on-close function."))))))


(defmacro close-conn-on-error
  [conn & body]
  `(try
     ~@body
     (catch Throwable e#
       (log-with-conn ~conn :warn "Closing connection due to exception.")
       (close ~conn)
       (throw e#))))


(defn req
  "Make a request to freeswitch.

  __Args:__

  * `conn` - Freeswitch connection.
  * `cmd-line` - The request to send.
  * `cmd-hdrs` - Request headers map.
  * `cmd-body` - The body of the request.

  __Returns:__

  An `async/promise-chan` which returns the response when available.

  __Note:__

  This is a low level function, intended to be used by other
  high-level functions like `req-cmd` etc."
  [conn
   cmd-line
   cmd-hdrs
   cmd-body]
  (close-conn-on-error
   conn
   (let [{:keys [closed?
                 outgoing-stream]} conn]
     (apply detect-special-events conn cmd-line)
     (log-wc-debug conn
                   (format "Sending request; cmd-line: %s, cmd-hdrs: %s, cmd-body: %s"
                           (pr-str cmd-line)
                           (pr-str cmd-hdrs)
                           (pr-str cmd-body)))
     (let [resp-chan (async/promise-chan)
           data      [(encode cmd-line cmd-hdrs cmd-body) resp-chan]]
       (if-not (realized? closed?)
         @(stream/put! outgoing-stream data)
         (throw (IOException. "Can't send data through closed connection.")))
       resp-chan))))



(defn req-sync
  "Make a request to freeswitch and wait for response.

  __Args:__

  * `conn` - Freeswitch connection.
  * `cmd-line` - The request to send.
  * `cmd-hdrs` - Request headers map.
  * `cmd-body` - The body of the request.

  __Kwargs:__
  * `:resp-timeout` - (optional) Seconds to wait for server response, before throwing an exception.
                      Defaults to `30` seconds.

  __Returns:__

  Returns the response when available.

  __Note:__

  This is a low level function, intended to be used by other
  high-level functions like `req-cmd` etc."
  [conn
   cmd-line
   cmd-hdrs
   cmd-body
   & {:keys [resp-timeout]
      :or   {resp-timeout default-server-response-timeout}}]
  (close-conn-on-error
   conn
   ;; Send request and wait for response upto `timeout-ms` time.
   (let [resp-chan    (req conn cmd-line cmd-hdrs cmd-body)
         timeout-chan (async/timeout (int (* resp-timeout 1000.0)))
         [resp chan]  (async/alts!! [resp-chan timeout-chan] :priority true)]
     (if (= chan resp-chan)
       resp
       (throw (ex-info "Timeout waiting for response from server."
                       {:conn     conn
                        :cmd-line cmd-line
                        :cmd-hdrs cmd-hdrs
                        :cmd-body cmd-body}))))))


(defn- init-inbound
  "Do some initiation rites in inbound mode."
  [conn])


(defn- init-outbound
  "Do some initiation rites in outbound mode."
  [{:keys [resp-timeout] :as conn} chan-data]
  (log-wc-debug conn "Initiation rites starting ...")
  ;; NOTE: Without linger, hangup/error events are not sent and freeswitch
  ;; closes the connection prematurely.
  (req-sync conn ["linger"] {} nil :resp-timeout resp-timeout)
  ;; NOTE: Without myevents, event processing won't work.
  (req-sync conn ["myevents"] {} nil :resp-timeout resp-timeout)
  (log-wc-debug conn "Initiation rites complete."))


(defn bind-event
  "Bind a handler function to the event.

  __Args:__

  * `conn` - The connection map.
  * `handler` - The event handler function. It's signature should be:
              `(fn [conn event-map])`. Handler return value does not
              matter.

  __Kwargs:__

  All key value pairs are treated as event headers to match against.

  __Returns:__

  `nil`

  __Usage Example:__

  ```
  ;; Set a catch-all-stray event handler.
  (bind-event conn
              (fn [conn event]
                (println \"I match all stray events!\")))

  ;; Create a BACKGROUND_JOB event handler.
  (bind-event conn
              (fn [conn event]
                (println \"I match all BG_JOB events.\"))
              :event-name \"BACKGROUND_JOB\")

  ;; Create BACKGROUND_JOB event handler for a specific job-uuid.
  (bind-event conn
              (fn [conn event]
                (println \"I match BG_JOB with specific UUID.\"))
              :event-name \"BACKGROUND_JOB\"
              :job-uuid \"1234\")
  ```

  __Note:__

  * This does not send an 'event' command to freeswitch.
  * Generally, you should use it's higher-level cousin: [[req-event]].
  * Only one event handler is allowed per match criteria. New bindings
    override the old ones.
  * Specific handlers has higher priority than generalized ones.
    The catch-all-stray handler has lowest priority.
  "
  [conn
   handler
   & {:as event-headers}]
  {:pre [(fn? handler)]}
  (close-conn-on-error
   conn
   (let [hkey (set (map norm-kv event-headers))]
     (swap! (:event-handlers conn) assoc hkey handler))))


(defn unbind-event
  "Unbind the associated handler for an event.

  __Args:__

  * `conn` - The connection map.

  __Kwargs:__

  Event headers to match against.

  __Returns:__

  `nil`"
  [conn
   & {:as event-headers}]
  (close-conn-on-error
   conn
   (let [hkey (set (map norm-kv event-headers))]
     (when (empty? hkey)
       (log-with-conn conn :warn "Binding a catch-all-stray handler!"))
     (swap! (:event-handlers conn) dissoc hkey))))


(declare disconnect)
(defn- send-password
  [{:keys [password auth-status resp-timeout] :as conn} msg]
  (async/go
    (close-conn-on-error
     conn
     (let [resp-chan     (req conn ["auth" (str password)] {} nil)
           timeout-chan  (async/timeout (int (* resp-timeout 1000.0)))
           [result chan] (async/alts! [resp-chan timeout-chan] :priority true)]
       (if (= chan resp-chan)
         (if-not (:ok result)
           (do (disconnect conn)
               (deliver auth-status :auth-failure))
           (do (log-wc-debug conn "Authenticated.")
               (deliver auth-status :auth-success)))
         (deliver auth-status :auth-timeout))))))


(defn- fulfil-result
  [{:keys [resp-chans-queue-atom] :as conn} result]
  (let [resp-chan (peek @resp-chans-queue-atom)]
    (async/put! resp-chan result)
    (swap! resp-chans-queue-atom pop)))


(defn- enqueue-event
  [{:keys [event-chan] :as conn} event]
  (async/put! event-chan event))


;; How this works:
;; Event handlers are put into a map, associated with a set made
;; from all the headers they are interested in. For example,
;; Here's a sample value of the event-handlers map: {
;;   #{} <catch-all-stray-events-handler>
;;   #{"EVENT-NAME:BACKGROUND_JOB"} <general-bgjob-handler-func>
;;   #{"EVENT-NAME:BACKGROUND_JOB" "JOB-UUID:1234"} <specific-bgjob-handler>
;; }
;;
;; During matching, we transform the event into a similar set. Then
;; select those keys of event-handlers map, which are subset of the
;; event set. If multiple subset is found, we select the biggest subset
;; for more specific match.
;;
;; Note:
;; If multiple biggest subset are found, there is no guarantee
;; about which one will get selected.
(defn- dispatch-event
  [{:keys [event-handlers] :as conn} event]
  (assert (= (type @event-handlers) clojure.lang.PersistentTreeMap)
          "Event-handlers map is not a sorted-map!")
  (let [event-keys (set (map norm-kv event))
        ;; As the @event-handlers is a sorted-map, the keys are already sorted
        ;; by their in reverse order.
        hkey (->> (keys @event-handlers)
                  (some #(when (set/subset? % event-keys) %)))
        handler (get @event-handlers hkey)]
    (if handler
      (do (try
            (handler conn event)
            (catch Throwable e
              (log-with-conn conn :warn "Ignored exception inside event handler: " (str e))))
          true)
      (do (when @warn-on-handler-less-event?
            (log-with-conn conn
                           :warn
                           "Ignoring handler-less event:"
                           event
                           (event :event-name)))
          false))))


(defn- spawn-event-dispatcher
  "Create a go-block to handle incoming events."
  [async-thread-type {:keys [event-chan] :as conn}]
  (if (= async-thread-type :go-block)
    ;; Spawn a go block.
    (async/go
      (loop [event (async/<! event-chan)]
        (when event
          (dispatch-event conn event)
          (recur (async/<! event-chan)))))

    ;; Spawn a thread.
    (async/thread
      (loop [event (async/<!! event-chan)]
        (when event
          (dispatch-event conn event)
          (recur (async/<!! event-chan)))))))


(defn ack-closure
  [conn]
  (close conn))


(defn ack-drainage
  [conn]
  (close conn))


(defn- handle-disconnect-notice
  [{:keys [connected? aleph-stream] :as conn} msg]
  (log-wc-debug conn "Received disconnect-notice."))


(defn- handle-rude-rejection
  [{:keys [auth-status] :as conn} msg]
  (deliver auth-status :rude-rejection))


(defn- create-aleph-data-consumer
  "Create a data consumer to process incoming data in an aleph stream."
  [{:keys [rx-buff event-chan] :as conn}]
  (fn [^bytes data-bytes]
    (if (nil? data-bytes)
      ;; Handle disconnection.
      (do
        (log-wc-debug conn "Disconnected."))

      ;; Handle incoming data.
      (let [data (String. data-bytes)
            [msgs data-rest] (decode-all (str @rx-buff data))]

        ;; Do different things based on message received.
        (doseq [m msgs]
          (let [ctype (get-in m [:envelope-headers :content-type])]
            (log-wc-debug conn "Received msg:" (pr-str m))
            (case ctype
              "auth/request" (send-password conn m)
              "command/reply" (fulfil-result conn (parse-command-reply m))
              "text/rude-rejection" (handle-rude-rejection conn m)
              "api/response" (fulfil-result conn (parse-api-response m))
              "text/event-plain" (enqueue-event conn (parse-event m))
              "text/event-json" (enqueue-event conn (parse-event m))
              "text/event-xml" (enqueue-event conn (parse-event m))
              "text/disconnect-notice" (handle-disconnect-notice conn m)
              (println "Ignoring unexpected content-type: " ctype))))
        (reset! rx-buff data-rest)))))


(defn- setup-and-handle-connection
  [conn chan-data pre-init-fn custom-init-fn async-thread-type handler]
  ;; Call pre-init-fn, if given.
  (close-conn-on-error
   conn
   (when pre-init-fn
     (pre-init-fn conn chan-data)))

  ;; Send initiation rites.
  (close-conn-on-error
   conn
   (if custom-init-fn
     (custom-init-fn conn chan-data)
     (init-outbound conn chan-data)))

  ;; Spawn event dispatcher thread.
  (spawn-event-dispatcher async-thread-type conn)

  ;; Call connection handler.
  (close-conn-on-error
   conn
   (handler conn chan-data)))

(defn- chan-data-from-connect-reply
  [connect-reply]
  (as-> connect-reply $
    (dissoc $ :ok :body :content-type)
    (map (fn [[k v]] [k (url-decode v)]) $)
    (into {} $)))


(defn event-dispatcher-key-sort-comparator
  [key-a key-b]
  ;; We want dispatcher with larger key to come before a dispatcher with smaller key.
  (compare (vec key-b) (vec key-a)))


(defn clear-all-event-handlers
  "Clears the event handler map of a connection."
  [conn]
  (reset! (:event-handlers conn) (sorted-map-by event-dispatcher-key-sort-comparator)))


(defn- create-aleph-conn-handler
  "Create an incoming connection handler to use with aleph/start-server."
  [handler custom-init-fn pre-init-fn async-thread-type on-close incoming-buffer-size resp-timeout]
  (fn [strm info]
    (let [resp-chans-queue-atom (atom PersistentQueue/EMPTY)
          buffered-strm         (stream/buffer incoming-buffer-size strm)
          conn                  (cond-> {:aleph-conn-info info
                                         :mode            :fs-outbound

                                         :closed?               (promise)
                                         :aleph-stream          strm
                                         :rx-buff               (atom "")
                                         :resp-chans-queue-atom resp-chans-queue-atom
                                         :outgoing-stream       (create-outgoing-stream resp-chans-queue-atom strm)
                                         :resp-timeout          resp-timeout

                                         :event-handlers         (atom (sorted-map-by event-dispatcher-key-sort-comparator))
                                         :event-chan             (async/chan)
                                         :enabled-special-events (atom (zipmap special-events (repeat false)))}
                                  on-close
                                  (assoc :on-close-fn on-close))]
      (log-wc-debug conn "Connected.")

      ;; Setup callbacks to handle connection interruption.
      (stream/on-closed strm #(ack-closure conn))
      (stream/on-drained strm #(ack-drainage conn))

      ;; Bind a consumer for incoming data bytes.
      (stream/consume (create-aleph-data-consumer conn) buffered-strm)

      (if (= async-thread-type :go-block)
        ;; Run handler in a go-block.
        (async/go
          (try
            (let [resp-chan (req conn ["connect"] {} nil)
                  chan-data (-> (try
                                  (let [timeout-chan (async/timeout (int (* resp-timeout 1000.0)))
                                        [data chan]  (async/alts! [resp-chan timeout-chan] :priority true)]
                                    (if (= chan resp-chan)
                                      data
                                      (throw (ex-info "Timeout waiting for freeswitch channel data." {:conn conn})))))
                                (chan-data-from-connect-reply))]
              (setup-and-handle-connection conn
                                           chan-data
                                           pre-init-fn
                                           custom-init-fn
                                           async-thread-type
                                           handler))
            (finally
              (close conn))))

        ;; Run handler in an async thread.
        (async/thread
          (try
            (let [resp-chan (req conn ["connect"] {} nil)
                  chan-data (-> (try
                                  (let [timeout-chan (async/timeout (int (* resp-timeout 1000.0)))
                                        [data chan]  (async/alts!! [resp-chan timeout-chan] :priority true)]
                                    (if (= chan resp-chan)
                                      data
                                      (throw (ex-info "Timeout waiting for freeswitch channel data." {:conn conn})))))
                                (chan-data-from-connect-reply))]
              (setup-and-handle-connection conn
                                           chan-data
                                           pre-init-fn
                                           custom-init-fn
                                           async-thread-type
                                           handler))
            (finally
              (close conn)))))

      ;; Return the aleph stream.
      strm)))


(defn connect
  "Make an inbound connection to freeswitch.

  __Kwargs:__

  * `:host` - (optional) Hostname or ipaddr of the freeswitch ESL server.
              Defaults to `\"127.0.0.1\"`.
  * `:port` - (optional) Port where freeswitch is listening.
              Defaults to `8021`.
  * `:password` - (optional) Password for freeswitch inbound connection.
                  Defaults to `\"ClueCon\"`.
  * `:conn-timeout` - (optional) Connection timeout in seconds.
                      Defaults to `10`.
  * `:async-thread-type` - (optional) The type of thread to spawn for event
                           dispatcher. Valid values are - `thread` and `go-block`.
                           Default is - `thread`.
  * `:on-close` - (optional) A single-arity function which will be called after
                  the connection closes. Call signature is: `(fn [fscon])` .
  * `:incoming-buffer-size` - (optional) Number of messages to buffer on the
                              receive side before applying back-pressure.
                              Defaults to 32.
  * `:resp-timeout` - (optional) Seconds to wait for server response, for some necessary automatic commands.
                      Defaults to `30` seconds. Throws exception after the timeout.

  * You can add extra keyword arguments to fine tune behavior of `aleph.tcp/client` function.

  __Returns:__

  A map describing the connection.

  __Note:__

  Blocks until authentication step is complete."
  [& {:keys [host
             port
             password
             conn-timeout
             async-thread-type
             on-close
             incoming-buffer-size
             resp-timeout]
      :or   {host                 "127.0.0.1"
             port                 8021
             password             "ClueCon"
             conn-timeout         10
             async-thread-type    :thread
             incoming-buffer-size 32
             resp-timeout         default-server-response-timeout}
      :as   kwargs}]
  (let [strm                  @(-> (tcp/client (dissoc kwargs
                                                       :password
                                                       :conn-timeout
                                                       :async-thread-type
                                                       :on-close
                                                       :incoming-buffer-size
                                                       :resp-timeout))
                                   (deferred/timeout! (int (* conn-timeout 1000))))
        resp-chans-queue-atom (atom PersistentQueue/EMPTY)
        buffered-strm         (stream/buffer incoming-buffer-size strm)
        conn                  (cond-> {:host         host
                                       :port         port
                                       :password     password
                                       :conn-timeout conn-timeout
                                       :resp-timeout resp-timeout
                                       :auth-status  (promise)
                                       :mode         :fs-inbound

                                       :closed?               (promise)
                                       :aleph-stream          strm
                                       :rx-buff               (atom "")
                                       :resp-chans-queue-atom resp-chans-queue-atom
                                       :outgoing-stream       (create-outgoing-stream resp-chans-queue-atom strm)

                                       :event-handlers         (atom (sorted-map-by event-dispatcher-key-sort-comparator))
                                       :event-chan             (async/chan)
                                       :enabled-special-events (atom (zipmap special-events (repeat false)))}
                                on-close
                                (assoc :on-close-fn on-close))]
    (log-wc-debug conn "Connected.")

    (close-conn-on-error
     conn
     ;; Setup callbacks to handle connection interruption.
     (stream/on-closed strm #(ack-closure conn))
     (stream/on-drained strm #(ack-drainage conn))

     ;; Hook-up incoming data handler.
     (stream/consume (create-aleph-data-consumer conn) buffered-strm)

     (spawn-event-dispatcher async-thread-type conn)

     ;; Block until authentication step is complete.
     (case @(conn :auth-status)
       :rude-rejection
       (do (close conn)
           (throw (ex-info "Connection rejected. Please check ESL 'apply-inbound-acl' param."
                           {:host (conn :host)
                            :port (conn :port)})))

       :auth-failure
       (do (close conn)
           (throw (ex-info "Failed to authenticate."
                           {:host (conn :host)
                            :port (conn :port)})))

       :auth-timeout
       (do (close conn)
           (throw (ex-info "Timeout waiting for authentication acknowledgement."
                           {:conn conn})))

       :auth-success
       (do (init-inbound conn)
           conn)))))


(defn listen
  "Listen for outbound connections from freeswitch.

  __Kwargs:__

  * `:port` - Port to listen for freeswitch connections.
  * `:handler` - A function with signature: `(fn [conn chan-data])`.
                 `conn` is a connection map which can be used with any
                 requester function, like: [[req-cmd]], [[req-api]] etc.
                 `chan-data` is information about current channel. `conn`
                 is automatically closed after handler exits.
  * `:custom-init-fn` - (optional) A function with signature: `(fn [conn chan-data])`.
                        If provided, it will replace the builtin function which sends
                        initiation rites, like `linger` and `myevents` upon connection
                        creation.
  * `:pre-init-fn` - (optional) A function with signature: `(fn [conn chan-data])`.
                     If provided, this function is called before event dispatcher
                     is turned on and before connection initiation function is called.
                     If you predictably want to receive all early events sent by
                     freeswitch, setup your event handlers here.
  * `:async-thread-type` - (optional) A keyword indicating types of threads to spawn
                           for event handling and dispatch. Valid values are -
                           `:thread` and `:go-block`. Default is `:thread`.
  * `:on-close` - (optional) A single-arity function which will be called after
                  the connection closes. Call signature is: `(fn [fscon])` .
  * `:incoming-buffer-size` - (optional) Number of messages to buffer on the
                              receive side before applying back-pressure.
                              Defaults to 32.
  * `:resp-timeout` - (optional) Seconds to wait for server response, for some necessary automatic commands.
                      Defaults to `30` seconds. Throws exception after the timeout.

  __Returns:__

  An aleph server object.

  __Notes:__

  * Connection auto listens for 'myevents'. But no event handler is bound.
  * To stop listening for connections, call `.close` method of the returned
    server object.
  * Two threads/go-blocks are spawned to handle a each connection. If you are
    on budget, pass `:go-block` as `:async-thread-type`.
  "
  [& {:keys [port
             handler
             custom-init-fn
             pre-init-fn
             async-thread-type
             on-close
             incoming-buffer-size
             resp-timeout]
      :or   {custom-init-fn       nil
             pre-init-fn          nil
             async-thread-type    :thread
             incoming-buffer-size 32
             resp-timeout         default-server-response-timeout}
      :as   kwargs}]
  {:pre [(integer? port)
         (fn? handler)]}
  (log/info "Listening for freeswitch at port: " port)
  (tcp/start-server (create-aleph-conn-handler handler
                                               custom-init-fn
                                               pre-init-fn
                                               async-thread-type
                                               on-close
                                               incoming-buffer-size
                                               resp-timeout)
                    {:port port}))


(defn disconnect
  "Gracefully disconnect from freeswitch by sending an 'exit' command.

  __Args:__

  * `conn` - The connection map.

  __Returns:__

  `nil`"
  [conn]
  (close-conn-on-error
   conn
   (let [{:keys [closed?]} conn]
     (if-not (realized? closed?)
       (do (log-wc-debug conn "Sending exit request ...")
           (try
             (req conn ["exit"] {} nil)
             (catch IOException _ nil)))
       (log-with-conn conn :warn "Disconnected already.")))))


(defn req-cmd
  "Send a simple command request.

  __Args:__

  * `conn` - The connection map.
  * `cmd` - The command string including additional arguments.

  __Kwargs:__

  * `:resp-timeout` - (optional) Seconds to wait for server response, before throwing an exception.
                      Defaults to `30` seconds.

  __Returns:__

  A response map with key `:ok` bound to a boolean value
  describing success of the operation.

  __Usage Example:__

  ```
  ;; Send a 'noevents' command.
  (req-cmd conn \"noevents\")
  ```

  __Note:__

  Don't use this function to send special commands, like -
  'bgapi', 'sendmsg' etc. Rather use the high level functions
  provided for each."
  [conn
   cmd
   & {:keys [resp-timeout]
      :or   {resp-timeout default-server-response-timeout}}]
  (close-conn-on-error
   conn
   (let [m (re-find #"(?i)^\s*(bgapi|sendmsg|sendevent)" cmd)]
     (if m
       (throw
        (IllegalArgumentException.
         (format "Please use req-%s function instead." (m 1))))
       (req-sync conn [cmd] {} nil :resp-timeout resp-timeout)))))


(defn req-api
  "Convenience function to make an api request.

  __Args:__

  * `conn` - The connection map.
  * `api-cmd` - Api command string with arguments.

  __Kwargs:__

  * `:resp-timeout` - (optional) Seconds to wait for server response, before throwing an exception.
                      Defaults to `30` seconds.

  __Returns:__

  A response map with following keys:

  * `:ok` - Whether the operation succeeded.
  * `:result` - The result of the api request.

  __Usage Example:__

  ```
  ;; Send a 'status' api request.
  (println (req-api conn \"status\"))
  ```
  "
  [conn
   api-cmd
   & {:keys [resp-timeout]
      :or   {resp-timeout default-server-response-timeout}}]
  (close-conn-on-error
   conn
   (let [cmd-line ["api" api-cmd]]
     (req-sync conn cmd-line {} nil :resp-timeout resp-timeout))))


(defn req-bgapi
  "Make a background api request.

  __Args:__

  * `conn` - The connection map.
  * `handler` - Result handler function. Signature is: `(fn [conn rslt])`.
    `rslt` is a map with following keys:
      * `:ok` - Designates success of api operation.
      * `:result` - Result of the api command.
      * `:event` - The event which delivered the result.
  * `api-cmd` - Api command string with arguments.

  __Kwargs:__

  * `:resp-timeout` - (optional) Seconds to wait for server response, before throwing an exception.
                      Defaults to `30` seconds.

  __Returns:__

  The command response (not the api result).


  __Usage Example:__

  ```
  ;; Execute a 'status' api request in background.
  (req-bgapi conn
             (fn [conn rslt] (println rslt))
             \"status\")
  ```
  "
  [conn
   handler
   api-cmd
   & {:keys [resp-timeout]
      :or   {resp-timeout default-server-response-timeout}}]
  (close-conn-on-error
   conn
   (let [{:keys [enabled-special-events]} conn]
    ;; Ask freeswitch to send us BACKGROUND_JOB events.
     (if-not (@enabled-special-events "BACKGROUND_JOB")
       (req conn ["event" "BACKGROUND_JOB"] {} nil))
     (let [gen-job-uuid (str (uuid/v1))
           cmd-line ["bgapi" api-cmd]
           cmd-hdrs {:job-uuid gen-job-uuid}
           handler' (fn [con event]
                     ;; As the event is being processed, we don't need the
                     ;; binding anymore. Otherwise, this might cause memory leak
                     ;; for long lived connections.
                      (unbind-event conn
                                    :event-name "BACKGROUND_JOB"
                                    :job-uuid gen-job-uuid)
                      (handler conn (parse-bgapi-response event)))]
      ;; By providing our own generated uuid, we can bind an
      ;; event handler before the response is generated. Relieing on
      ;; freeswitch generated uuid results in event handler function
      ;; being ran twich for jobs which complete too fast.
       (bind-event conn
                   handler'
                   :event-name "BACKGROUND_JOB"
                   :job-uuid gen-job-uuid)
       (try
         (let [{:keys [job-uuid] :as rslt} (req-sync conn cmd-line cmd-hdrs nil :resp-timeout resp-timeout)]
           (if job-uuid
            ;; Just a sanity check.
             (assert (= (norm-token gen-job-uuid)
                        (norm-token job-uuid)))
            ;; Remove the binding for a failed command.
             (unbind-event conn
                           :event-name "BACKGROUND_JOB"
                           :job-uuid gen-job-uuid))
           rslt)
         (catch Throwable e
            ;; Remove the binding if some exception occurs.
           (unbind-event conn
                         :event-name "BACKGROUND_JOB"
                         :job-uuid gen-job-uuid)
           (throw e)))))))


(defn req-event
  "Request to listen for an event and bind a handler for it.

  __Args:__

  * `conn` - The connection map.
  * `handler` - Event handler function with signature:
                `(fn [conn event-map])`.

  __Kwargs:__

  * `:event-name` - Name of the event. Special value `ALL` means
                    subscribe to all events and the handler matches
                    any value for `:event-name.`
  * `:resp-timeout` - (optional) Seconds to wait for server response, before throwing an exception.
                      Defaults to `30` seconds.

  * All other keyword arguments are treated as event headers
    to match against. Like `:event-subclass` to match for custom
    events.

  __Returns:__

  Response of the event command.

  __Usage Examples:__

  ```
  ;; Listen for a regular event.
  (req-event
    conn
    (fn [conn event]
      (println \"Got a call update!\"))
    :event-name \"CALL_UPDATE\")

  ;; Listen for a custom event with specific subclass.
  (req-event
    conn
    (fn [conn event]
      (println \"Inside a menu!\"))
    :event-name \"CUSTOM\"
    :event-subclass \"menu:enter\")

  ;; Listen for all events and setup a catch-all-stray handler.
  (req-event
    conn
    (fn [conn event]
      (println event))
    :event-name \"ALL\")
  ```
  "
  [conn
   handler
   & {:keys [event-name
             resp-timeout]
      :or   {resp-timeout default-server-response-timeout}
      :as   event-headers}]
  {:pre [(fn? handler)
         (not (nil? event-name))]}
  (close-conn-on-error
   conn
   (let [cmd-line ["event" event-name]
         event-headers (if (= (str/lower-case (str/trim event-name)) "ALL")
                         (dissoc event-headers :event-name :resp-timeout)
                         event-headers)]
     ;; Bind a handler.
     (apply bind-event
            conn
            handler
            (flatten (seq event-headers)))

     ;; Request to listen for the event.
     (try
       (let [{:keys [ok] :as rslt} (req-sync conn cmd-line {} nil :resp-timeout resp-timeout)]
        ;; Unbind event handler if 'event' command failed.
         (when-not ok
           (apply unbind-event
                  conn
                  (flatten (seq event-headers))))
         rslt)
       (catch Throwable e
        ;; Unbind event handler if some exception happens.
         (apply unbind-event
                conn
                (flatten (seq event-headers)))
         (throw e))))))


(defn req-sendevent
  "Send a generated event to freeswitch.

  __Args:__

  * `conn` - The connection map.
  * `event-name` - The name of the event.

  __Keyword args:__

  * `:body` - (optional) The body of the event.
  * `:resp-timeout` - (optional) Seconds to wait for server response, before throwing an exception.
                      Defaults to `30` seconds.
  * Any other keyword arguments are treated as headers for the event.

  __Returns:__

  Response of the command.
  "
  [conn
   event-name
   & {:keys [body
             resp-timeout]
      :or   {resp-timeout default-server-response-timeout}
      :as   event-headers}]
  (close-conn-on-error
   conn
   (let [cmd-line ["sendevent" event-name]
         cmd-hdrs (dissoc event-headers :body :timeout-ms)
         cmd-body body]
     (req-sync conn cmd-line cmd-hdrs cmd-body :resp-timeout resp-timeout))))


(defn req-sendmsg
  "Make a 'sendmsg' request to control a call.

  __Args:__

  * `conn` - The connection map.

  __Kwargs:__

  * `:chan-uuid` - The UUID of target channel. Not required in outbound mode.
  * `:body` - (optional) Body of the message.
  * `:resp-timeout` - (optional) Seconds to wait for server response, before throwing an exception.
                      Defaults to `30` seconds.
  * Any other keyword arguments are treated as headers for the message.

  __Returns:__

  Reponse of the command.

  __Note:__

  To execute a dialplan app or hangup the call, use higher
  level funcs like [[req-call-execute]] which provide automated
  event listener setup.
  "
  [conn
   & {:keys [chan-uuid
             body
             resp-timeout]
      :or   {resp-timeout default-server-response-timeout}
      :as   headers}]
  (close-conn-on-error
   conn
   (let [cmd-line (if chan-uuid
                    ["sendmsg" chan-uuid]
                    ["sendmsg"])
         cmd-hdrs (as-> headers $
                    (dissoc $ :body :chan-uuid :resp-timeout)
                    (remove (fn [[k v]] (nil? v)) $))
         cmd-body body]
     (req-sync conn cmd-line cmd-hdrs cmd-body :resp-timeout resp-timeout))))


(defn req-call-execute
  "Send a 'sendmsg' request to a channel (or current channel, in case
  of freeswitch-outbound mode) to execute a dialplan application.

  __Args:__

  * `app-cmd` - The dialplan app to execute, including it's arguments.
                i.e. \"playback /tmp/myfile.wav\"

  __Kwargs:__

  * `:chan-uuid` - The UUID of the target channel. Unnecessary in outbound mode.
  * `:event-uuid` - (optional) An UUID to track the events generated by the command.
                    If not provided, a random UUID is used. Note that as of freeswitch
                    1.6, this UUID is returned as value of the `:application-uuid` header
                    of the event.
  * `:start-handler` - (optional) Function to process the 'CHANNEL_EXECUTE' event.
  * `:end-handler` - (optional) Function to process the 'CHANNEL_EXECUTE_COMPLETE' event.
  * `:event-lock` - (optional) Whether to execute apps in sync. Defaults to false.
  * `:loops` - (optional) The number of times the app will be executed. Defaults to 1.
  * `:resp-timeout` - (optional) Seconds to wait for server response, before throwing an exception.
                      Defaults to `30` seconds.

  __Returns:__

  Command response.
  "
  [conn
   app-cmd
   & {:keys [chan-uuid
             event-uuid
             start-handler
             end-handler
             event-lock
             loops
             resp-timeout]
      :or {event-lock   false
           loops        1
           resp-timeout default-server-response-timeout}
      :as kwargs}]
  (close-conn-on-error
   conn
   (let [event-uuid (or event-uuid (str (uuid/v1)))
         {:keys [enabled-special-events]} conn
         [app-name app-arg] (str/split app-cmd #"\s+" 2)]
     ;; Setup :start-handler, if present.
     (when start-handler
       (when-not (@enabled-special-events "CHANNEL_EXECUTE")
         (assert (:ok (req-cmd conn "event CHANNEL_EXECUTE" :resp-timeout resp-timeout))))
       (if chan-uuid
         (bind-event conn
                     (fn [conn event]
                       (unbind-event conn
                                     :event-name "CHANNEL_EXECUTE"
                                     :unique-id chan-uuid
                                     :application-uuid event-uuid)
                       (start-handler conn event))
                     :event-name "CHANNEL_EXECUTE"
                     :unique-id chan-uuid
                     :application-uuid event-uuid)
         (bind-event conn
                     (fn [conn event]
                       (unbind-event conn
                                     :event-name "CHANNEL_EXECUTE"
                                     :application-uuid event-uuid)
                       (start-handler conn event))
                     :event-name "CHANNEL_EXECUTE"
                     :application-uuid event-uuid)))

     ;; Setup :end-handler, if present.
     (when end-handler
       (when-not (@enabled-special-events "CHANNEL_EXECUTE_COMPLETE")
         (assert (:ok (req-cmd conn "event CHANNEL_EXECUTE_COMPLETE" :resp-timeout resp-timeout))))
       (if chan-uuid
         (bind-event conn
                     (fn [conn event]
                       (unbind-event conn
                                     :event-name "CHANNEL_EXECUTE_COMPLETE"
                                     :unique-id chan-uuid
                                     :application-uuid event-uuid)
                       (end-handler conn event))
                     :event-name "CHANNEL_EXECUTE_COMPLETE"
                     :unique-id chan-uuid
                     :application-uuid event-uuid)
         (bind-event conn
                     (fn [conn event]
                       (unbind-event conn
                                     :event-name "CHANNEL_EXECUTE_COMPLETE"
                                     :application-uuid event-uuid)
                       (end-handler conn event))
                     :event-name "CHANNEL_EXECUTE_COMPLETE"
                     :application-uuid event-uuid)))

     (try
      ;; Make the 'sendmsg' request.
       (req-sendmsg conn
                    :chan-uuid chan-uuid
                    :call-command "execute"
                    :execute-app-name app-name
                    :event-uuid event-uuid
                    :loops loops
                    :event-lock event-lock
                    :content-type "text/plain"
                    :body app-arg
                    :resp-timeout resp-timeout)
       (catch Throwable e
        ;; Unbind event handlers in case of exception.
         (when start-handler
           (if chan-uuid
             (unbind-event conn
                           :event-name "CHANNEL_EXECUTE"
                           :unique-id chan-uuid
                           :application-uuid event-uuid)
             (unbind-event conn
                           :event-name "CHANNEL_EXECUTE"
                           :application-uuid event-uuid)))
         (when end-handler
           (if chan-uuid
             (unbind-event conn
                           :event-name "CHANNEL_EXECUTE_COMPLETE"
                           :unique-id chan-uuid
                           :application-uuid event-uuid)
             (unbind-event conn
                           :event-name "CHANNEL_EXECUTE_COMPLETE"
                           :application-uuid event-uuid)))
        ;; Rethrow exception.
         (throw e))))))

;; TODO: req-call-hangup
;; TODO: req-call-nomedia
