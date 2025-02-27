(ns milvus-clj.core
  (:require [clojure.walk :refer [postwalk stringify-keys]])
  (:import [com.google.gson Gson JsonObject]
           [io.milvus.client MilvusClient MilvusServiceClient]
           [io.milvus.common.clientenum ConsistencyLevelEnum]
           [io.milvus.grpc DataType QueryResults SearchResults]
           [io.milvus.param
            ConnectParam
            IndexType
            LogLevel
            MetricType
            R]
           [io.milvus.param.collection
            CreateCollectionParam
            CreateDatabaseParam
            DropCollectionParam
            DropDatabaseParam
            FieldType
            FlushParam
            LoadCollectionParam
            ReleaseCollectionParam]
           [io.milvus.param.dml
            AnnSearchParam
            DeleteParam
            HybridSearchParam
            InsertParam
            InsertParam$Field
            QueryParam
            SearchParam
            UpsertParam]
           [io.milvus.param.dml.ranker RRFRanker WeightedRanker]
           [io.milvus.param.index CreateIndexParam DropIndexParam]
           [io.milvus.response MutationResultWrapper QueryResultsWrapper SearchResultsWrapper]
           [java.util ArrayList]
           [java.util.concurrent TimeUnit]))


;; Connections

(defn client
  "This function creates a Milvus client instance."
  [{:keys [host
           port
           database
           username
           password
           client-key-path
           client-pem-path
           ca-pem-path
           server-pem-path
           connection-timeout-ms
           keep-alive-timeout-ms
           keep-alive-without-calls?
           secure?
           idle-timeout-ms]}]
  (let [param (cond-> (ConnectParam/newBuilder)
                      host (.withHost host)
                      port (.withPort port)
                      database (.withDatabaseName database)
                      connection-timeout-ms (.withConnectTimeout connection-timeout-ms TimeUnit/MILLISECONDS)
                      keep-alive-timeout-ms (.withKeepAliveTime keep-alive-timeout-ms TimeUnit/MILLISECONDS)
                      keep-alive-without-calls? (.keepAliveWithoutCalls keep-alive-without-calls?)
                      secure? (.withSecure secure?)
                      idle-timeout-ms (.withIdleTimeout idle-timeout-ms TimeUnit/MILLISECONDS)
                      (and username password) (.withAuthorization username password)
                      (and secure? client-key-path) (.withClientKeyPath client-key-path)
                      (and secure? client-pem-path) (.withClientPemPath client-pem-path)
                      (and secure? ca-pem-path) (.withCaPemPath ca-pem-path)
                      (and secure? server-pem-path) (.withServerPemPath server-pem-path)
                      true .build)]
    (MilvusServiceClient. ^ConnectParam param)))

(defn timeout
  "The timeout setting for RPC call."
  [^MilvusClient client timeout-ms]
  (.withTimeout client timeout-ms TimeUnit/MILLISECONDS))

(defn close
  "Disconnects from a Milvus server with configurable timeout value. Call this method before
   the application terminates. This method throws an InterruptedException exception if it is
   interrupted."
  ([^MilvusClient client]
   (close client 10))
  ([^MilvusClient client max-wait-sec]
   (.close client max-wait-sec)))

(defn set-log-level [^MilvusClient client level]
  (let [levels {:debug   LogLevel/Debug
                :info    LogLevel/Info
                :warning LogLevel/Warning
                :error   LogLevel/Error}]
    (if-let [log-level (get levels level)]
      (.setLogLevel client log-level)
      (throw (ex-info (str "Invalid log level: " level) {})))))

(defn- success? [status]
  (zero? status))

(defn- parse-r-response [^R response]
  (if-let [exception (.getException response)]
    (do
      (.printStackTrace exception)
      (throw (ex-info (.getMessage exception) {:response response})))
    (let [status (.getStatus response)]
      (if (success? status)
        (.getData response)
        (throw (ex-info (str "Request failed: " status) {:response response}))))))

;; Database

(defn- parse-rpc-status [response]
  {:message (-> response
                parse-r-response
                bean
                :msg)})

(defn create-database
  "This function creates a database."
  [^MilvusClient client database-name]
  (let [param (.build (doto (CreateDatabaseParam/newBuilder)
                        (.withDatabaseName database-name)))]
    (parse-rpc-status (.createDatabase client param))))

(defn drop-database
  " This function drops a database. Note that this method drops all data in the database."
  [^MilvusClient client database-name]
  (let [param (.build (doto (DropDatabaseParam/newBuilder)
                        (.withDatabaseName database-name)))]
    (parse-rpc-status (.dropDatabase client param))))

(defn list-databases
  "This function lists all databases in the cluster."
  [^MilvusClient client]
  (into [] (.getDbNamesList (parse-r-response (.listDatabases client)))))

;; Collection

(def data-types
  {:none                DataType/None
   :bool                DataType/Bool
   :int8                DataType/Int8
   :int16               DataType/Int16
   :int32               DataType/Int32
   :int64               DataType/Int64
   :float               DataType/Float
   :double              DataType/Double
   :string              DataType/String
   :var-char            DataType/VarChar
   :array               DataType/Array
   :json                DataType/JSON
   :binary-vector       DataType/BinaryVector
   :float-vector        DataType/FloatVector
   :float16-vector      DataType/Float16Vector
   :bfloat16-vector     DataType/BFloat16Vector
   :sparse-float-vector DataType/SparseFloatVector})

(defn- make-field-type [{:keys [name
                                primary-key?
                                description
                                data-type
                                type-params
                                dimension
                                max-length
                                auto-id?
                                partition-key?
                                element-type
                                max-capacity]}]
  (let [add-type-params (fn [^FieldType field-type type-params]
                          (doseq [{:keys [key value]} type-params]
                            (.addTypeParam field-type key value))
                          field-type)]
    (cond-> (FieldType/newBuilder)
            name (.withName name)
            primary-key? (.withPrimaryKey primary-key?)
            description (.withDescription description)
            data-type (.withDataType (or (get data-types data-type)
                                         (throw (ex-info (str "Invalid data type: " name data-type) {}))))
            type-params (add-type-params type-params)
            dimension (.withDimension (int dimension))
            max-length (.withMaxLength (int max-length))
            auto-id? (.withAutoID auto-id?)
            partition-key? (.withPartitionKey partition-key?)
            element-type (.withElementType (or (get data-types element-type)
                                               (throw (ex-info (str "Invalid data type: " name data-type) {}))))
            max-capacity (.withMaxCapacity (int max-capacity))
            true .build)))

(def ^:private consistency-levels
  {:strong     ConsistencyLevelEnum/STRONG
   :bounded    ConsistencyLevelEnum/BOUNDED
   :eventually ConsistencyLevelEnum/EVENTUALLY})

(defn create-collection
  "This function creates a collection with a specified schema."
  [^MilvusClient client {:keys [collection-name
                                shards-num
                                description
                                field-types
                                consistency-level
                                partition-num]}]
  (let [field-types' (map make-field-type field-types)
        param (cond-> (CreateCollectionParam/newBuilder)
                      collection-name (.withCollectionName collection-name)
                      shards-num (.withShardsNum (int shards-num))
                      description (.withDescription description)
                      field-types (.withFieldTypes (ArrayList. field-types'))
                      consistency-level (.withConsistencyLevel
                                          (or (get consistency-levels consistency-level)
                                              (throw (ex-info (str "Invalid consistency level: "
                                                                   consistency-level) {}))))
                      partition-num (.withPartitionNum (int partition-num))
                      true .build)]
    (parse-rpc-status (.createCollection client param))))

(defn drop-collection
  "This function drops a specified collection."
  [^MilvusClient client collection-name]
  (let [param (.build (doto (DropCollectionParam/newBuilder)
                        (.withCollectionName collection-name)))]
    (parse-rpc-status (.dropCollection client param))))

(def gson (Gson.))

(defn- map->json-object [m]
  (postwalk (fn [x]
              (if (map? x)
                (->> x
                     stringify-keys
                     (.toJsonTree gson)
                     .getAsJsonObject)
                x))
            m))

(defn- ->data-type [value]
  (if (map? value)
    (map->json-object value)
    value))

(defn- make-field-value [{:keys [name values]}]
  (InsertParam$Field. name (ArrayList. (map ->data-type values))))

(defn- parse-mutation-result [response]
  (let [mutation-result (parse-r-response response)
        ^MutationResultWrapper mw (MutationResultWrapper. mutation-result)
        long-ids (try (.getLongIDs mw) (catch Exception _ nil))
        string-ids (try (.getStringIDs mw) (catch Exception _ nil))]
    {:insert-count (try (.getInsertCount mw) (catch Exception _ nil))
     :ids          (vec (or long-ids string-ids))
     :delete-count (try (.getDeleteCount mw) (catch Exception _ nil))
     :operation-ts (.getOperationTs mw)}))

(defn insert
  "This function inserts entities into a specified collection."
  [^MilvusClient client {:keys [collection-name
                                partition-name
                                fields
                                rows]}]
  (let [rows' (map map->json-object rows)
        fields' (map make-field-value fields)
        param (cond-> (InsertParam/newBuilder)
                      collection-name (.withCollectionName collection-name)
                      partition-name (.withPartitionName partition-name)
                      fields (.withFields (ArrayList. fields'))
                      rows (.withRows (ArrayList. rows'))
                      true .build)]
    (parse-mutation-result (.insert client param))))

(defn delete
  "This function deletes an entity or entities from a collection by filtering the primary key field
   with boolean expression."
  [^MilvusClient client {:keys [collection-name
                                partition-name
                                expr]}]
  (let [param (cond-> (DeleteParam/newBuilder)
                      collection-name (.withCollectionName collection-name)
                      partition-name (.withPartitionName partition-name)
                      expr (.withExpr expr)
                      true .build)]
    (parse-mutation-result (.delete client param))))

(defn- parse-flush-response [response]
  (parse-r-response response))

(defn flush-collections
  "This method triggers a flush action in which all growing segments in the specified collection
   are marked as sealed and then flushed to storage."
  [^MilvusClient client {:keys [collection-names
                                sync-flush?
                                sync-flush-waiting-interval-ms
                                sync-flush-waiting-timeout-sec]}]
  (let [param (cond-> (FlushParam/newBuilder)
                      collection-names (.withCollectionNames (ArrayList. collection-names))
                      sync-flush? (.withSyncFlush sync-flush?)
                      sync-flush-waiting-interval-ms (.withSyncFlushWaitingInterval sync-flush-waiting-interval-ms)
                      sync-flush-waiting-timeout-sec (.withSyncFlushWaitingTimeout sync-flush-waiting-timeout-sec)
                      true .build)]
    (parse-flush-response (.flush client param))))

(defn load-collection
  "This function loads the specified collection and all the data within to memory for search or
   query."
  [^MilvusClient client {:keys [collection-name
                                sync-load?
                                sync-load-waiting-interval
                                sync-load-waiting-timeout
                                replica-number
                                refresh?]}]

  (let [param (cond-> (LoadCollectionParam/newBuilder)
                      collection-name (.withCollectionName collection-name)
                      sync-load? (.withSyncLoad sync-load?)
                      sync-load-waiting-interval (.withSyncLoadWaitingInterval sync-load-waiting-interval)
                      sync-load-waiting-timeout (.withSyncLoadWaitingTimeout sync-load-waiting-timeout)
                      replica-number (.withReplicaNumber (int replica-number))
                      refresh? (.withRefresh refresh?)
                      true .build)]
    (.loadCollection client param)))


(defn release-collection
  "This function releases the specified collection and all data within it from memory."
  [^MilvusClient client {:keys [collection-name]}]
  (let [param (.build (doto (ReleaseCollectionParam/newBuilder)
                        (.withCollectionName collection-name)))]
    (.releaseCollection client param)))

;; Index

(def metric-types
  {:l2      MetricType/L2
   :ip      MetricType/IP
   :cosine  MetricType/COSINE
   :hamming MetricType/HAMMING
   :jaccard MetricType/JACCARD})

(def index-types
  {:none                  IndexType/None

   ;; Only supported for float vectors
   :flat                  IndexType/FLAT
   :ivf-flat              IndexType/IVF_FLAT
   :ivf-sq8               IndexType/IVF_SQ8
   :ivf-pq                IndexType/IVF_PQ
   :hnsw                  IndexType/HNSW
   :diskann               IndexType/DISKANN
   :autoindex             IndexType/AUTOINDEX
   :scann                 IndexType/SCANN

   ;; GPU indexes only for float vectors
   :gpu-ivf-flat          IndexType/GPU_IVF_FLAT
   :gpu-ivf-pq            IndexType/GPU_IVF_PQ
   :GPU_BRUTE_FORCE       IndexType/GPU_BRUTE_FORCE
   :GPU_CAGRA             IndexType/GPU_CAGRA

   ;; Only supported for binary vectors
   :bin-flat              IndexType/BIN_FLAT
   :bin-ivf-flat          IndexType/BIN_IVF_FLAT

   ;; Only for varchar type field
   :trie                  IndexType/TRIE

   ;; Only for scalar type field
   :stl-sort              IndexType/STL_SORT

   :inverted              IndexType/INVERTED
   :sparse-inverted-index IndexType/SPARSE_INVERTED_INDEX
   :sparse-wand           IndexType/SPARSE_WAND})

(defn create-index
  "This function creates an index on a field in a specified collection."
  [^MilvusClient client {:keys [collection-name
                                field-name
                                index-type
                                index-name
                                metric-type
                                extra-param
                                sync-mode?
                                sync-load-waiting-interval
                                sync-load-waiting-timeout]}]
  (let [param (cond-> (CreateIndexParam/newBuilder)
                      collection-name (.withCollectionName collection-name)
                      field-name (.withFieldName field-name)
                      index-type (.withIndexType (or (get index-types index-type)
                                                     (throw (ex-info (str "Invalid index type: "
                                                                          index-type) {}))))
                      index-name (.withIndexName index-name)
                      metric-type (.withMetricType (or (get metric-types metric-type)
                                                       (throw (ex-info (str "Invalid metric type: "
                                                                            metric-type) {}))))
                      extra-param (.withExtraParam extra-param)
                      sync-mode? (.withSyncMode sync-mode?)
                      sync-load-waiting-interval (.withSyncWaitingInterval sync-load-waiting-interval)
                      sync-load-waiting-timeout (.withSyncWaitingTimeout sync-load-waiting-timeout)
                      true .build)]
    (parse-rpc-status (.createIndex client param))))

(defn drop-index
  "This function drops an index and its corresponding index file in the collection."
  [^MilvusClient client {:keys [collection-name index-name]}]
  (let [param (cond-> (DropIndexParam/newBuilder)
                      collection-name (.withCollectionName collection-name)
                      index-name (.withIndexName index-name)
                      true .build)]
    (parse-rpc-status (.dropIndex client param))))

;; Query And Search

(defn query
  "This function queries entity(s) based on scalar field(s) filtered by boolean expression."
  [^MilvusClient client {:keys [collection-name
                                consistency-level
                                partition-names
                                travel-timestamp
                                out-fields
                                expr
                                offset
                                limit
                                ignore-growing?]}]
  (let [param (cond-> (QueryParam/newBuilder)
                      collection-name (.withCollectionName collection-name)
                      consistency-level (.withConsistencyLevel
                                          (or (get consistency-levels consistency-level)
                                              (throw (ex-info (str "Invalid consistency level: "
                                                                   consistency-level) {}))))
                      partition-names (.withPartitionNames (ArrayList. partition-names))
                      travel-timestamp (.withTravelTimestamp travel-timestamp)
                      out-fields (.withOutFields (ArrayList. out-fields))
                      expr (.withExpr expr)
                      offset (.withOffset offset)
                      limit (.withLimit limit)
                      ignore-growing? (.withIgnoreGrowing ignore-growing?)
                      true .build)
        ^QueryResults query-results (parse-r-response (.query client param))
        ^QueryResultsWrapper query-results-wrapper (QueryResultsWrapper. query-results)]
    (mapv #(-> % bean :fieldValues) (.getRowRecords query-results-wrapper))))

(defn search
  "This function conducts an approximate nearest neighbor (ANN) search on a vector field and pairs
   up with boolean expression to conduct filtering on scalar fields before searching."
  [^MilvusClient client {:keys [collection-name
                                consistency-level
                                partition-names
                                travel-timestamp
                                out-fields
                                expr
                                metric-type
                                vector-field-name
                                top-k
                                vectors
                                round-decimal
                                params
                                ignore-growing?]}]
  (let [param (cond-> (SearchParam/newBuilder)
                      collection-name (.withCollectionName collection-name)
                      consistency-level (.withConsistencyLevel
                                          (or (get consistency-levels consistency-level)
                                              (throw (ex-info (str "Invalid consistency level: "
                                                                   consistency-level) {}))))
                      partition-names (.withPartitionNames (ArrayList. partition-names))
                      travel-timestamp (.withTravelTimestamp travel-timestamp)
                      out-fields (.withOutFields (ArrayList. out-fields))
                      expr (.withExpr expr)
                      metric-type (.withMetricType (or (get metric-types metric-type)
                                                       (throw (ex-info (str "Invalid metric type: "
                                                                            metric-type) {}))))
                      vector-field-name (.withVectorFieldName vector-field-name)
                      top-k (.withTopK (int top-k))
                      vectors (.withVectors (let [vector-list (ArrayList.)]
                                              (doseq [vector vectors]
                                                (.add vector-list (ArrayList. vector)))
                                              vector-list))
                      round-decimal (.withRoundDecimal (int round-decimal))
                      params (.withParams params)
                      ignore-growing? (.withIgnoreGrowing ignore-growing?)
                      true .build)
        ^SearchResults search-results (parse-r-response (.search client param))
        ^SearchResultsWrapper search-results-wrapper (SearchResultsWrapper. (.getResults search-results))]
    (vec (map-indexed
           (fn [idx _]
             (mapv #(let [{:keys [fieldValues longID score strID]} (bean %)]
                      {:id       (or longID strID)
                       :distance score
                       :entity   fieldValues})
                   (.getIDScore search-results-wrapper idx)))
           vectors))))

(defn- ann-search-param [{:keys [expr
                                 metric-type
                                 vector-field-name
                                 top-k
                                 params
                                 float-vectors
                                 binary-vectors
                                 float16-vectors
                                 b-float16-vectors
                                 sparse-float-vectors]}]
  (cond-> (AnnSearchParam/newBuilder)
          expr (.withExpr expr)
          metric-type (.withMetricType (or (get metric-types metric-type)
                                           (throw (ex-info (str "Invalid metric type: "
                                                                metric-type) {}))))
          vector-field-name (.withVectorFieldName vector-field-name)
          top-k (.withTopK (int top-k))
          params (.withParams params)
          float-vectors (.withFloatVectors float-vectors)
          binary-vectors (.withBinaryVectors binary-vectors)
          float16-vectors (.withFloat16Vectors float16-vectors)
          b-float16-vectors (.withBFloat16Vectors b-float16-vectors)
          sparse-float-vectors (.withSparseFloatVectors sparse-float-vectors)
          true .build))

(defn rrf-ranker [{:keys [k]}]
  (cond-> (RRFRanker/newBuilder)
          k (.withK k)
          true .build))

(defn weighted-ranker [{:keys [weights]}]
  (cond-> (WeightedRanker/newBuilder)
          weights (.withWeights weights)
          true .build))

(defn- get-vector-count [search-requests]
  (let [request (first search-requests)]
    (count (or (:float-vectors request)
               (:binary-vectors request)
               (:float16-vectors request)
               (:b-float16-vectors request)
               (:sparse-float-vectors request)))))

(defn hybrid-search
  "This method conducts an approximate nearest neighbor (ANN) search on multiple vector fields and
   returns search results after reranking."
  [^MilvusClient client {:keys [collection-name
                                consistency-level
                                partition-names
                                out-fields
                                top-k
                                round-decimal
                                search-requests
                                ranker]}]
  (let [add-search-requests (fn [builder]
                              (doseq [search-request search-requests]
                                (.addSearchRequest builder (ann-search-param search-request)))
                              builder)
        with-ranker (fn [builder]
                      (case (:type ranker)
                        :rrf (.withRanker builder (rrf-ranker (dissoc ranker :type)))
                        :weighted (.withRanker builder (weighted-ranker (dissoc ranker :type)))
                        nil)
                      builder)
        param (cond-> (HybridSearchParam/newBuilder)
                      collection-name (.withCollectionName collection-name)
                      consistency-level (.withConsistencyLevel
                                          (or (get consistency-levels consistency-level)
                                              (throw (ex-info (str "Invalid consistency level: "
                                                                   consistency-level) {}))))
                      partition-names (.withPartitionNames (ArrayList. partition-names))
                      out-fields (.withOutFields (ArrayList. out-fields))
                      top-k (.withTopK (int top-k))
                      round-decimal (.withRoundDecimal (int round-decimal))
                      true with-ranker
                      true add-search-requests
                      true .build)

        ^SearchResults search-results (parse-r-response (.hybridSearch client param))
        ^SearchResultsWrapper search-results-wrapper (SearchResultsWrapper. (.getResults search-results))]
    (vec (map
           (fn [idx]
             (mapv #(let [{:keys [fieldValues longID score strID]} (bean %)]
                      {:id       (or longID strID)
                       :distance score
                       :entity   fieldValues})
                   (.getIDScore search-results-wrapper idx)))
           (range (get-vector-count search-requests))))))

(defn upsert
  "This method inserts new entities into a specified collection, and replaces them if the entities
   already exist."
  [^MilvusClient client {:keys [collection-name
                                partition-name
                                fields
                                rows]}]
  (let [fields' (map make-field-value fields)
        rows' (map map->json-object rows)
        param (cond-> (UpsertParam/newBuilder)
                      collection-name (.withCollectionName collection-name)
                      partition-name (.withPartitionName partition-name)
                      fields' (.withFields (ArrayList. fields'))
                      rows (.withRows (ArrayList. rows'))
                      true .build)]
    (parse-mutation-result (.upsert client param))))

(comment
  (import [java.util Random TreeMap])

  (defn gen-sparse []
    (let [ran (Random.)
          sparse (TreeMap.)
          dim (inc (.nextInt ran 10))]
      (dotimes [i dim]
        (.put sparse (long (.nextInt ran 1000000)) (.nextFloat ran)))
      sparse))

  (defn gen-float-vector [dim]
    (let [ran (Random.)]
      (vec (repeatedly dim #(float (.nextFloat ran))))))

  (with-open [client (client {:host "localhost"
                              :port 19530})]
    (create-collection client
                       {:collection-name "test"
                        :field-types     (concat [{:primary-key? true
                                                   :data-type    :int64
                                                   :name         "id"}
                                                  {:data-type  :var-char
                                                   :max-length 256
                                                   :name       "text"}
                                                  {:data-type :json
                                                   :name      "tags"}
                                                  {:data-type :float-vector
                                                   :name      "dense_vector"
                                                   :dimension 10}
                                                  {:data-type :sparse-float-vector
                                                   :name      "sparse_vector"}])})

    #_(create-index client {:collection-name "test"
                            :field-name      "sparse_vector"
                            :index-type      :sparse-inverted-index
                            :index-name      "sparse_vector"
                            :metric-type     :ip
                            ;; :extra-param "{\"drop_ratio_build\": 0.2}"
                            })

    #_(create-index client {:collection-name "test"
                            :field-name      "dense_vector"
                            :index-type      :flat
                            :index-name      "dense_vector"
                            :metric-type     :l2})

    #_(load-collection client {:collection-name "test"})

    #_(insert client {:collection-name "test"
                      :rows            [{:id            1
                                         :text          "hello"
                                         :tags          {:value ["a" "b" "c"]}
                                         :dense_vector  (gen-float-vector 10)
                                         :sparse_vector (gen-sparse)}]})

    #_(insert client {:collection-name "test"
                      :fields          [{:name "id" :values [2]}
                                        {:name "text" :values ["world"]}
                                        {:name "tags" :values [{:value ["d" "e" "f"]}]}
                                        {:name "dense_vector" :values [(gen-float-vector 10)]}
                                        {:name "sparse_vector" :values [(gen-sparse)]}]})


    #_(upsert client {:collection-name "test"
                      :rows            [{:id            1
                                         :text          "hello2"
                                         :dense_vector  (gen-float-vector 10)
                                         :sparse_vector (gen-sparse)}]})


    (search client {:collection-name   "test"
                    :metric-type       :l2
                    :vectors           [(gen-float-vector 10)]
                    :vector-field-name "dense_vector"
                    :expr              "json_contains_all(tags[\"value\"], [\"a\"])"
                    :out-fields        ["id" "dense_vector" "sparse_vector"]
                    :top-k             2})


    #_(hybrid-search client {:collection-name "test"
                             :search-requests [{:vector-field-name "dense_vector"
                                                :metric-type       :l2
                                                :float-vectors     [(gen-float-vector 10)]
                                                :top-k             10}
                                               {:vector-field-name    "sparse_vector"
                                                :metric-type          :ip
                                                :sparse-float-vectors [(gen-sparse)]
                                                :top-k                10}]
                             :out-fields      ["id" "dense_vector" "sparse_vector"]
                             :ranker          {:type    :weighted
                                               :weights [0.7 0.3]}
                             :top-k           5}))


  ;;
  )
