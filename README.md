# milvus-clj

Clojure libraray for [Milvus](https://github.com/milvus-io/milvus).

## Usage

```clojure
(let [db-name "mydb"
        collection-name "mycoll"]
    (with-open [client (milvus-client {:host "localhost" :port 19530 :database db-name})]
      (set-log-level client :debug)

      (println "--- drop collection")
      (drop-collection client collection-name)

      (println "--- create collection")
      (create-collection client {:collection-name collection-name
                                 :description "a collection for search"
                                 :field-types [{:primary-key? true
                                                :auto-id? false
                                                :data-type :int64
                                                :name "uid"
                                                :description "unique id"}
                                               {:data-type :float-vector
                                                :name "embedding"
                                                :description "embeddings"
                                                :dimension 3}]})

      (println "--- create index")
      (create-index client {:collection-name collection-name
                            :field-name "embedding"
                            :index-type :flat
                            :index-name "embedding_index"
                            :metric-type :l2})

      (println "--- load collection")
      (load-collection client {:collection-name collection-name})

      (println "--- insert")
      (println (insert client {:collection-name collection-name
                               :fields [{:name "uid" :values [1 2]}
                                        {:name "embedding" :values [(map float [0.1 0.2 0.3])
                                                                    (map float [0.4 0.5 0.6])]}]}))

      (Thread/sleep 1000)

      (println "--- delete")
      (println (delete client {:collection-name collection-name
                               :expr "uid == 1"}))

      (Thread/sleep 1000)

      (println "--- search")
      (search client {:collection-name collection-name
                      :metric-type :l2
                      :vectors [(map float [0.3 0.4 0.5])
                                (map float [0.1 0.2 0.3])]
                      :vector-field-name "embedding"
                      :out-fields ["uid" "embedding"]
                      :top-k 2})))
```