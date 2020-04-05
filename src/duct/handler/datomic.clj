(ns duct.handler.datomic
  (:refer-clojure :exclude [ex-cause])
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [duct.database.datomic :as datomic.db]
            [duct.handler.sql :as sql]
            [integrant.core :as ig]))

(defonce stash (atom {}))

(defn- ex-cause [ex]
  (when-let [cause (or (:db/error (ex-data ex))
                       (:cause (Throwable->map ex)))]
    (cond
      ;; Datomic free (on-prem)
      (string? cause) (first (str/split cause #" "))
      ;; Datomic Cloud
      (keyword? cause) cause
      :else nil)))

(defmulti not-found? ex-cause)

(defmethod not-found? ":db.error/cas-failed" [_] true)
(defmethod not-found? :default [_] false)

(extend-protocol sql/RelationalDatabase
  duct.database.datomic.Boundary
  (query [{:keys [connection]} {:keys [id args]}]
    (let [db (d/db connection)
          query (get @stash id)
          args (into [db] args)]
      (map first
           (d/q {:query query :args args}))))

  (execute! [{:keys [connection]} arg-map]
    (try
      (let [{:keys [tx-data]} (d/transact connection arg-map)]
        [(count tx-data)])
      (catch Exception ex
        (if (not-found? ex)
          [0]
          (throw ex)))))

  (insert!  [{:keys [connection]} arg-map]
    (let [{:keys [tempids]} (d/transact connection arg-map)]
      tempids)))

(defmethod ig/prep-key :duct.handler/datomic [_ opts]
  (if (:db opts)
    opts
    (assoc opts :db (ig/ref :duct.database/datomic))))

(defn- stash-query [{:as options :keys [query args]}]
  "Stash the query form and retrieve them later"
  (let [id (hash query)]
    (swap! stash assoc id query)
    (-> options
      (dissoc :query)
      (assoc :sql {:id id :args args}))))

(defmethod ig/init-key ::query
  [_ options]
  (ig/init-key ::sql/query (stash-query options)))

(defmethod ig/init-key ::query-one
  [_ {:as options :keys [query args]}]
  (ig/init-key ::sql/query-one (stash-query options)))

(defmethod ig/init-key ::execute
  [_ options]
  (ig/init-key ::sql/execute (assoc options :sql (select-keys options [:tx-data]))))

(defmethod ig/init-key ::insert
  [_ options]
  (ig/init-key ::sql/insert (assoc options :sql (select-keys options [:tx-data]))))
