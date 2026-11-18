(ns duct.handler.datomic
  (:refer-clojure :exclude [ex-cause])
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [datomic.client.api :as d]
            [duct.database.datomic]
            [integrant.core :as ig]
            [ring.util.response :as resp]
            [uritemplate-clj.core :as ut]))

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

(defmethod ig/expand-key :duct.handler/datomic [key opts]
  {key (if (:db opts)
         opts
         (assoc opts :db (ig/ref :duct.database/datomic)))})

(defn- request-bindings [request]
  (let [names (into #{}
                    (comp (filter #(or (symbol? %) (keyword? %)))
                          (map name))
                    (tree-seq coll? seq request))]
    (into {}
          (comp (filter symbol?)
                (remove #{'_})
                (filter #(contains? names (name %)))
                (map (fn [binding]
                       [(keyword (name binding)) (symbol (name binding))])))
          (take-nth 2 (destructure [request nil])))))

(defn- request-handler [{:keys [args request] :or {request '_} :as options} respond]
  (let [arg-map (cond-> {:id (hash options)}
                  args (assoc :args args))
        build   (eval `(fn [respond#]
                         (fn [~request]
                           (respond# ~arg-map ~(request-bindings request)))))]
    (build respond)))

(defn- uri-template [template values]
  (let [values (into {}
                     (map (fn [[key value]]
                            [(str/replace key #"[^\w]" "") value]))
                     (walk/stringify-keys values))]
    (ut/uritemplate template values)))

(defn- transform-result [result {:keys [hrefs remove rename]} request-vars]
  (let [vars (merge request-vars result)
        linked (reduce-kv (fn [result key template]
                            (assoc result key (uri-template template vars)))
                          result
                          hrefs)]
    (set/rename-keys (apply dissoc linked remove) rename)))

(defn- affected-count [options db args]
  (try
    (first (execute! options db args))
    (catch Exception ex
      (if (not-found? ex)
        0
        (throw ex)))))

(defmethod ig/init-key ::query
  [_ {:keys [db] :as options}]
  (request-handler options
                   (fn [args request-vars]
                     (resp/response (map #(transform-result % options request-vars)
                                         (query options db args))))))

(defmethod ig/init-key ::query-one
  [_ {:keys [db] :as options}]
  (request-handler options
                   (fn [args request-vars]
                     (if-let [result (first (query options db args))]
                       (resp/response (transform-result result options request-vars))
                       (resp/not-found {:error :not-found})))))

(defmethod ig/init-key ::execute
  [_ {:keys [db] :as options}]
  (request-handler options
                   (fn [args _]
                     (if (zero? (affected-count options db args))
                       (resp/not-found {:error :not-found})
                       {:status 204 :headers {} :body nil}))))

(defmethod ig/init-key ::insert
  [_ {:keys [db location] :as options}]
  (request-handler options
                   (fn [args request-vars]
                     (let [tempids (insert! options db args)]
                       (if location
                         (resp/created (uri-template location (merge request-vars tempids)))
                         {:status 201 :headers {} :body nil})))))
