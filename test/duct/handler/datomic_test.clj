(ns duct.handler.datomic-test
  (:require [clojure.test :refer :all]
            [compute.datomic-client-memdb.core :as memdb]
            [datomic.client.api :as d]
            [duct.core :as duct]
            [duct.database.datomic :as db]
            [duct.handler.datomic :as datomic]
            [integrant.core :as ig]))

(duct/load-hierarchy)

(def ^:dynamic *client* nil)

(defn client-fixture
  [f]
  (with-open [c (memdb/client {})]
    (binding [*client* c]
      (f))))

(use-fixtures :each client-fixture)

(def schema {:tx-data
             [{:db/ident       :post/id
               :db/unique      :db.unique/identity
               :db/valueType   :db.type/long
               :db/cardinality :db.cardinality/one}
              {:db/ident       :post/subject
               :db/valueType   :db.type/string
               :db/cardinality :db.cardinality/one}
              {:db/ident       :post/body
               :db/valueType   :db.type/string
               :db/cardinality :db.cardinality/one}
              {:db/ident       :post/comments
               :db/valueType   :db.type/ref
               :db/cardinality :db.cardinality/many
               :db/isComponent true}
              {:db/ident       :comment/id
               :db/unique      :db.unique/identity
               :db/valueType   :db.type/long
               :db/cardinality :db.cardinality/one}
              {:db/ident       :comment/body
               :db/valueType   :db.type/string
               :db/cardinality :db.cardinality/one}]})

(def test-data {:tx-data
                [{:db/id         "comment-1"
                  :comment/id    1
                  :comment/body  "Great!"}
                 {:db/id         "comment-2"
                  :comment/id    2
                  :comment/body  "Rubbish!"}
                 {:post/id       1
                  :post/subject  "Test"
                  :post/body     "Testing 1, 2, 3."
                  :post/comments ["comment-1" "comment-2"]}]})

(def counter (atom 0))

(defn- create-database []
  (let [db-name (str "db-" (swap! counter inc))]
    (d/create-database *client* {:db-name db-name})
    (let [connection (d/connect *client* {:db-name db-name})]
      (d/transact connection schema)
      (d/transact connection test-data)
      (db/->Boundary *client* connection))))

(deftest derive-test
  (isa? ::datomic/query     :duct.module.sql/requires-db)
  (isa? ::datomic/query-one :duct.module.sql/requires-db)
  (isa? ::datomic/execute   :duct.module.sql/requires-db)
  (isa? ::datomic/insert    :duct.module.sql/requires-db))

(deftest prep-test
  (testing "query"
    (is (= (ig/prep {::datomic/query
                     {:query '[:find (pull ?e [*])
                               :where [?e :comment/id _]]}})
           {::datomic/query
            {:db    (ig/ref :duct.database/datomic)
             :query '[:find (pull ?e [*])
                      :where [?e :comment/id _]]}})))

  (testing "query-one"
    (is (= (ig/prep {::datomic/query
                     {:request '{{:keys [id]} :route-params}
                      :query   '[:find (pull ?e [:post/subject :post/body])
                                 :in $ ?id
                                 :where [?e :post/id ?id]]
                      :args    '[id]}})
           {::datomic/query
            {:db      (ig/ref :duct.database/datomic)
             :request '{{:keys [id]} :route-params}
             :query   '[:find (pull ?e [:post/subject :post/body])
                        :in $ ?id
                        :where [?e :post/id ?id]]
             :args    '[id]}})))

  (testing "execute"
    (is (= (ig/prep {::datomic/query
                     {:request '{{:keys [id]} :route-params, {:strs [body]} :form-params}
                      :tx-data '[{:comment/id id :comment/body body}]}})
           {::datomic/query
            {:db      (ig/ref :duct.database/datomic)
             :request '{{:keys [id]} :route-params, {:strs [body]} :form-params}
             :tx-data '[{:comment/id id :comment/body body}]}})))

  (testing "insert"
    (is (= (ig/prep {::datomic/query
                     {:request  '{[_ pid cid body] :ataraxy/result}
                      :tx-data  '[{:db/id "tempid" :comment/id cid :comment/body body}
                                  {:post/id pid :post/comments "tempid"}]
                      :location "/posts{/pid}/comments{/cid}"}})
           {::datomic/query
            {:db       (ig/ref :duct.database/datomic)
             :request  '{[_ pid cid body] :ataraxy/result}
             :tx-data  '[{:db/id "tempid" :comment/id cid :comment/body body}
                         {:post/id pid :post/comments "tempid"}]
             :location "/posts{/pid}/comments{/cid}"}}))))

(deftest query-test
  (testing "with destructuring"
    (let [config  {::datomic/query
                   {:db      (create-database)
                    :request '{[_ post-id] :ataraxy/result}
                    :args    '[post-id]
                    :query   '[:find (pull ?comment [:comment/body])
                               :in $ ?post-id
                               :where [?post :post/id ?post-id]
                                      [?post :post/comments ?comment]]}}
          handler (::datomic/query (ig/init config))]
      (is (= (handler {:ataraxy/result [{} 1]})
             {:status 200, :headers {}, :body '({:comment/body "Great!"} {:comment/body "Rubbish!"})}))))

  (testing "without destructuring"
    (let [config  {::datomic/query
                   {:db    (create-database)
                    :query '[:find (pull ?e [:post/subject])
                             :where [?e :post/id _]]}}
          handler (::datomic/query (ig/init config))]
      (is (= (handler {})
             {:status 200, :headers {}, :body '({:post/subject "Test"})}))))

  (testing "with renamed keys"
    (let [config  {::datomic/query
                   {:db     (create-database)
                    :query  '[:find (pull ?e [:post/subject :post/body])
                              :where [?e :post/id _]]
                    :rename {:post/subject :subject}}}
          handler (::datomic/query (ig/init config))]
      (is (= (handler {})
             {:status 200, :headers {}, :body '({:subject "Test"
                                                 :post/body "Testing 1, 2, 3."})}))))

  (testing "with hrefs"
    (let [config  {::datomic/query
                   {:db    (create-database)
                    :query  '[:find (pull ?e [:post/id :post/subject])
                              :where [?e :post/id _]]
                    :hrefs {:href "/posts{/id}"}}}
          handler (::datomic/query (ig/init config))]
      (is (= (handler {})
             {:status 200, :headers {}, :body '({:post/id   1
                                                 :href "/posts/1"
                                                 :post/subject "Test"})}))))

  (testing "with hrefs from request vars"
    (let [config  {::datomic/query
                   {:db      (create-database)
                    :request '{[_ pid] :ataraxy/result}
                    :query   '[:find (pull ?e [:comment/id :comment/body])
                               :where [?e :comment/id _]]
                    :hrefs   {:href "/posts{/pid}/comments{/id}"}}}
          handler (::datomic/query (ig/init config))]
      (is (= (handler {:ataraxy/result [{} 1]})
             {:status  200
              :headers {}
              :body    '({:comment/id 1, :href "/posts/1/comments/1", :comment/body "Great!"}
                         {:comment/id 2, :href "/posts/1/comments/2", :comment/body "Rubbish!"})}))))

  (testing "with removed keys"
    (let [config  {::datomic/query
                   {:db     (create-database)
                    :query   '[:find (pull ?e [:post/id :post/subject])
                               :where [?e :post/id _]]
                    :hrefs  {:href "/posts{/id}"}
                    :remove [:post/id]}}
          handler (::datomic/query (ig/init config))]
      (is (= (handler {})
             {:status 200, :headers {}, :body [{:href "/posts/1"
                                                :post/subject "Test"}]})))))

(deftest query-one-test
  (testing "with destructuring"
    (let [config  {::datomic/query-one
                   {:db      (create-database)
                    :request '{[_ id] :ataraxy/result}
                    :args    '[id]
                    :query   '[:find (pull ?e [:post/subject :post/body])
                               :in $ ?id
                               :where [?e :post/id ?id]]}}
          handler (::datomic/query-one (ig/init config))]
      (is (= (handler {:ataraxy/result [{} 1]})
             {:status 200, :headers {}, :body #:post{:subject "Test", :body "Testing 1, 2, 3."}}))
      (is (= (handler {:ataraxy/result [{} 2]})
             {:status 404, :headers {}, :body {:error :not-found}}))))

  (testing "with renamed keys"
    (let [config  {::datomic/query-one
                   {:db      (create-database)
                    :request '{[_ id] :ataraxy/result}
                    :args    '[id]
                    :query   '[:find (pull ?e [:post/subject :post/body])
                               :in $ ?id
                               :where [?e :post/id ?id]]
                    :rename  {:post/subject :subject}}}
          handler (::datomic/query-one (ig/init config))]
      (is (= (handler {:ataraxy/result [{} 1]})
             {:status 200, :headers {}, :body {:subject "Test"
                                               :post/body "Testing 1, 2, 3."}}))))

  (testing "with hrefs"
    (let [config  {::datomic/query-one
                   {:db      (create-database)
                    :request '{[_ id] :ataraxy/result}
                    :args    '[id]
                    :query   '[:find (pull ?e [:post/id :post/subject])
                               :in $ ?id
                               :where [?e :post/id ?id]]
                    :hrefs   {:href "/posts{/id}"}}}
          handler (::datomic/query-one (ig/init config))]
      (is (= (handler {:ataraxy/result [{} 1]})
             {:status 200, :headers {}, :body {:post/id      1
                                               :href         "/posts/1"
                                               :post/subject "Test"}}))))

  (testing "with hrefs from request vars"
    (let [config  {::datomic/query-one
                   {:db      (create-database)
                    :request '{[_ id] :ataraxy/result}
                    :args    '[id]
                    :query   '[:find (pull ?e [:post/subject])
                               :in $ ?id
                               :where [?e :post/id ?id]]
                    :hrefs   {:href "/posts{/id}"}}}
          handler (::datomic/query-one (ig/init config))]
      (is (= (handler {:ataraxy/result [{} 1]})
             {:status 200, :headers {}, :body {:href "/posts/1"
                                               :post/subject "Test"}}))))

  (testing "with removed keys"
    (let [config  {::datomic/query-one
                   {:db      (create-database)
                    :request '{[_ id] :ataraxy/result}
                    :args    '[id]
                    :query   '[:find (pull ?e [:post/id :post/subject])
                               :in $ ?id
                               :where [?e :post/id ?id]]
                    :hrefs   {:href "/posts{/id}"}
                    :remove  [:post/id]}}
          handler (::datomic/query-one (ig/init config))]
      (is (= (handler {:ataraxy/result [{} 1]})
             {:status 200, :headers {}, :body {:href "/posts/1"
                                               :post/subject "Test"}})))))

(defn- pull [{:keys [connection]} arg-map]
  (let [db (d/db connection)]
    (d/pull db arg-map)))

(defn- q [{:keys [connection]} {:keys [query args]}]
  (let [db (d/db connection)]
    (d/q {:query query
          :args (into [db] args)})))

(deftest execute-test
  (let [db      (create-database)
        config  {::datomic/execute
                 {:db      db
                  :request '{[_ id body] :ataraxy/result}
                  :tx-data '[{:comment/id id :comment/body body}]}}
        handler (::datomic/execute (ig/init config))]
    (testing "valid update"
      (is (= (handler {:ataraxy/result [{} 1 "Average"]})
             {:status 204, :headers {}, :body nil}))
      (is (= (pull db {:eid [:comment/id 1]
                       :selector '[:comment/id :comment/body]})
             #:comment{:id 1 :body "Average"})))))

(deftest retract-test
  (let [db      (create-database)
        config  {::datomic/execute
                 {:db      db
                  :request '{[_ id] :ataraxy/result}
                  :tx-data '[[:db/retractEntity [:comment/id id]]]}}
        handler (::datomic/execute (ig/init config))]
    (testing "valid retraction"
      (is (= (handler {:ataraxy/result [{} 1]})
             {:status 204, :headers {}, :body nil}))
      (is (empty? (q db {:query '[:find (pull ?e [*])
                                  :where [?e :comment/id 1]]}))))

    (testing "invalid id"
      ;; Although there is no such entity, datomic still records the fact that
      ;; a retraction has been attempted.
      (is (= (handler {:ataraxy/result [{} 100]})
             {:status 204, :headers {}, :body nil})))))

(deftest cas-test
  (let [db      (create-database)
        config  {::datomic/execute
                 {:db      db
                  :request '{[_ id from-value to-value] :ataraxy/result}
                  :tx-data '[[:db/cas [:comment/id id] :comment/body from-value to-value]]}}
        handler (::datomic/execute (ig/init config))]
    (testing "valid update"
      (is (= (handler {:ataraxy/result [{} 1 "Great!" "Okay"]})
             {:status 204, :headers {}, :body nil}))
      (is (= (pull db {:eid [:comment/id 1]
                       :selector [:comment/id :comment/body]})
             #:comment{:id 1 :body "Okay"})))

    (testing "no such entity"
      (is (= (handler {:ataraxy/result [{} 100 "Okay" "Great!"]})
             {:status 404, :headers {}, :body {:error :not-found}})))

    (testing "invalid value"
      (is (= (handler {:ataraxy/result [{} 1 "foo" "bar"]})
             {:status 404, :headers {}, :body {:error :not-found}})))))

(deftest insert-test
  (testing "with location"
    (let [db      (create-database)
          config  {::datomic/insert
                   {:db       db
                    :request '{[_ pid cid body] :ataraxy/result}
                    :tx-data '[{:comment/id cid :comment/body body}
                               {:post/id pid :post/comments cid}]
                    :location "/posts{/pid}/comments{/cid}"}}
          handler (::datomic/insert (ig/init config))]
      (is (= (handler {:ataraxy/result [{} 1 3 "New comment"]})
             {:status 201, :headers {"Location" "/posts/1/comments/3"}, :body nil}))
      (is (= (pull db {:eid [:comment/id 3]
                       :selector '[:comment/id :comment/body]})
             #:comment{:id 3 :body "New comment"}))))

  (testing "with tempids to location"
    (let [db      (create-database)
          config  {::datomic/insert
                   {:db       db
                    :request '{[_ pid cid body] :ataraxy/result}
                    :tx-data '[{:db/id "tempid" :comment/id cid :comment/body body}
                               {:post/id pid :post/comments "tempid"}]
                    :location "/posts{/pid}/comments{/tempid}"}}
          handler (::datomic/insert (ig/init config))]
      (is (= (handler {:ataraxy/result [{} 1 4 "New comment!"]})
             {:status 201
              :body nil
              :headers
              {"Location" (format "/posts/1/comments/%d" (:db/id (pull db {:eid [:comment/id 4]
                                                                           :selector '[*]})))}}))))

  (testing "without location"
    (let [db     (create-database)
          config {::datomic/insert
                  {:db      db
                   :request '{[_ pid cid body] :ataraxy/result}
                   :tx-data '[{:db/id "tempid" :comment/id cid :comment/body body}
                              {:post/id pid :post/comments "tempid"}]}}
          handler (::datomic/insert (ig/init config))]
      (is (= (handler {:ataraxy/result [{} 1 5 "New comment!!"]})
             {:status 201, :headers {}, :body nil}))
      (is (= (pull db {:eid [:comment/id 5]
                       :selector '[:comment/id :comment/body]})
             #:comment{:id 5 :body "New comment!!"})))))
