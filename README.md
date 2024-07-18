[![Clojars Project](https://img.shields.io/clojars/v/com.constacts/milvus-clj.svg)](https://clojars.org/com.constacts/milvus-clj)

# milvus-clj

Clojure library for [Milvus](https://github.com/milvus-io/milvus).

## Clojure CLI/deps.edn

```
com.constacts/milvus-clj {:mvn/version "0.2.8"}
```

## Usage

```clojure
(require '[milvus-clj.core :as milvus])
```

#### Create Client

```clojure
(def client (milvus/client {:host "localhost" :port 19530 :database "mydb"}))
```

### Create Collection

```clojure
(milvus/create-collection client {:collection-name "mycoll"
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
```


### Create Index

```clojure
(milvus/create-index client {:collection-name "mycoll"
                             :field-name "embedding"
                             :index-type :flat
                             :index-name "embedding_index"
                             :metric-type :l2})
```

### Load Collection

```clojure
(milvus/load-collection client {:collection-name "mycoll"})
```

### Insert 

```clojure
(milvus/insert client {:collection-name "mycoll"
                       :fields [{:name "uid" :values [1 2]}
                                {:name "embedding" :values [(map float [0.1 0.2 0.3])
                                                            (map float [0.4 0.5 0.6])]}]}

;; or

(milvus/insert client {:collection-name "mycoll"
                       :rows [{:uid 1
                               :embedding (map float [0.1 0.2 0.3])} 
                              {:uid 2
                               :embedding (map float [0.4 0.5 0.6])}]})

;; for json type

(milvus/insert client {:collection-name "mycoll"
                       :rows [{:uid 1
                               :etc {:filename "README.md"}}]})

```

### Delete

```clojure
(milvus/delete client {:collection-name "mycoll"
                       :expr "uid == 1"})
```

### Search

```clojure
(milvus/search client {:collection-name "mycoll"
                       :metric-type :l2
                       :vectors [(map float [0.3 0.4 0.5])
                                 (map float [0.1 0.2 0.3])]
                       :vector-field-name "embedding"
                       :out-fields ["uid" "embedding"]
                       :top-k 2})
```

### Hybrid Search

```clojure
(milvus/hybrid-search client {:collection-name "mycoll"
                              :search-requests [{:vector-field-name "dense_vector"
                                                  :metric-type :l2
                                                  :float-vectors [(gen-float-vector 10)]
                                                  :top-k 10}
                                                {:vector-field-name "sparse_vector"
                                                  :metric-type :ip
                                                  :sparse-float-vectors [(gen-sparse)]
                                                  :top-k 10}]
                              :out-fields ["pk" "dense_vector" "sparse_vector"]
                              :ranker {:type :weighted
                                        :weights [0.7 0.3]}
                              :top-k 5}))
```

### Query

```clojure
(milvus/query client {:collection-name "mycoll"
                      :expr "uid == 2"
                      :out-fields ["uid" "embedding"]})
```

### Drop Index

```clojure
(milvus/drop-index client {:collection-name "mycoll"
                           :index-name "embedding_index"})
```

### Drop Collection

```clojure
(milvus/drop-collection client "mycoll")
```

### Release Collection


```clojure
(milvus/release-collection client {:collection-name "mycoll"})
```
