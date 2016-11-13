(defproject immutable-sql "0.1.0"
  :description "Immutable SQL"
  :url "https://github.com/raspasov/immutable-sql"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :signing {:gpg-key "1891E1D8"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [funcool/cuerdas "2.0.0"]
                 [honeysql "0.8.1"]
                 [alaisi/postgres.async "0.8.0"]
                 [fullcontact/full.async "1.0.0"]])
