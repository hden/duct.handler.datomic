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

(defmulti prepare-execute tag)
(defmethod prepare-execute :default [_ _ arg-map]
  (select-keys arg-map [:tx-data]))

(defmulti after-execute tag)
(defmethod after-execute :default [_ _ {:keys [tx-data]}]
  [(count tx-data)])

(defmulti prepare-insert tag)
(defmethod prepare-insert :default [_ _ arg-map]
  (select-keys arg-map [:tx-data]))

(defmulti after-insert tag)
(defmethod after-insert :default [_ _ {:keys [tempids]}]
  tempids)

(extend-protocol sql/RelationalDatabase
  duct.database.datomic.Boundary
  (query [db {:keys [id] :as args}]
    (let [options (get @stash id)]
      (->> args
           (prepare-query options db)
           (d/q)
           (after-query options db))))

  (execute! [{:keys [connection] :as db} {:keys [id] :as arg-map}]
    (try
      (let [options (get @stash id)]
        (->> arg-map
             (prepare-execute options db)
             (d/transact connection)
             (after-execute options db)))
      (catch Exception ex
        (if (not-found? ex)
          [0]
          (throw ex)))))

  (insert! [{:keys [connection] :as db} {:keys [id] :as arg-map}]
    (let [options (get @stash id)]
      (->> arg-map
           (prepare-insert options db)
           (d/transact connection)
           (after-insert options db)))))

(defmethod ig/prep-key :duct.handler/datomic [_ opts]
  (if (:db opts)
    opts
    (assoc opts :db (ig/ref :duct.database/datomic))))

(defn- stash-options! [{:as options :keys [args tx-data]}]
  (let [id (hash options)]
    (swap! stash assoc id options)
    (-> options
        (dissoc :query :tx-data)
        (assoc :sql (cond-> {:id id}
                      args (assoc :args args)
                      tx-data (assoc :tx-data tx-data))))))

(defmethod ig/init-key ::query
  [_ options]
  (ig/init-key ::sql/query (stash-options! options)))

(defmethod ig/init-key ::query-one
  [_ options]
  (ig/init-key ::sql/query-one (stash-options! options)))

(defmethod ig/init-key ::execute
  [_ options]
  (ig/init-key ::sql/execute (stash-options! options)))

(defmethod ig/init-key ::insert
  [_ options]
  (ig/init-key ::sql/insert (stash-options! options)))
