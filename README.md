# immutable-sql

[![Clojars Project](https://img.shields.io/clojars/v/immutable-sql.svg)](https://clojars.org/immutable-sql)

Add to your project.clj:

```clj
;add latest version from Clojars
[immutable-sql "x.x.x"]
```

Add to your namespace imports:

```clj
(:require [immutable-sql.core :as imm-sql]
          [clojure.core.async :refer [<!!]
          [postgres.async :as postgres-async]])
```

### Set up a connection pool
Immutable SQL currently only supports the alaisi/postgres.async library. clojure.jdbc PR's welcome!
```clj
(def db (postgres-async/open-db
          {:hostname  "db.example.com"
           :port      5432                                  
           :database  "exampledb"
           :username  "user"
           :password  "pass"
           :pool-size 25}))
```
(Taken from https://github.com/alaisi/postgres.async#setting-up-a-connection-pool)


### Create an "immutable" table

```clj
(create-table
      ;name of the Postgres schema and the table itself
      "test_schema" "test_table"
      ;map of "column_name" => postgres type
      {"title" "TEXT NOT NULL"
       "uuid" "TEXT NOT NULL"
       "price" "numeric(21,6)"
       "image" "TEXT NOT NULL"}
      ;add index on those columns
      ["uuid"])
```
This prints out the SQL that creates the Postgres schema, table, table columns and indices. Run it with your favorite Postgres client.


### Insert a new row
```clj
(<!! (imm-sql/immutable-insert!
       db
       :test_schema.test_table
       ;identity (aka row) lookup is determined by this
       {:uuid "uuid-1"}
       ;we write with a function; function takes previous row as a map
       ;in this case m is nill - there's no row where uuid = 'uuid-1' yet 
       (fn [m]
         {:uuid  "uuid-1"
          :title "Clojure stickers"
          :price 1.00
          :image "http://clojure.org/images/clojure-logo-120b.png"})))
```

### "Update" a row 
```clj
(<!! (imm-sql/immutable-insert!
       db
       :test_schema.test_table
       {:uuid "uuid-1"}
       (fn [m]
         ;m here is the previous version of the row 
         (assoc m :title "Clojure stickers are awesome"))))
```

Your table should now look something like this:

<img src="/doc/table1.png" width="800px" hspace="5px"/>
<br>

(screenshot from Postico - great Postgres client)


### TODO
- explanation of trade-offs
- more examples

## License

Copyright © 2016-2021 Rangel Spasov

Distributed under the MIT License
# immutable-sql
