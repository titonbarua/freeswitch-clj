# freeswitch-clj

A Clojure library to communicate with freeswitch event socket.

## Features

- Freeswitch ESL protocol implemented in Clojure.
- Support for both inbound and outbound mode.
- Callback based event handling.
- Automated event handler management for things like `bgapi` and `CUSTOM` events.
- Uses high-performance `aleph` async framework under the hood.

## Usage

`freeswitch-clj` can be used in both inbound mode and outbound mode.

### Inbound example

```clojure
(require '[freeswitch-clj :as f])

;; Connect to a local freeswitch server.
(def conn (f/connect :host "localhost"
                     :port 8021
                     :password "ClueCon"))

;; Send an 'api' request.
(f/req-api conn "status")
;; => {:ok true, :result "...", :Reply-Text "..."}

;; Define a handler to process result of a 'bgapi' request.
(def rslt-handler
    (fn [conn rslt]
        (println "Result is:" rslt)))

;; Make the 'bgapi' request.
(f/req-bgapi conn "status" rslt-handler)
;; => {:ok true, :Reply-Text "...", :Job-UUID "<uuid>"}
;; Result is: {:ok true, :result "...", :event {...}}

;; Diconnect.
(f/disconnect conn)
```

### Outbound example

```clojure
(require '[freeswitch-clj :as f])

;; Define an incoming connection handler.
(defn conn-handler
    [conn]
    (println "Channel data is:" (conn :channel-data))
    ;; Channel data is: {...}

    (println (f/req-api conn "status"))
    ;; {:ok true, :result "...", :Reply-Text "..."}

    (f/disconnect conn))

;; Listen for connections from freeswitch.
(f/listen :port 10000 :handler conn-handler)
```

Check out [more usage examples.](http://)

## License

Copyright © 2017 [Messrs Concitus, Dhaka, Bangladesh](mailto:contact@concitus.com)

Distributed under the MIT Public License.
