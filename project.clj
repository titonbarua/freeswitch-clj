(defproject freeswitch-clj "0.2.2-SNAPSHOT"
  :description "A Clojure interface to freeswitch event socket."
  :url "https://github.com/titonbarua/freeswitch-clj"
  :license {:name "MIT Public License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [org.clojure/core.async "0.4.474"]
                 [com.taoensso/timbre "4.10.0"]
                 [danlentz/clj-uuid "0.1.7"]
                 [cheshire "5.8.0"]
                 [aleph "0.4.3"]]
  :profiles {:test {:dependencies [[digest "1.4.6"]]}
             :doc-gen {:plugins [[lein-codox "0.10.3"]]
                       :codox {:metadata {:doc/format :markdown}
                               :namespaces [freeswitch-clj.core]
                               :source-uri "https://github.com/titonbarua/freeswitch-clj/blob/{version}/{filepath}#L{line}"
                               :themes [:rdash]}
                       :dependencies [[codox-theme-rdash "0.1.2"]]}})
