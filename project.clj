(defproject freeswitch-clj "1.4.2"
  :description "A Clojure interface to freeswitch event socket."
  :url "https://github.com/titonbarua/freeswitch-clj"
  :license {:name "MIT Public License"
            :url "https://opensource.org/licenses/MIT"}
  :signing {:gpg-key "titon@vimmaniac.com"}
  :dependencies [[org.clojure/clojure "1.12.5" :scope "provided"]
                 [org.clojure/core.async "1.9.865"]
                 [org.clojure/tools.logging "1.3.1"]
                 [org.slf4j/slf4j-simple "2.0.18"]
                 [danlentz/clj-uuid "0.2.5"]
                 [metosin/jsonista "1.0.0"]
                 [aleph "0.9.9"]]
  :plugins [[lein-ancient "1.0.0-RC3"]]
  :profiles {:test {:dependencies [[digest "1.4.10"]]}
             :doc-gen {:plugins [[lein-codox "0.10.3"]]
                       :codox {:metadata {:doc/format :markdown}
                               :namespaces [freeswitch-clj.core]
                               :source-uri "https://github.com/titonbarua/freeswitch-clj/blob/v{version}/{filepath}#L{line}"
                               :themes [:rdash]}
                       :dependencies [[codox-theme-rdash "0.1.2"]]}})
