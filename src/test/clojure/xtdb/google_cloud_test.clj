(ns xtdb.google-cloud-test
  (:require [clojure.java.shell :as sh]
            [clojure.test :as t]
            [clojure.tools.logging :as log]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.api :as xt]
            [xtdb.buffer-pool-test :as bp-test]
            [xtdb.datasets.tpch :as tpch]
            [xtdb.google-cloud :as google-cloud]
            [xtdb.node :as xtn]
            [xtdb.object-store-test :as os-test]
            [xtdb.test-util :as tu]
            [xtdb.util :as util])
  (:import (com.google.cloud.storage Bucket Bucket$BucketSourceOption Storage Storage$BucketGetOption StorageException StorageOptions StorageOptions$Builder)
           (java.io Closeable)
           (java.time Duration)
           (xtdb.api.storage GoogleCloudStorage ObjectStore)
           (xtdb.buffer_pool RemoteBufferPool)))

;; Ensure you are authenticated with google cloud before running these tests - there are two options to do this:
;; - gcloud auth Login onto an account which belongs to the `xtdb-devs@gmail.com` group
;; - assume the role of the service account created for these tests (this allows us to verify the role works as intended)
;; ---> gcloud auth activate-service-account --key-file=<KEYFILE>
;; ---> Where <KEYFILE> is the filepath to a key file for 'xtdb-test-service-account' 

(def project-id "xtdb-scratch")
(def test-bucket "gcp-test-xtdb-object-store")

(defn config-present? []
  (try
    (let [^Storage storage (-> (StorageOptions/newBuilder)
                               ^StorageOptions$Builder (.setProjectId project-id)
                               ^StorageOptions (.build)
                               (.getService))
          ^Bucket bucket (.get storage ^String test-bucket ^"[Lcom.google.cloud.storage.Storage$BucketGetOption;" (into-array Storage$BucketGetOption []))]
      (.exists bucket (into-array Bucket$BucketSourceOption [])))
    (catch StorageException e
      (when-not (= 401 (.getCode e))
        (throw e)))))

(defn cli-available? []
  (= 0 (:exit (sh/sh "gcloud" "--help"))))

(defn run-if-auth-available [f]
  (cond
    (config-present?) (f)

    (not (cli-available?))
    (log/warn "gcloud cli is unavailable, and application default credentials are not set")

    :else
    (log/warn "gcloud cli appears to be available but you are not authenticated, run `gcloud auth application-default login` before running the tests")))

(t/use-fixtures :once run-if-auth-available)

(defn object-store ^Closeable [prefix]
  (let [factory (-> (GoogleCloudStorage/googleCloudStorage project-id test-bucket)
                    (.prefix (util/->path (str prefix))))]
    (google-cloud/open-object-store factory)))

(t/deftest ^:google-cloud put-delete-test
  (let [os (object-store (random-uuid))]
    
    (os-test/test-put-delete os)))

(t/deftest ^:google-cloud range-test
  (let [os (object-store (random-uuid))]
    (os-test/test-range os)))

(defn start-kafka-node [local-disk-cache prefix]
  (xtn/start-node
   {:server {:port 0}
    :storage [:remote
              {:object-store [:google-cloud {:project-id project-id
                                             :bucket test-bucket
                                             :prefix (str "xtdb.google-cloud-test." prefix)}]
               :local-disk-cache local-disk-cache}]
    :log [:kafka {:tx-topic (str "xtdb.kafka-test.tx-" prefix)
                  :files-topic (str "xtdb.kafka-test.files-" prefix)
                  :bootstrap-servers "localhost:9092"}]}))

(t/deftest ^:google-cloud list-test
  (util/with-tmp-dirs #{local-disk-cache}
    (util/with-open [node (start-kafka-node local-disk-cache (random-uuid))]
      (let [buffer-pool (bp-test/fetch-buffer-pool-from-node node)]
        (bp-test/test-list-objects buffer-pool)))))

(t/deftest ^:google-cloud list-test-with-prior-objects
  (util/with-tmp-dirs #{local-disk-cache}
    (let [prefix (random-uuid)]
      (util/with-open [node (start-kafka-node local-disk-cache prefix)]
        (let [^RemoteBufferPool buffer-pool (bp-test/fetch-buffer-pool-from-node node)]
          (bp-test/put-edn buffer-pool (util/->path "alice") :alice)
          (bp-test/put-edn buffer-pool (util/->path "alan") :alan)
          (Thread/sleep 1000)
          (t/is (= (mapv util/->path ["alan" "alice"]) (.listAllObjects buffer-pool)))))

      (util/with-open [node (start-kafka-node local-disk-cache prefix)]
        (let [^RemoteBufferPool buffer-pool (bp-test/fetch-buffer-pool-from-node node)]
          (t/testing "prior objects will still be there, should be available on a list request"
            (t/is (= (mapv util/->path ["alan" "alice"]) (.listAllObjects buffer-pool))))

          (t/testing "should be able to add new objects and have that reflected in list objects output"
            (bp-test/put-edn buffer-pool (util/->path "alex") :alex)
            (Thread/sleep 1000)
            (t/is (= (mapv util/->path ["alan" "alex" "alice"]) (.listAllObjects buffer-pool)))))))))

(t/deftest ^:google-cloud multiple-node-list-test
  (util/with-tmp-dirs #{local-disk-cache}
    (let [prefix (random-uuid)]
      (util/with-open [node-1 (start-kafka-node local-disk-cache prefix)
                       node-2 (start-kafka-node local-disk-cache prefix)]
        (let [^RemoteBufferPool buffer-pool-1 (bp-test/fetch-buffer-pool-from-node node-1)
              ^RemoteBufferPool buffer-pool-2 (bp-test/fetch-buffer-pool-from-node node-2)]
          (bp-test/put-edn buffer-pool-1 (util/->path "alice") :alice)
          (bp-test/put-edn buffer-pool-2 (util/->path "alan") :alan)
          (Thread/sleep 1000)
          (t/is (= (mapv util/->path ["alan" "alice"])
                   (.listAllObjects buffer-pool-1)))

          (t/is (= (mapv util/->path ["alan" "alice"])
                   (.listAllObjects buffer-pool-2))))))))

(t/deftest ^:google-cloud put-object-twice-shouldnt-throw
  (util/with-tmp-dirs #{local-disk-cache}
    (let [prefix (random-uuid)]
      (util/with-open [node-1 (start-kafka-node local-disk-cache prefix)
                       node-2 (start-kafka-node local-disk-cache prefix)]
        (let [^RemoteBufferPool buffer-pool-1 (bp-test/fetch-buffer-pool-from-node node-1)
              ^RemoteBufferPool buffer-pool-2 (bp-test/fetch-buffer-pool-from-node node-2)]
          (bp-test/put-edn buffer-pool-1 (util/->path "alice") :alice)
          (bp-test/put-edn buffer-pool-2 (util/->path "alice") :alice)
          (Thread/sleep 1000)
          (t/is (= (mapv util/->path ["alice"])
                   (.listAllObjects buffer-pool-1)))
  
          (t/is (= (mapv util/->path ["alice"])
                   (.listAllObjects buffer-pool-2))))))))

(t/deftest ^:google-cloud node-level-test
  (util/with-tmp-dirs #{local-disk-cache}
    (util/with-open [node (start-kafka-node local-disk-cache (random-uuid))]
      (let [^RemoteBufferPool buffer-pool (bp-test/fetch-buffer-pool-from-node node)]
        ;; Submit some documents to the node
        (t/is (= true
                 (:committed? (xt/execute-tx node [[:put-docs :bar {:xt/id "bar1"}]
                                                   [:put-docs :bar {:xt/id "bar2"}]
                                                   [:put-docs :bar {:xt/id "bar3"}]]))))

        ;; Ensure finish-chunk! works
        (t/is (nil? (tu/finish-chunk! node)))

        ;; Ensure can query back out results
        (t/is (= [{:e "bar2"} {:e "bar1"} {:e "bar3"}]
                 (xt/q node '(from :bar [{:xt/id e}]))))

        ;; Ensure some files written to buffer-pool
        (t/is (seq (.listAllObjects buffer-pool)))))))

;; Using large enough TPCH ensures multiparts get properly used within the bufferpool
(t/deftest ^:google-cloud tpch-test-node
  (util/with-tmp-dirs #{local-disk-cache}
    (util/with-open [node (start-kafka-node local-disk-cache (random-uuid))]
      ;; Submit tpch docs
      (-> (tpch/submit-docs! node 0.05)
          (tu/then-await-tx node (Duration/ofHours 1)))

      ;; Ensure finish-chunk! works
      (t/is (nil? (tu/finish-chunk! node)))

      ;; Ensure some files written to buffer-pool 
      (let [^RemoteBufferPool buffer-pool (bp-test/fetch-buffer-pool-from-node node)]
        (t/is (seq (.listAllObjects buffer-pool)))))))
