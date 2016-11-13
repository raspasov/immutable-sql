(ns immutable-sql.core
  (:require [honeysql.helpers :refer :all :exclude [update]]
            [clojure.core.async :refer [chan close! go >! <! <!! >!! go-loop put! thread alts! alts!! timeout]]
            [full.async :refer [go-try <?? <? go-retry]]
            [honeysql.core :as hsql]
            [postgres.async :refer :all]
            [cuerdas.core :as ss])
  (:import (clojure.lang IPersistentMap Keyword Murmur3 IPersistentVector IFn)
           (com.github.pgasync Transaction)
           (java.util.regex Matcher)))


;CREATING TABLES, etc REPL functions
;=======================================================================================================================

(defn create-index [unique? schema table-name ^IPersistentVector columns ^String where]
  (let [full-table-name (str schema "." table-name)]
    (ss/collapse-whitespace
      (ss/join " "
               [(ss/join " "
                         ["CREATE" (if unique? "UNIQUE") "INDEX"])
                (str "\""
                     table-name
                     ":"
                     (ss/join "+" columns)
                     (if where
                       (str ":where:" (ss/replace where " " "")))
                     "\"")
                "ON"
                full-table-name
                "("
                (ss/join "," columns)
                ")"
                (if where
                  (ss/join " " ["WHERE" where]))
                ";"]))))


(defn create-table
  "Prints out SQL for tables, indices and sequences for immutable-sql compatible tables.
   Does not run any SQL, only prints in the REPL.
   Example usage:"
  #_(create-table
      "immutable_sql" "products"
      {"title" "TEXT NOT NULL"
       "price" "numeric(21,6)"
       "image" "TEXT NOT NULL"}
      ["title"])
  [^String schema ^String table-name ^IPersistentMap col-specs ^IPersistentVector identity-columns]
  (let [cols-str (clojure.string/join ", " (map (fn [kv] (clojure.string/join " " kv)) col-specs))
        full-table-name (str schema "." table-name)
        seq-name-1 (str schema "." table-name "_seq1")
        seq-name-2 (str schema "." table-name "_seq2")
        create-seq-sql-1
        (clojure.string/join " " ["CREATE SEQUENCE" seq-name-1 ";"])
        create-seq-sql-2
        (clojure.string/join " " ["CREATE SEQUENCE" seq-name-2 ";"])
        create-table-sql
        (clojure.string/join " "
                             ["CREATE TABLE" full-table-name "("
                              (str "id int8 DEFAULT nextval('" seq-name-1 "'),")
                              (str "id_group int8 NOT NULL DEFAULT currval('" seq-name-1 "'),")
                              (str "version int8 NOT NULL DEFAULT nextval('" seq-name-2 "'),")
                              cols-str ","
                              "added TIMESTAMP DEFAULT now(),"
                              "PRIMARY KEY (version));"])
        create-unique-id-index
        (create-index true schema table-name ["id"] nil)
        create-identity-index
        (create-index true schema table-name identity-columns "id IS NOT NULL")]
    (println create-seq-sql-1)
    (println create-seq-sql-2)
    (println create-table-sql)
    (println create-unique-id-index)
    (println create-identity-index)))
;=======================================================================================================================
(defn replace-by
  [^CharSequence s re f]
  (let [m (re-matcher re s)]
    (if (.find m)
      (let [buffer (StringBuffer. (.length s))]
        (loop [found true]
          (if found
            (do (.appendReplacement m buffer (Matcher/quoteReplacement (f (re-groups m))))
                (recur (.find m)))
            (do (.appendTail m buffer)
                (.toString buffer)))))
      s)))

(defn postgres-format
  "Formats a sql vector query for Postgres, replacing ? with $ per Postgres SQL style"
  [x]
  (let [idx (atom 0)]
    (-> x
        (hsql/format :quoting :ansi)
        (update-in
          [0]
          (fn [sql-string]
            (replace-by
              sql-string #"\?"
              (fn [_] (str "$" (swap! idx inc)))))))))


(defn immutable-insert!
  ([db ^Keyword table-name ^IPersistentMap identity-col-vals ^IFn f]
   (immutable-insert! db table-name identity-col-vals f true))
  ([db ^Keyword table-name ^IPersistentMap identity-col-vals ^IFn f ^Boolean commit-tx?]
   (go
     (let [tx (<! (begin! db))]
       (<! (immutable-insert! db table-name identity-col-vals f commit-tx? tx)))))
  ([db ^Keyword table-name ^IPersistentMap identity-col-vals ^IFn f ^Boolean commit-tx? ^Transaction tx]
   (let [table-name-str (name table-name)
         _ (assert (fn? f) "f must be a function")
         _ (assert (not (nil? db)) "Database has gone away, not your fault!")
         _ (assert (and (not (empty? identity-col-vals)) (map? identity-col-vals))
                   "identity-kv-m must be supplied as a map {:col1 v1 :col2 v2}")
         where-part (vec (conj (map (fn [[k v]] [:= k v]) identity-col-vals) :and))
         advisory-lock-hash (Murmur3/hashUnencodedChars (apply str (vals identity-col-vals)))
         ret-ch
         (go-try
           ;exceptions in any of the queries propagate the exception all the way back the caller (good)
           ;READ QUERIES
           ;=======================================================================================
           ;acquire advisory locks
           (let [_ (<? (query! tx [(str "SELECT pg_advisory_xact_lock(" advisory-lock-hash ");")]))
                 qv (postgres-format {:select [:*] :from [table-name] :where (conj where-part [:not= :id nil])})

                 ;try to grab existing identity
                 rs1 (<? (query! tx qv))
                 ;if 'prev_version' is :na, then the 'prev_version' column does not exist in the table
                 [{:keys [id id_group version prev_version] :or {prev_version :na} :as old-m}] rs1

                 ;_ (timbre/info "Point 1" old-m)
                 new-m
                 (-> (f old-m)
                     (assoc :id_group id_group :id id)
                     (dissoc :added :version))

                 old-m-for-compare (dissoc old-m :added :version)
                 ;gran insert-duplicates? from f's metadata
                 {:keys [insert-duplicates?]} (meta f)]
             ;=======================================================================================
             ;WRITE QUERIES
             ;=======================================================================================
             #_(timbre/info "new-m" new-m)
             #_(timbre/info "old-m-for-compare" old-m-for-compare)

             (if (and (= false insert-duplicates?) (= new-m old-m-for-compare))
               ;no change and insert-duplicates? is false
               ;return old-m
               (do
                 (when commit-tx? (<? (commit! tx)))
                 [tx old-m])
               ;else, do the insert
               ;invalidate previous version
               (let [_
                     (when old-m
                       (<?
                         (update! tx {:table table-name-str :where ["id = $1 AND version = $2" id version]} {:id nil})))

                     ;insert new row
                     insert-result
                     ;QUERY 4
                     ;either new-m or a brand-new identity
                     (<? (insert! tx {:table table-name-str :returning "*"} (if id new-m (f nil))))

                     {[new-m-from-db] :rows} insert-result
                     _ (when commit-tx? (<? (commit! tx)))]
                 ;=======================================================================================
                 ;return the tx and the new row from db
                 [tx new-m-from-db]))))]
     ;return a channel
     ret-ch)))

(defn get! [db ^Keyword table-name]
  (execute!
    db
    (postgres-format
      {:select [:*] :from [table-name] :where [:not= :id nil]})))

(defn any-query! [db query-m]
  (execute! db (postgres-format query-m)))
