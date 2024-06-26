(defproject freeswitch-clj "1.4.1"
  :description "A Clojure interface to freeswitch event socket."
  :url "https://github.com/titonbarua/freeswitch-clj"
  :license {:name "MIT Public License"
            :url "https://opensource.org/licenses/MIT"}
  :signing {:gpg-key "titon@vimmaniac.com"}
  :dependencies [[org.clojure/clojure "1.11.3" :scope "provided"]
                 [org.clojure/core.async "1.6.681"]
                 [org.clojure/tools.logging "1.3.0"]
                 [org.slf4j/slf4j-simple "2.0.13"]
                 [danlentz/clj-uuid "0.1.9"]
                 [metosin/jsonista "0.3.8"]
                 [aleph "0.8.0"]]
  :plugins [[lein-ancient "1.0.0-RC3"]]
  :profiles {:test {:dependencies [[digest "1.4.10"]]}
             :doc-gen {:plugins [[lein-codox "0.10.3"]]
                       :codox {:metadata {:doc/format :markdown}
                               :namespaces [freeswitch-clj.core]
                               :source-uri "https://github.com/titonbarua/freeswitch-clj/blob/v{version}/{filepath}#L{line}"
                               :themes [:rdash]}
                       :dependencies [[codox-theme-rdash "0.1.2"]]}})
