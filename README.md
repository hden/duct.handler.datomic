# duct.handler.datomic

A duct library for building simple database-driven handlers for the datomic database.

Ported from [duct.handler.sql](https://github.com/duct-framework/handler.sql)).

## Installation

To install, add the following to your project dependencies:

```
[hden/duct.handler.datomic "0.1.0"]
```

## Usage

This library is designed to be used with a routing library with Duct bindings, such as Ataraxy.

### Querying

Querying the database generally follows a HTTP GET request. There are two keys for creating handlers that query the database:

* `:duct.handler.datomic/query`     - for multiple results
* `:duct.handler.datomic/query-one` - for when you have only one result

The simplest usage is a handler that queries the database the same way
each time:

```clojure
{[:duct.handler.datomic/query :example.handler.product/list]
 {:db  #ig/ref :duct.database/datomic
  :query [:find (pull ?e [*])
          :where [?e :product/id _]]}}
```

In the above example, a [composite key][] is used to provide a unique
identifier for the handler.

The `:db` option should be a `duct.database.datomic.Boundary` record, and
the `:query` option should be a [datalog query][] form.

If you omit the `:db` option, the `ig/prep` stage will default it to
`#ig/ref :duct.database/datomic`. This means you can write:

```clojure
{[:duct.handler.datomic/query :example.handler.product/list]
 {:query [:find (pull ?e [*])
          :where [?e :product/id _]]}}
```

If you want to change the query based on the request, you can
destructure the parameters you want in the `:request` option and binds them
to the args option:

> The db argument if automatically bind to the first argument.

```clojure
{[:duct.handler.datomic/query-one :example.handler.product/find]
 {:db      #ig/ref :duct.database/datomic
  :request {[_ id] :ataraxy/result}
  :args [id]
  :query [:find (pull ?e [*])
          :in $ ?id
          :where [?e :product/id ?id]]}}
```

The response can also be altered. The `:rename` option is available
for renaming keys returned in the result set:

```clojure
{[:duct.handler.datomic/query :example.handler.product/list]
 {:db     #ig/ref :duct.database/datomic
  :query [:find (pull ?e [:product/id :product/name])
          :where [?e :product/id _]]
  :rename {:product/id :id, :product/name :name}}}
```

The `:hrefs` option adds hypertext references based on [URI
Templates][]:

```clojure
{[:duct.handler.datomic/query :example.handler.product/list]
 {:db     #ig/ref :duct.database/datomic
  :query [:find (pull ?e [:product/id :product/name])
          :where [?e :product/id _]]
  :hrefs {:self "/products{/id}"}}}
```
The `:hrefs` key takes template parameters from the result fields, and
from the request destructuring.

Finally, the `:remove` key allows keys to be removed from the
response. This is useful if you want a key to be used in a href, but
not to show up in the final response:

```clojure
{[:duct.handler.datomic/query :example.handler.product/list]
 {:db     #ig/ref :duct.database/datomic
  :query [:find (pull ?e [:product/id :product/name])
          :where [?e :product/id _]]
  :hrefs  {:self "/products{/id}"}
  :remove [:product/id]}}
```

[composite key]: https://github.com/weavejester/integrant#composite-keys
[datalog query]: https://docs.datomic.com/cloud/query/query-data-reference.html
[uri templates]: https://tools.ietf.org/html/rfc6570

### Updating

Sometimes a HTTP request will alter the database. There are two keys
for creating handlers that update the database:

* `:duct.handler.datomic/insert`  - for inserting datoms
* `:duct.handler.datomic/execute` - for updating or deleting datoms

The `:duct.handler.datomic/insert` key is designed to respond to a HTTP
`POST` event and send a "Created" 201 response with a "Location"
header created from the generated ID of a transaction. For example:

```clojure
{[:duct.handler.datomic/insert :example.handler.product/create]
 {:db       #ig/ref :duct.database/datomic
  :request  {{:strs [id name]} :form-params}
  :tx-data  [{:product/id id :product/name name}]
  :location "/products{/id}"}}
```

The resolved [tempids][] are also available as template parameters.

The `:duct.handler.datomic/execute` doesn't have to worry about generated
keys; it's designed to report to HTTP `DELETE` and `PUT` requests. If
the transaction appends one or more datoms, a "No Content" 204 response is
returned, otherwise, if zero datoms are appended (rare for datomic), a
404 response is returned.

For example:

```clojure
{[:duct.handler.datomic/execute :example.handler.product/update]
 {:db      #ig/ref :duct.database/datomic
  :request {[_ id name] :ataraxy/result}
  :tx-data [{:product/id id :product/name name}]}

 [:duct.handler.datomic/execute :example.handler.product/destroy]
 {:db      #ig/ref :duct.database/datomic
  :request {[_ id] :ataraxy/result}
  :tx-data [[:db/retractEntity [:product/id id]]]}}
```

[tempids]: https://docs.datomic.com/cloud/transactions/transaction-processing.html#tempid-resolution

### Full Example

Together with a router like Ataraxy, the configuration might look
something like:

```clojure
{:duct.core/project-ns example
 :duct.core/environment :production

 :duct.module.web/api {}
 :duct.module/sql     {}

 :duct.module/ataraxy
 {"/products"
  {[:get]        [:product/list]
   [:get "/" id] [:product/find ^uuid id]

   [:post {{:strs [name]} :form-params}]
   [:product/create name]

   [:put "/" id {{:strs [name]} :form-params}]
   [:product/update ^uuid id name]

   [:delete "/" id]
   [:product/destroy ^uuid id]}}

 [:duct.handler.datomic/query :example.handler.product/list]
 {:query [:find (pull ?e [*])
          :where [?e :product/id _]]}

 [:duct.handler.datomic/query-one :example.handler.product/find]
 {:request {[_ id] :ataraxy/result}
  :args    [id]
  :query   [:find (pull ?e [*])
            :in $ ?id
            :where [?e :product/id ?id]]}

 [:duct.handler.datomic/insert :example.handler.product/create]
 {:request  {[_ id name] :ataraxy/result}
  :tx-data  [{:product/id id :product/name name}]
  :location "/products{/id}"}

 [:duct.handler.datomic/execute :example.handler.product/update]
 {:request {[_ id name] :ataraxy/result}
  :tx-data [{:product/id id :product/name name}]}

 [:duct.handler.datomic/execute :example.handler.product/destroy]
 {:request {[_ id] :ataraxy/result}
  :tx-data [[:db/retractEntity [:product/id id]]]}}
```

Note that the `:db` key can be omitted in this case, because the
`:duct.module/datomic` module will automatically populate the handlers
with a database connection.

## Caveats

This library can produce simple handlers that require only the
information present in the request map. When paired with a good
routing library, this can be surprisingly powerful.

However, don't overuse this library. If your requirements for a
handler are more complex, then create your own `init-key` method for
the handler. It's entirely possible, even likely, that your app will
contain handlers created via this library, and handlers that are
created through your own `init-key` methods.

## Note On Data Modeling

Although it's not a requirement to use this library, you should almost certainly
want to create your own unique identity instead of exposing Datomic's `:db/id` to
the API. [It is recommend that you always have some kind of external ID for entities in you system.](https://forum.datomic.com/t/copying-entities-between-dbs-while-maintaining-relationships/1275/3)

### Bad! Don't Do This In Production!

This example might look harmless, but I assure you that it's not.

```clojure
{:duct.core/project-ns example
 :duct.module/ataraxy
 {"/products"
  {[:delete "/" id] [:product/destroy ^int id]}}

 [:duct.handler.datomic/execute :example.handler.product/destroy]
 {:request {[_ id] :ataraxy/result}
  :tx-data [[:db/retractEntity id]]}}
```

When people calls your API endpoint with DELETE `/products/17592186045422`, how
do you know that the entity is a product?

Here is a better way:

```clojure
{:duct.core/project-ns example
 :duct.module/ataraxy
 {"/products"
  {[:delete "/" id] [:product/destroy id]}}

 [:duct.handler.datomic/execute :example.handler.product/destroy]
 {:request {[_ id] :ataraxy/result}
  :tx-data [[:db/retractEntity [:product/id id]]]}}
```

## License

Copyright © 2020 Haokang Den

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.
