[![Clojars Project](https://img.shields.io/clojars/v/com.constacts/milvus-clj.svg)](https://clojars.org/com.constacts/milvus-clj)

# milvus-clj

Clojure library for [Milvus](https://github.com/milvus-io/milvus).

## Clojure CLI/deps.edn

```
com.constacts/milvus-clj {:mvn/version "0.2.1"}
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
                                                            (map float [0.4 0.5 0.6])]}]})
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
