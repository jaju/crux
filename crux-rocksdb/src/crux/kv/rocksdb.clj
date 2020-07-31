(ns ^:no-doc crux.kv.rocksdb
  "RocksDB KV backend for Crux."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [crux.kv :as kv]
            [crux.kv.rocksdb.loader]
            [crux.memory :as mem]
            [crux.system :as sys]
            [crux.io :as cio])
  (:import java.io.Closeable
           java.nio.ByteBuffer
           (java.nio.file Files Path)
           java.nio.file.attribute.FileAttribute
           java.util.function.ToIntFunction
           (org.rocksdb Checkpoint CompressionType FlushOptions LRUCache
                        Options ReadOptions RocksDB RocksIterator
                        WriteBatch WriteOptions Statistics StatsLevel)))

(set! *unchecked-math* :warn-on-boxed)

(def ^:const ^:private initial-read-buffer-limit 128)

(defn- read-value [^ToIntFunction f]
  (loop [limit initial-read-buffer-limit]
    (let [out (mem/direct-byte-buffer (mem/allocate-unpooled-buffer limit))
          result (.applyAsInt f out)]
      (cond
        (= result RocksDB/NOT_FOUND)
        nil

        (< limit result)
        (recur result)

        :else
        (mem/as-buffer out)))))

(defn- iterator->key [^RocksIterator i]
  (when (.isValid i)
    (read-value (reify ToIntFunction
                  (applyAsInt [_ out]
                    (.key i ^ByteBuffer out))))))

(defrecord RocksKvIterator [^RocksIterator i]
  kv/KvIterator
  (seek [this k]
    (.seek i (mem/direct-byte-buffer k))
    (iterator->key i))

  (next [this]
    (.next i)
    (iterator->key i))

  (prev [this]
    (.prev i)
    (iterator->key i))

  (value [this]
    (read-value (reify ToIntFunction
                  (applyAsInt [_ out]
                    (.value i ^ByteBuffer out)))))

  Closeable
  (close [this]
    (.close i)))

(defrecord RocksKvSnapshot [^RocksDB db ^ReadOptions read-options snapshot]
  kv/KvSnapshot
  (new-iterator [this]
    (->RocksKvIterator (.newIterator db read-options)))

  (get-value [this k]
    (read-value (reify ToIntFunction
                  (applyAsInt [_ out]
                    (.get db read-options (mem/direct-byte-buffer k) ^ByteBuffer out)))))

  Closeable
  (close [_]
    (.close read-options)
    (.releaseSnapshot db snapshot)))

(def ^:private default-block-cache-size (* 128 1024 1024))
(def ^:private default-block-size (* 16 1024))

(defrecord RocksKv [^RocksDB db, ^WriteOptions write-options, ^Options options, ^Closeable metrics, db-dir]
  kv/KvStore
  (new-snapshot [_]
    (let [snapshot (.getSnapshot db)]
      (->RocksKvSnapshot db
                         (doto (ReadOptions.)
                           (.setSnapshot snapshot))
                         snapshot)))

  (store [_ kvs]
    (with-open [wb (WriteBatch.)]
      (doseq [[k v] kvs]
        (.put wb (mem/direct-byte-buffer k) (mem/direct-byte-buffer v)))
      (.write db write-options wb)))

  (delete [_ ks]
    (with-open [wb (WriteBatch.)]
      (doseq [k ks]
        (.remove wb (mem/direct-byte-buffer k)))
      (.write db write-options wb)))

  (compact [_]
    (.compactRange db))

  (fsync [_]
    (with-open [flush-options (doto (FlushOptions.)
                                (.setWaitForFlush true))]
      (.flush db flush-options)))

  (count-keys [_]
    (-> (.getProperty db "rocksdb.estimate-num-keys")
        (Long/parseLong)))

  (db-dir [_]
    (str db-dir))

  (kv-name [this]
    (.getName (class this)))

  Closeable
  (close [_]
    (cio/try-close db)
    (cio/try-close options)
    (cio/try-close write-options)
    (cio/try-close metrics)))

(defn ->kv-store {::sys/deps {:metrics (fn [_])}
                  ::sys/args {:db-dir {:doc "Directory to store K/V files"
                                       :required? true
                                       :spec ::sys/path}
                              :sync? {:doc "Sync the KV store to disk after every write."
                                      :default false
                                      :spec ::sys/boolean}
                              :db-options {:doc "RocksDB Options"
                                           :spec #(instance? Options %)}
                              :disable-wal? {:doc "Disable Write Ahead Log"
                                             :default false
                                             :spec ::sys/boolean}}}
  [{:keys [db-dir sync? disable-wal? metrics db-options] :as options}]

  (RocksDB/loadLibrary)
  (let [stats (when metrics (doto (Statistics.) (.setStatsLevel (StatsLevel/EXCEPT_DETAILED_TIMERS))))
        opts (doto (or ^Options db-options (Options.))
               (cond-> metrics (.setStatistics stats))
               (.setCompressionType CompressionType/LZ4_COMPRESSION)
               (.setBottommostCompressionType CompressionType/ZSTD_COMPRESSION)
               (.setCreateIfMissing true))

        db (try
             (RocksDB/open opts (-> (Files/createDirectories ^Path db-dir (make-array FileAttribute 0))
                                    (.toAbsolutePath)
                                    (str)))
             (catch Throwable t
               (.close opts)
               (throw t)))
        metrics (when metrics (metrics db stats))]
    (map->RocksKv {:db-dir db-dir
                   :options opts
                   :db db
                   :metrics metrics
                   :write-options (doto (WriteOptions.)
                                    (.setSync (boolean sync?))
                                    (.setDisableWAL (boolean disable-wal?)))})))
