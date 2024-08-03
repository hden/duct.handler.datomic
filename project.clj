(defproject hden/duct.handler.datomic "0.1.0"
  :description "Duct library for building simple database-driven handlers"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :managed-dependencies [[com.datomic/client-cloud "1.0.130"]]
  :dependencies [[org.clojure/clojure "1.11.4"]
                 [com.datomic/client-cloud]
                 [duct/handler.sql "0.4.0"]
                 [hden/duct.database.datomic "0.3.1"]
                 [integrant "0.10.0"]]
  :repositories [["datomic-cloud" "s3://datomic-releases-1fc2183a/maven/releases"]]
  :repl-options {:init-ns duct.handler.datomic}
  :plugins [[lein-cloverage "1.2.4"]]
  :profiles
  {:dev {:dependencies [[datomic-client-memdb "1.1.1"]
                        [duct/core "0.8.1"]]}})
