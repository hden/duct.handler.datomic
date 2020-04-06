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
(defmethod not-found? :default [_] false)

(defn- tag [m & args]
  (:tag m))

(defmulti query tag)
(defmethod query :default [{:keys [query]} {:keys [connection]} {:keys [args]}]
  (let [db (d/db connection)]
    (map first (d/q {:query query :args (into [db] args)}))))

(defmulti execute! tag)
(defmethod execute! :default [_ {:keys [connection]} {:keys [args]}]
  (let [{:keys [tx-data]} (d/transact connection args)]
    [(count tx-data)]))

(defmulti insert! tag)
(defmethod insert! :default [_ {:keys [connection]} {:keys [args]}]
  (let [{:keys [tempids]} (d/transact connection args)]
    tempids))

(extend-protocol sql/RelationalDatabase
  duct.database.datomic.Boundary
  (query [db {:keys [id] :as arg-map}]
    (let [options (get @stash id)]
      (query options db arg-map)))

  (execute! [db {:keys [id] :as arg-map}]
    (try
      (let [options (get @stash id)]
        (execute! options db arg-map))
      (catch Exception ex
        (if (not-found? ex)
          [0]
          (throw ex)))))

  (insert! [db {:keys [id] :as arg-map}]
    (let [options (get @stash id)]
      (insert! options db arg-map))))

(defmethod ig/prep-key :duct.handler/datomic [_ opts]
  (if (:db opts)
    opts
    (assoc opts :db (ig/ref :duct.database/datomic))))

(defn- stash-options! [{:as options :keys [args]}]
  (let [id (hash options)]
    (swap! stash assoc id options)
    (-> options
        (dissoc :query :tx-data)
        (assoc :sql (cond-> {:id id}
                      args (assoc :args args))))))

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
