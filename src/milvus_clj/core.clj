(ns milvus-clj.core
  (:import [io.milvus.client MilvusClient MilvusServiceClient]
           [io.milvus.param
            ConnectParam
            LogLevel
            R]
           [io.milvus.param.collection
            CreateDatabaseParam
            DropDatabaseParam]
           [java.util.concurrent TimeUnit]))

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

(defn create-database [^MilvusClient client database-name]
  (let [param (.build (doto (CreateDatabaseParam/newBuilder)
                        (.withDatabaseName database-name)))
        {:keys [msg]} (bean (parse-r-response (.createDatabase client param)))]
    {:message msg}))

(defn drop-database [^MilvusClient client database-name]
  (let [param (.build (doto (DropDatabaseParam/newBuilder)
                        (.withDatabaseName database-name)))
        {:keys [msg]} (bean (parse-r-response (.dropDatabase client param)))]
    {:message msg}))

(defn list-databases [^MilvusClient client]
  (into [] (.getDbNamesList (parse-r-response (.listDatabases client)))))


;; Collection

(defn create-collection [^MilvusClient client])

(defn drop-collection [^MilvusClient client])

(defn insert [^MilvusClient client])

;; Index

(defn create-index [^MilvusClient client])

(defn drop-index [^MilvusClient client])

;; Query And Search

(defn query [])

(defn search [])




(comment
  ;;

  (with-open [client (milvus-client {:host "localhost" :port 19530})]
    (drop-database client "mydb"))


  ;;;
  )