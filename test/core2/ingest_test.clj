(ns core2.ingest-test
  (:require [cheshire.core :as json]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.instant :as inst]
            [clojure.string :as str]
            [clojure.test :as t]
            [core2.core :as c2]
            [core2.ingest :as ingest]
            [core2.json :as c2-json]
            [core2.log :as log]
            [core2.util :as util]
            [core2.metadata :as meta])
  (:import [core2.core IngestLoop Node]
           [core2.ingest Ingester TransactionInstant]
           core2.object_store.ObjectStore
           clojure.lang.MapEntry
           [java.io Closeable File]
           java.nio.file.Files
           java.nio.file.attribute.FileAttribute
           [java.time Clock Duration ZoneId]
           java.util.Date
           java.util.concurrent.CompletableFuture
           org.apache.arrow.memory.BufferAllocator
           [org.apache.arrow.vector BigIntVector VectorSchemaRoot]))

(defn- ->mock-clock ^java.time.Clock [^Iterable dates]
  (let [times-iterator (.iterator dates)]
    (proxy [Clock] []
      (getZone []
        (ZoneId/of "UTC"))
      (instant []
        (if (.hasNext times-iterator)
          (.toInstant ^Date (.next times-iterator))
          (throw (IllegalStateException. "out of time")))))))

(def txs
  [[{:op :put
     :doc {:_id "device-info-demo000000",
           :api-version "23",
           :manufacturer "iobeam",
           :model "pinto",
           :os-name "6.0.1"}}
    {:op :put
     :doc {:_id "reading-demo000000",
           :device-id "device-info-demo000000",
           :cpu-avg-15min 8.654,
           :rssi -50.0,
           :cpu-avg-5min 10.802,
           :battery-status "discharging",
           :ssid "demo-net",
           :time #inst "2016-11-15T12:00:00.000-00:00",
           :battery-level 59.0,
           :bssid "01:02:03:04:05:06",
           :battery-temperature 89.5,
           :cpu-avg-1min 24.81,
           :mem-free 4.10011078E8,
           :mem-used 5.89988922E8}}]
   [{:op :put
     :doc {:_id "device-info-demo000001",
           :api-version "23",
           :manufacturer "iobeam",
           :model "mustang",
           :os-name "6.0.1"}}
    {:op :put
     :doc {:_id "reading-demo000001",
           :device-id "device-info-demo000001",
           :cpu-avg-15min 8.822,
           :rssi -61.0,
           :cpu-avg-5min 8.106,
           :battery-status "discharging",
           :ssid "stealth-net",
           :time #inst "2016-11-15T12:00:00.000-00:00",
           :battery-level 86.0,
           :bssid "A0:B1:C5:D2:E0:F3",
           :battery-temperature 93.7,
           :cpu-avg-1min 4.93,
           :mem-free 7.20742332E8,
           :mem-used 2.79257668E8}}]])

(t/deftest can-build-chunk-as-arrow-ipc-file-format
  (let [node-dir (io/file "target/can-build-chunk-as-arrow-ipc-file-format")
        mock-clock (->mock-clock [#inst "2020-01-01" #inst "2020-01-02"])
        last-tx-instant (ingest/->TransactionInstant 3496 #inst "2020-01-02")
        total-number-of-ops (count (for [tx-ops txs
                                         op tx-ops]
                                     op))]
    (util/delete-dir node-dir)

    (with-open [node (c2/->local-node node-dir)
                tx-producer (c2/->local-tx-producer node-dir {:clock mock-clock})]
      (let [^BufferAllocator a (.allocator node)
            ^ObjectStore os (.object-store node)
            ^Ingester i (.ingester node)
            ^IngestLoop il (.ingest-loop node)
            object-dir (io/file node-dir "objects")]

        (t/is (nil? @(c2/latest-completed-tx os a)))
        (t/is (nil? @(c2/latest-row-id os a)))

        (t/is (= last-tx-instant
                 (last (for [tx-ops txs]
                         @(.submitTx tx-producer tx-ops)))))

        (t/is (= last-tx-instant
                 (.awaitTx il last-tx-instant (Duration/ofSeconds 2))))

        (.finishChunk i)

        (t/is (= last-tx-instant @(c2/latest-completed-tx os a)))
        (t/is (= (dec total-number-of-ops) @(c2/latest-row-id os a)))

        (let [objects-list @(.listObjects os)]
          (t/is (= 21 (count objects-list))))

        (let [objects-list @(.listObjects os "metadata-*")]
          (t/is (= 1 (count objects-list)))
          (t/is (= "metadata-00000000.arrow" (first objects-list))))

        (c2-json/write-arrow-json-files object-dir)
        (t/is (= 42 (alength (.listFiles object-dir))))

        (doseq [^File f (.listFiles object-dir)
                :when (.endsWith (.getName f) ".json")]
          (t/is (= (json/parse-string (slurp (io/resource (str "can-build-chunk-as-arrow-ipc-file-format/" (.getName f)))))
                   (json/parse-string (slurp f)))
                (.getName f)))))))

(t/deftest can-stop-node-without-writing-chunks
  (let [node-dir (io/file "target/can-stop-node-without-writing-chunks")
        mock-clock (->mock-clock [#inst "2020-01-01" #inst "2020-01-02"])
        last-tx-instant (ingest/->TransactionInstant 3496 #inst "2020-01-02")
        total-number-of-ops (count (for [tx-ops txs
                                         op tx-ops]
                                     op))]
    (util/delete-dir node-dir)

    (with-open [node (c2/->local-node node-dir)
                tx-producer (c2/->local-tx-producer node-dir {:clock mock-clock})]
      (let [^ObjectStore os (.object-store node)
            ^IngestLoop il (.ingest-loop node)
            object-dir (io/file node-dir "objects")]

        (t/is (= last-tx-instant
                 (last (for [tx-ops txs]
                         @(.submitTx tx-producer tx-ops)))))

        (t/is (= last-tx-instant
                 (.awaitTx il last-tx-instant (Duration/ofSeconds 2))))
        (t/is (= last-tx-instant (.latestCompletedTx il)))

        (with-open [node (c2/->local-node node-dir)]
          (let [^IngestLoop il (.ingest-loop node)]
            (t/is (= last-tx-instant
                     (.awaitTx il last-tx-instant (Duration/ofSeconds 2))))
            (t/is (= last-tx-instant (.latestCompletedTx il)))))

        (t/is (zero? (alength (.listFiles object-dir))))))))

(defn- device-info-csv->doc [[device-id api-version manufacturer model os-name]]
  {:_id (str "device-info-" device-id)
   :api-version api-version
   :manufacturer manufacturer
   :model model
   :os-name os-name})

(defn- readings-csv->doc [[time device-id battery-level battery-status
                           battery-temperature bssid
                           cpu-avg-1min cpu-avg-5min cpu-avg-15min
                           mem-free mem-used rssi ssid]]
  {:_id (str "reading-" device-id)
   :time (inst/read-instant-date
          (-> time
              (str/replace " " "T")
              (str/replace #"-(\d\d)$" ".000-$1:00")))
   :device-id (str "device-info-" device-id)
   :battery-level (Double/parseDouble battery-level)
   :battery-status battery-status
   :battery-temperature (Double/parseDouble battery-temperature)
   :bssid bssid
   :cpu-avg-1min (Double/parseDouble cpu-avg-1min)
   :cpu-avg-5min (Double/parseDouble cpu-avg-5min)
   :cpu-avg-15min (Double/parseDouble cpu-avg-15min)
   :mem-free (Double/parseDouble mem-free)
   :mem-used (Double/parseDouble mem-used)
   :rssi (Double/parseDouble rssi)
   :ssid ssid})

(t/deftest can-ingest-ts-devices-mini
  (let [node-dir (io/file "target/can-ingest-ts-devices-mini")]
    (util/delete-dir node-dir)

    (with-open [node (c2/->local-node node-dir {:max-block-size 100})
                tx-producer (c2/->local-tx-producer node-dir {})
                info-reader (io/reader (io/resource "devices_mini_device_info.csv"))
                readings-reader (io/reader (io/resource "devices_mini_readings.csv"))]
      (let [^BufferAllocator a (.allocator node)
            ^ObjectStore os (.object-store node)
            ^Ingester i (.ingester node)
            ^IngestLoop il (.ingest-loop node)
            object-dir (io/file node-dir "objects")]
        (let [device-infos (map device-info-csv->doc (csv/read-csv info-reader))
              readings (map readings-csv->doc (csv/read-csv readings-reader))
              [initial-readings rest-readings] (split-at (count device-infos) readings)
              tx-ops (for [doc (concat (interleave device-infos initial-readings) rest-readings)]
                       {:op :put
                        :doc doc})]

          (t/is (= 11000 (count tx-ops)))

          (t/is (nil? (.latestCompletedTx il)))

          (let [last-tx-instant @(reduce
                                  (fn [acc tx-ops]
                                    (.submitTx tx-producer tx-ops))
                                  nil
                                  (partition-all 100 tx-ops))]

            (t/is (= last-tx-instant (.awaitTx il last-tx-instant (Duration/ofSeconds 5))))
            (t/is (= last-tx-instant (.latestCompletedTx il)))
            (.finishChunk i)

            (t/is (= last-tx-instant @(c2/latest-completed-tx os a)))
            (t/is (= (dec (count tx-ops)) @(c2/latest-row-id os a)))

            (t/is (= 11 (count @(.listObjects os "metadata-*"))))
            (t/is (= 2 (count @(.listObjects os "chunk-*-api-version*"))))
            (t/is (= 11 (count @(.listObjects os "chunk-*-battery-level*"))))))))))

(t/deftest can-ingest-ts-devices-mini-into-multiple-nodes
  (let [node-dir (io/file "target/can-ingest-ts-devices-mini-into-multiple-nodes")
        opts {:max-block-size 100}]
    (util/delete-dir node-dir)

    (with-open [node-1 (c2/->local-node node-dir opts)
                node-2 (c2/->local-node node-dir opts)
                node-3 (c2/->local-node node-dir opts)
                tx-producer (c2/->local-tx-producer node-dir {})
                info-reader (io/reader (io/resource "devices_mini_device_info.csv"))
                readings-reader (io/reader (io/resource "devices_mini_readings.csv"))]
      (let [device-infos (map device-info-csv->doc (csv/read-csv info-reader))
            readings (map readings-csv->doc (csv/read-csv readings-reader))
            [initial-readings rest-readings] (split-at (count device-infos) readings)
            tx-ops (for [doc (concat (interleave device-infos initial-readings) rest-readings)]
                     {:op :put
                      :doc doc})]

        (t/is (= 11000 (count tx-ops)))

        (let [last-tx-instant @(reduce
                                (fn [acc tx-ops]
                                  (.submitTx tx-producer tx-ops))
                                nil
                                (partition-all 100 tx-ops))]

          (doseq [^Node node (shuffle (take 6 (cycle [node-1 node-2 node-3])))
                  :let [il ^IngestLoop (.ingest-loop node)
                        os ^ObjectStore (.object-store node)]]
            (t/is (= last-tx-instant (.awaitTx il last-tx-instant (Duration/ofSeconds 5))))
            (t/is (= last-tx-instant (.latestCompletedTx il)))

            (t/is (= 11 (count @(.listObjects os "metadata-*"))))
            (t/is (= 2 (count @(.listObjects os "chunk-*-api-version*"))))
            (t/is (= 11 (count @(.listObjects os "chunk-*-battery-level*"))))))))))

(t/deftest can-ingest-ts-devices-mini-with-stop-start-and-reach-same-state
  (let [node-dir (io/file "target/can-ingest-ts-devices-mini-with-stop-start-and-reach-same-state")
        opts {:max-block-size 100}]
    (util/delete-dir node-dir)

    (with-open [tx-producer (c2/->local-tx-producer node-dir {})
                info-reader (io/reader (io/resource "devices_mini_device_info.csv"))
                readings-reader (io/reader (io/resource "devices_mini_readings.csv"))]
      (let [device-infos (map device-info-csv->doc (csv/read-csv info-reader))
            readings (map readings-csv->doc (csv/read-csv readings-reader))
            [initial-readings rest-readings] (split-at (count device-infos) readings)
            tx-ops (for [doc (concat (interleave device-infos initial-readings) rest-readings)]
                     {:op :put
                      :doc doc})
            [first-half-tx-ops second-half-tx-ops] (split-at (/ (count tx-ops) 2) tx-ops)]

        (t/is (= 5500 (count first-half-tx-ops)))
        (t/is (= 5500 (count second-half-tx-ops)))

        (let [^TransactionInstant
              first-half-tx-instant @(reduce
                                      (fn [acc tx-ops]
                                        (.submitTx tx-producer tx-ops))
                                      nil
                                      (partition-all 100 first-half-tx-ops))]

          (with-open [node (c2/->local-node node-dir opts)]
            (let [^BufferAllocator a (.allocator node)
                  ^IngestLoop il (.ingest-loop node)
                  ^ObjectStore os (.object-store node)]
              (t/is (= first-half-tx-instant (.awaitTx il first-half-tx-instant (Duration/ofSeconds 5))))
              (t/is (= first-half-tx-instant (.latestCompletedTx il)))

              (let [^TransactionInstant os-tx-instant @(c2/latest-completed-tx os a)
                    os-latest-row-id @(c2/latest-row-id os a)]
                (t/is (< (.tx-id os-tx-instant) (.tx-id first-half-tx-instant)))
                (t/is (< os-latest-row-id (count first-half-tx-ops)))

                (t/is (= 5 (count @(.listObjects os "metadata-*"))))
                (t/is (= 2 (count @(.listObjects os "chunk-*-api-version*"))))
                (t/is (= 5 (count @(.listObjects os "chunk-*-battery-level*")))))

              (let [^TransactionInstant
                    second-half-tx-instant @(reduce
                                             (fn [acc tx-ops]
                                               (.submitTx tx-producer tx-ops))
                                             nil
                                             (partition-all 100 second-half-tx-ops))]

                (t/is (<= (.tx-id first-half-tx-instant)
                          (.tx-id (.latestCompletedTx il))
                          (.tx-id second-half-tx-instant)))

                (with-open [new-node (c2/->local-node node-dir opts)]
                  (doseq [^Node node [new-node node]
                          :let [^IngestLoop il (.ingest-loop node)]]
                    (t/is (<= (.tx-id first-half-tx-instant)
                              (.tx-id (.latestCompletedTx il))
                              (.tx-id second-half-tx-instant))))

                  (doseq [^Node node [new-node node]
                          :let [^IngestLoop il (.ingest-loop node)
                                ^ObjectStore os (.object-store node)]]
                    (t/is (= second-half-tx-instant (.awaitTx il second-half-tx-instant (Duration/ofSeconds 5))))
                    (t/is (= second-half-tx-instant (.latestCompletedTx il)))

                    (t/is (= 11 (count @(.listObjects os "metadata-*"))))
                    (t/is (= 2 (count @(.listObjects os "chunk-*-api-version*"))))
                    (t/is (= 11 (count @(.listObjects os "chunk-*-battery-level*"))))))))))))))

(t/deftest scans-rowid-for-value
  (let [node-dir (io/file "target/scans-rowid-for-value")]
    (util/delete-dir node-dir)

    (with-open [node (c2/->local-node node-dir)
                tx-producer (c2/->local-tx-producer node-dir)]
      (let [^BufferAllocator allocator (.allocator node)
            ^Ingester i (.ingester node)
            ^IngestLoop il (.ingest-loop node)
            ^ObjectStore object-store (.object-store node)]

        (let [tx @(.submitTx tx-producer [{:op :put, :doc {:name "Håkan", :id 1}}])]

          (.awaitTx il tx (Duration/ofSeconds 2))

          (.finishChunk i))

        (let [last-tx @(.submitTx tx-producer [{:op :put, :doc {:name "James", :id 2}}
                                               {:op :put, :doc {:name "Jon", :id 3}}
                                               {:op :put, :doc {:name "Dan", :id 4}}])]
          (.awaitTx il last-tx (Duration/ofSeconds 2))

          (.finishChunk i))

        (t/is (= {1 "James", 2 "Jon"}
                 @(-> (.listObjects object-store "metadata-*")
                      (util/then-compose
                        (fn [ks]
                          (let [futs (for [k ks]
                                       (let [to-path (Files/createTempFile "core2" "" (make-array FileAttribute 0))]
                                         (-> (.getObject object-store k to-path)
                                             (util/then-compose
                                               (fn [to-path]
                                                 (if-let [chunk-file (->> (c2/block-stream to-path allocator)
                                                                          (reduce (completing
                                                                                   (fn [_ ^VectorSchemaRoot metadata-root]
                                                                                     (when (pos? (compare (str (meta/max-value metadata-root "name"))
                                                                                                          "Ivan"))
                                                                                       (meta/chunk-file metadata-root "name"))))
                                                                                  nil))]
                                                   (.getObject object-store chunk-file to-path)
                                                   (CompletableFuture/completedFuture nil))))
                                             (util/then-apply
                                               (fn [to-path]
                                                 (when to-path
                                                   (->> (c2/block-stream to-path allocator)
                                                        (into [] (mapcat (fn [^VectorSchemaRoot chunk-root]
                                                                           (let [name-vec (.getVector chunk-root "name")
                                                                                 ^BigIntVector row-id-vec (.getVector chunk-root "_row-id")]
                                                                             (vec (for [idx (range (.getRowCount chunk-root))
                                                                                        :let [name (str (.getObject name-vec idx))]
                                                                                        :when (pos? (compare name "Ivan"))]
                                                                                    (MapEntry/create (.get row-id-vec idx) name))))))))))))))]
                            (-> (CompletableFuture/allOf (into-array CompletableFuture futs))
                                (util/then-apply (fn [_]
                                                   (into {} (mapcat deref) futs))))))))))))))
