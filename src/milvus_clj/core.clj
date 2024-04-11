(ns milvus-clj.core
  (:import [io.milvus.client MilvusClient MilvusServiceClient]
           [io.milvus.param
            ConnectParam
            LogLevel
            R
            MetricType
            IndexType]
           [io.milvus.param.collection
            CreateDatabaseParam
            CreateCollectionParam
            FieldType
            DropDatabaseParam
            DropCollectionParam
            LoadCollectionParam
            FlushParam]
           [io.milvus.common.clientenum ConsistencyLevelEnum]
           [io.milvus.param.dml InsertParam$Field InsertParam SearchParam]
           [io.milvus.param.index CreateIndexParam]
           [io.milvus.response MutationResultWrapper SearchResultsWrapper]
           [io.milvus.grpc DataType FlushResponse]
           [java.util.concurrent TimeUnit]
           [java.util ArrayList]))

;; Connections

(defn milvus-client
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

(defn timeout [^MilvusClient client timeout-ms]
  (.withTimeout client timeout-ms TimeUnit/MILLISECONDS))

(defn close
  ([^MilvusClient client]
   (close client 10))
  ([^MilvusClient client max-wait-sec]
   (.close client max-wait-sec)))

(defn set-log-level [^MilvusClient client level]
  (let [levels {:debug LogLevel/Debug
                :info LogLevel/Info
                :warning LogLevel/Warning
                :error LogLevel/Error}]
    (if-let [log-level (get levels level)]
      (.setLogLevel client log-level)
      (throw (ex-info (str "Invalid log level: " level) {})))))

(defn- success? [status]
  (zero? status))

(defn- parse-r-response [^R response]
  (if-let [exception (.getException response)]
    (throw (ex-info (.getMessage exception) {:response response}))
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

(defn create-database [^MilvusClient client database-name]
  (let [param (.build (doto (CreateDatabaseParam/newBuilder)
                        (.withDatabaseName database-name)))]
    (parse-rpc-status (.createDatabase client param))))

(defn drop-database [^MilvusClient client database-name]
  (let [param (.build (doto (DropDatabaseParam/newBuilder)
                        (.withDatabaseName database-name)))]
    (parse-rpc-status (.dropDatabase client param))))

(defn list-databases [^MilvusClient client]
  (into [] (.getDbNamesList (parse-r-response (.listDatabases client)))))

;; Collection

(def data-types
  {:bool DataType/Bool
   :int8 DataType/Int8
   :int16 DataType/Int16
   :int32 DataType/Int32
   :int64 DataType/Int64
   :float DataType/Float
   :double DataType/Double
   :var-char DataType/VarChar
   :binary-vector DataType/BinaryVector
   :float-vector DataType/FloatVector})

(defn- make-field-type [{:keys [name
                                primary-key?
                                description
                                data-type
                                type-params
                                dimension
                                max-length
                                auto-id?
                                partition-key?]}]
  (let [add-type-params (fn [^FieldType field-type type-params]
                          (doseq [{:keys [key value]} type-params]
                            (.addTypeParam field-type key value)))]
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
      true .build)))

(def ^:private consistency-levels
  {:strong ConsistencyLevelEnum/STRONG
   :bounded ConsistencyLevelEnum/BOUNDED
   :eventually ConsistencyLevelEnum/EVENTUALLY})

(defn create-collection [^MilvusClient client {:keys [collection-name
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

(defn drop-collection [^MilvusClient client collection-name]
  (let [param (.build (doto (DropCollectionParam/newBuilder)
                        (.withCollectionName collection-name)))]
    (parse-rpc-status (.dropCollection client param))))

(defn- make-field [{:keys [name values]}]
  (InsertParam$Field. name (ArrayList. values)))

(defn- parse-mutation-result [response]
  (let [mutation-result (parse-r-response response)
        ^MutationResultWrapper mw (MutationResultWrapper. mutation-result)]
    {:insert-count (try (.getInsertCount mw) (catch Exception _ nil))
     ;; TODO: 둘 중 하나만 나오니 ids로 통합하자.
     :long-ids (try (.getLongIDs mw) (catch Exception _ nil))
     :string-ids (try (.getStringIDs mw) (catch Exception _ nil))
     :delete-count (try (.getDeleteCount mw) (catch Exception _ nil))
     :operation-ts (.getOperationTs mw)}))

(defn insert [^MilvusClient client {:keys [collection-name
                                           partition-name
                                           fields]}]
  (let [fields' (map make-field fields)
        param (cond-> (InsertParam/newBuilder)
                collection-name (.withCollectionName collection-name)
                partition-name (.withPartitionName partition-name)
                fields' (.withFields (ArrayList. fields'))
                true .build)]
    (parse-mutation-result (.insert client param))))

(defn- parse-flush-response [response]
  (parse-r-response response))

(defn flush-collections [^MilvusClient client {:keys [collection-names
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

(defn load-collection [^MilvusClient client {:keys [collection-name
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

;; Index

(def metric-types
  {:l2 MetricType/L2
   :ip MetricType/IP
   :cosine MetricType/COSINE
   :hamming MetricType/HAMMING
   :jaccard MetricType/JACCARD})

(def index-types
  {;; Only supported for float vectors
   :flat IndexType/FLAT
   :ivf-flat IndexType/IVF_FLAT
   :ivf-sq8 IndexType/IVF_SQ8
   :ivf-pq IndexType/IVF_PQ
   :hnsw IndexType/HNSW
   :diskann IndexType/DISKANN
   :autoindex IndexType/AUTOINDEX
   :scann IndexType/SCANN

   ;; GPU indexes only for float vectors
   :gpu-ivf-flat IndexType/GPU_IVF_FLAT
   :gpu-ivf-pq IndexType/GPU_IVF_PQ

   ;; Only supported for binary vectors
   :bin-flat IndexType/BIN_FLAT
   :bin-ivf-flat IndexType/BIN_IVF_FLAT

   ;; Only for varchar type field
   :trie IndexType/TRIE

   ;; Only for scalar type field
   :stl-sort IndexType/STL_SORT ;; only for numeric type field
   })

(defn create-index [^MilvusClient client {:keys [collection-name
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

(defn drop-index [^MilvusClient client]
  (throw (ex-info "Not implemented" {})))

;; Query And Search

(defn query []
  (throw (ex-info "Not implemented" {})))

(defn- parse-search-results [response]
  (let [search-results (parse-r-response response)
        ^SearchResultsWrapper search-results-wrapper (SearchResultsWrapper. search-results)]
    (bean search-results-wrapper)))

(defn search [^MilvusClient client {:keys [collection-name
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
                vectors (.withVectors (doto (ArrayList.)
                                        (.add (ArrayList. vectors))))
                round-decimal (.withRoundDecimal (int round-decimal))
                params (.withParams params)
                ignore-growing? (.withIgnoreGrowing ignore-growing?)
                true .build)]
    (parse-search-results (.search client param))))

(comment
  ;;
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

      (create-index client {:collection-name collection-name
                            :field-name "embedding"
                            :index-type :flat
                            :index-name "embedding_index"
                            :metric-type :l2})

      (println "--- insert")
      (insert client {:collection-name collection-name
                      :fields [{:name "uid" :values [1 2]}
                               {:name "embedding" :values [(map float [0.1 0.2 0.3])
                                                           (map float [0.4 0.5 0.6])]}]})
      ;; (println "--- flush")
      ;; (flush-collections client {:collection-names [collection-name]})
      ;; (println "--- load collection")
      ;; (load-collection client {:collection-name collection-name})
      ;; (Thread/sleep 3000)
      ;; (println "--- search")
      ;; (search client {:collection-name collection-name
      ;;                 :metric-type :l2
      ;;                 :vectors (map float [0.1 0.2 0.3])
      ;;                 :vector-field-name "embedding"
      ;;                 :top-k 2})


      ;;;
      ))



  ;;;
  )