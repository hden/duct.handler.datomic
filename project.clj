(defproject hden/duct.handler.datomic "0.1.0"
  :description "Duct library for building simple database-driven handlers"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :managed-dependencies [[com.datomic/client-cloud "0.8.113"]]
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [com.datomic/client-cloud]
                 [duct/handler.sql "0.4.0"]
                 [hden/duct.database.datomic "0.2.0"]
                 [integrant "0.8.0"]]
  :repositories [["datomic-cloud" "s3://datomic-releases-1fc2183a/maven/releases"]]
  :repl-options {:init-ns duct.handler.datomic}
  :plugins [[lein-cloverage "1.2.2"]]
  :profiles
  {:dev {:dependencies [[datomic-client-memdb "1.1.1"]
                        [duct/core "0.8.0"]]}})
