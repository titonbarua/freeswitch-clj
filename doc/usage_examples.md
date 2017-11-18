# Introduction

`freeswitch-clj` is an event socket interface for FreeSWITCH in Clojure. This is an open source project released under MIT Pulic License. Read the the project README file hosted in [github](http://https://github.com/titonbarua/freeswitch-clj) for notes on installation and basic usage examples.

This document demonstrates the usage of the library with some additional examples.

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

A basic outbound setup where freeswitch is configured to knock on port `10000` for decision about call routing:

```clojure
(require '[freeswitch-clj.core :as f])

;; Create a connection handler.
(defn conn-handler
    [conn chan-data]
    (println "Channel data:" chan-data)
    (println (f/req-api conn "status"))

    ;; Send 'exit' command.
    (f/disconnect conn)
    ;; Wait for connection to close.
    @(conn :close?))


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
(f/req-bgapi conn bgjob-handler "status")
```

## Handling events, the high-level way

Function `req-event` can be used to both subscribe and setup handler for an event.

```clojure
;; Define an event handler.
(defn event-handler
    [conn event-map]
    (println "Received event:" event-map))

;; Watch for a heartbeat event.
(f/req-event conn
             event-handler
             :event-name "HEARTBEAT")
```

## Handling events, the low-level approach

For more control, event handler binding and event subscription can be separated.

```clojure
;; Bind event handler.
(f/bind-event
    conn
    (fn [conn event-map]
        (println "Received heartbeat:" event-map))
    :event-name "HEARTBEAT")

;; Subscribe to the event.
(f/req-cmd conn "event HEARTBEAT")
```
