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

(defn- tag [m & args]
  (:tag m))

(defmulti prepare-query tag)
(defmethod prepare-query :default [{:keys [query]} {:keys [connection]} {:keys [args]}]
  (let [db (d/db connection)]
    {:query query :args (into [db] args)}))

(defmulti after-query tag)
(defmethod after-query :default [_ _ coll]
  (map first coll))

(extend-protocol sql/RelationalDatabase
  duct.database.datomic.Boundary
  (query [db {:keys [id] :as args}]
    (let [options (get @stash id)]
      (->> args
           (prepare-query options db)
           (d/q)
           (after-query options db))))

  (execute! [{:keys [connection]} arg-map]
    (try
      (let [{:keys [tx-data]} (d/transact connection arg-map)]
        [(count tx-data)])
      (catch Exception ex
        (if (not-found? ex)
          [0]
          (throw ex)))))

  (insert! [{:keys [connection]} arg-map]
    (let [{:keys [tempids]} (d/transact connection arg-map)]
      tempids)))

(defmethod ig/prep-key :duct.handler/datomic [_ opts]
  (if (:db opts)
    opts
    (assoc opts :db (ig/ref :duct.database/datomic))))

(defn- stash-options [{:as options :keys [args]}]
  (let [id (hash options)]
    (swap! stash assoc id options)
    (-> options
      (dissoc :query)
      (assoc :sql {:id id :args args}))))

(defmethod ig/init-key ::query
  [_ options]
  (ig/init-key ::sql/query (stash-options options)))

(defmethod ig/init-key ::query-one
  [_ {:as options :keys [query args]}]
  (ig/init-key ::sql/query-one (stash-options options)))

(defmethod ig/init-key ::execute
  [_ options]
  (ig/init-key ::sql/execute (assoc options :sql (select-keys options [:tx-data]))))

(defmethod ig/init-key ::insert
  [_ options]
  (ig/init-key ::sql/insert (assoc options :sql (select-keys options [:tx-data]))))
