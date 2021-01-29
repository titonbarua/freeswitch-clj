(defproject freeswitch-clj "1.0.2"
  :description "A Clojure interface to freeswitch event socket."
  :url "https://github.com/titonbarua/freeswitch-clj"
  :license {:name "MIT Public License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [org.clojure/core.async "0.4.500"]
                 [com.taoensso/timbre "4.10.0"]
                 [danlentz/clj-uuid "0.1.9"]
                 [cheshire "5.8.1"]
                 [aleph "0.4.6"]
                 #_[alpeh "0.4.7-alpha7"]]
  :profiles {:test {:dependencies [[digest "1.4.9"]]}
             :doc-gen {:plugins [[lein-codox "0.10.3"]]
                       :codox {:metadata {:doc/format :markdown}
                               :namespaces [freeswitch-clj.core]
                               :source-uri "https://github.com/titonbarua/freeswitch-clj/blob/v{version}/{filepath}#L{line}"
                               :themes [:rdash]}
                       :dependencies [[codox-theme-rdash "0.1.2"]]}})
