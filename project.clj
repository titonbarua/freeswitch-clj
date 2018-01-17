(defproject freeswitch-clj "0.2.0"
  :description "A Clojure interface to freeswitch event socket."
  :url "https://github.com/titonbarua/freeswitch-clj"
  :license {:name "MIT Public License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [danlentz/clj-uuid "0.1.7"]
                 [cheshire "5.8.0"]
                 [aleph "0.4.3"]

                 ;; dependencies for testing and documentation.
                 [proto-repl "0.3.1"]
                 [codox-theme-rdash "0.1.2"]
                 [digest "1.4.6"]]
  :plugins [[lein-codox "0.10.3"]]
  :codox {:metadata {:doc/format :plaintext}
          :themes [:rdash]})
