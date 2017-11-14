# Usage examples

## Basic inbound setup

Here's a basic setup to send some commands to freeswitch in inbound mode:

```clojure
(require '[freeswitch-clj.core :as f])

;; Make a connection.
(def conn (f/connect :host "127.0.0.1"))

;; Print result of an api request.
(println (req-api conn "status"))

(f/disconnect conn)
```

## Basic outbound setup

A basic outbound setup where freeswitch is configured to knock on port
`10000` for decision about call routing:

```clojure
(require '[freeswitch-clj.core :as f])

;; Create a connection handler.
(defn conn-handler
    [conn]
    (println "Channel data:" (:channel-data conn))
    (println (f/req-api conn "status"))
    (f/disconnect conn))

;; Listen for outbound connections from freeswitch on port 10000.
(f/listen :port 10000
          :handler conn-handler)
```

## Handling result of background jobs

The function `req-bgapi` can be used to effortlessly handle result of background jobs.

```clojure
;; Define a result handler function.
(defn bgjob-handler
    [conn rslt]
    (println "bgjob result:" rslt))

;; Make a bgapi request.
(f/req-bgapi conn "status" bgjob-handler)
```

## Handling events, the high-level way

Function `req-event` can be used to both subscribe and setup handler
for an event.

```clojure
;; Define an event handler.
(defn event-handler
    [conn event-map]
    (println "Received event:" event-map))

;; Watch for a heartbeat event.
(f/req-event conn "HEARTBEAT" event-handler)
```

## Handling events, the low-level approach

For more control, event handler binding and event subscription can be
separated.

```clojure
;; Bind event handler.
(f/bind-event
    conn
    "HEARTBEAT"
    (fn [conn event-map]
        (println "Received heartbeat:" event-map))))

;; Subscribe to the event.
(f/req-cmd conn "event HEARTBEAT")
```
