(ns core2.temporal.kd-tree-test
  (:require [clojure.test :as t]
            [core2.util :as util]
            [core2.temporal :as temporal]
            [core2.temporal.kd-tree :as kd])
  (:import [java.util Collection Date HashMap List]
           [java.util.function Predicate ToLongFunction]
           [org.apache.arrow.memory RootAllocator]
           [org.apache.arrow.vector VectorSchemaRoot]
           [org.apache.arrow.vector.ipc.message ArrowRecordBatch]
           [org.apache.arrow.vector.complex FixedSizeListVector]
           [core2.temporal.kd_tree MergedKdTree Node]))

;; NOTE: "Developing Time-Oriented Database Applications in SQL",
;; chapter 10 "Bitemporal Tables".

;; Uses transaction time splitting, so some rectangles differ, but
;; areas covered are the same. Could or maybe should coalesce.

(defn- ->row-map [^List point]
  (zipmap [:id :row-id :valid-time-start :valid-time-end :tx-time-start :tx-time-end]
          [(.get point temporal/id-idx)
           (.get point temporal/row-id-idx)
           (Date. ^long (.get point temporal/valid-time-start-idx))
           (Date. ^long (.get point temporal/valid-time-end-idx))
           (Date. ^long (.get point temporal/tx-time-start-idx))
           (Date. ^long (.get point temporal/tx-time-end-idx))]))

(defn- temporal-rows [kd-tree row-id->row]
  (vec (for [{:keys [row-id] :as row} (->> (map ->row-map (kd/kd-tree->seq kd-tree))
                                           (sort-by (juxt :tx-time-start :row-id)))]
         (merge row (get row-id->row row-id)))))

(t/deftest bitemporal-tx-time-split-test
  (let [kd-tree nil
        id->internal-id-map (doto (HashMap.)
                              (.put 7797 7797))
        id->internal-id (reify ToLongFunction
                          (applyAsLong [_ x]
                            (.get id->internal-id-map x)))
        row-id->row (HashMap.)]
    ;; Current Insert
    ;; Eva Nielsen buys the flat at Skovvej 30 in Aalborg on January 10,
    ;; 1998.
    (with-open [allocator (RootAllocator.)
                ^Node kd-tree (temporal/insert-coordinates kd-tree
                                                           allocator
                                                           id->internal-id
                                                           (temporal/->coordinates {:id 7797
                                                                                    :row-id 1
                                                                                    :tx-time-start #inst "1998-01-10"}))]
      (.put row-id->row 1 {:customer-number 145})
      (t/is (= [{:id 7797,
                 :customer-number 145,
                 :row-id 1,
                 :valid-time-start #inst "1998-01-10T00:00:00.000-00:00",
                 :valid-time-end #inst "9999-12-31T23:59:59.999-00:00",
                 :tx-time-start #inst "1998-01-10T00:00:00.000-00:00",
                 :tx-time-end #inst "9999-12-31T23:59:59.999-00:00"}]
               (temporal-rows kd-tree row-id->row)))

      ;; Current Update
      ;; Peter Olsen buys the flat on January 15, 1998.
      (let [kd-tree (temporal/insert-coordinates kd-tree
                                                 allocator
                                                 id->internal-id
                                                 (temporal/->coordinates {:id 7797
                                                                          :row-id 2
                                                                          :tx-time-start #inst "1998-01-15"}))]
        (.put row-id->row 2 {:customer-number 827})
        (t/is (= [{:id 7797,
                   :row-id 1,
                   :customer-number 145,
                   :valid-time-start #inst "1998-01-10T00:00:00.000-00:00",
                   :valid-time-end #inst "9999-12-31T23:59:59.999-00:00",
                   :tx-time-start #inst "1998-01-10T00:00:00.000-00:00",
                   :tx-time-end #inst "1998-01-15T00:00:00.000-00:00"}
                  {:id 7797,
                   :customer-number 145,
                   :row-id 1,
                   :valid-time-start #inst "1998-01-10T00:00:00.000-00:00",
                   :valid-time-end #inst "1998-01-15T00:00:00.000-00:00",
                   :tx-time-start #inst "1998-01-15T00:00:00.000-00:00",
                   :tx-time-end #inst "9999-12-31T23:59:59.999-00:00"}
                  {:id 7797,
                   :row-id 2,
                   :customer-number 827,
                   :valid-time-start #inst "1998-01-15T00:00:00.000-00:00",
                   :valid-time-end #inst "9999-12-31T23:59:59.999-00:00",
                   :tx-time-start #inst "1998-01-15T00:00:00.000-00:00",
                   :tx-time-end #inst "9999-12-31T23:59:59.999-00:00"}]
                 (temporal-rows kd-tree row-id->row)))

        ;; Current Delete
        ;; Peter Olsen sells the flat on January 20, 1998.
        (let [kd-tree (temporal/insert-coordinates kd-tree
                                                   allocator
                                                   id->internal-id
                                                   (temporal/->coordinates {:id 7797
                                                                            :row-id 3
                                                                            :tx-time-start #inst "1998-01-20"
                                                                            :tombstone? true}))]
          (.put row-id->row 3 {:customer-number 827})
          (t/is (= [{:id 7797,
                     :customer-number 145,
                     :row-id 1,
                     :valid-time-start #inst "1998-01-10T00:00:00.000-00:00",
                     :valid-time-end #inst "9999-12-31T23:59:59.999-00:00",
                     :tx-time-start #inst "1998-01-10T00:00:00.000-00:00",
                     :tx-time-end #inst "1998-01-15T00:00:00.000-00:00"}
                    {:id 7797,
                     :customer-number 145,
                     :row-id 1,
                     :valid-time-start #inst "1998-01-10T00:00:00.000-00:00",
                     :valid-time-end #inst "1998-01-15T00:00:00.000-00:00",
                     :tx-time-start #inst "1998-01-15T00:00:00.000-00:00",
                     :tx-time-end #inst "9999-12-31T23:59:59.999-00:00"}
                    {:id 7797,
                     :customer-number 827,
                     :row-id 2,
                     :valid-time-start #inst "1998-01-15T00:00:00.000-00:00",
                     :valid-time-end #inst "9999-12-31T23:59:59.999-00:00",
                     :tx-time-start #inst "1998-01-15T00:00:00.000-00:00",
                     :tx-time-end #inst "1998-01-20T00:00:00.000-00:00"}
                    {:id 7797,
                     :customer-number 827,
                     :row-id 2,
                     :valid-time-start #inst "1998-01-15T00:00:00.000-00:00",
                     :valid-time-end #inst "1998-01-20T00:00:00.000-00:00",
                     :tx-time-start #inst "1998-01-20T00:00:00.000-00:00",
                     :tx-time-end #inst "9999-12-31T23:59:59.999-00:00"}]
                   (temporal-rows kd-tree row-id->row)))

          ;; Sequenced Insert
          ;; Eva actually purchased the flat on January 3, performed on January 23.
          (let [kd-tree (temporal/insert-coordinates kd-tree
                                                     allocator
                                                     id->internal-id
                                                     (temporal/->coordinates {:id 7797
                                                                              :row-id 4
                                                                              :tx-time-start #inst "1998-01-23"
                                                                              :valid-time-start #inst "1998-01-03"
                                                                              :valid-time-end #inst "1998-01-15"}))]
            (.put row-id->row 4 {:customer-number 145})
            (t/is (= [{:id 7797,
                       :customer-number 145,
                       :row-id 1,
                       :valid-time-start #inst "1998-01-10T00:00:00.000-00:00",
                       :valid-time-end #inst "9999-12-31T23:59:59.999-00:00",
                       :tx-time-start #inst "1998-01-10T00:00:00.000-00:00",
                       :tx-time-end #inst "1998-01-15T00:00:00.000-00:00"}
                      {:id 7797,
                       :customer-number 145,
                       :row-id 1,
                       :valid-time-start #inst "1998-01-10T00:00:00.000-00:00",
                       :valid-time-end #inst "1998-01-15T00:00:00.000-00:00",
                       :tx-time-start #inst "1998-01-15T00:00:00.000-00:00",
                       :tx-time-end #inst "1998-01-23T00:00:00.000-00:00"}
                      {:id 7797,
                       :customer-number 827,
                       :row-id 2,
                       :valid-time-start #inst "1998-01-15T00:00:00.000-00:00",
                       :valid-time-end #inst "9999-12-31T23:59:59.999-00:00",
                       :tx-time-start #inst "1998-01-15T00:00:00.000-00:00",
                       :tx-time-end #inst "1998-01-20T00:00:00.000-00:00"}
                      {:id 7797,
                       :row-id 2,
                       :customer-number 827,
                       :valid-time-start #inst "1998-01-15T00:00:00.000-00:00",
                       :valid-time-end #inst "1998-01-20T00:00:00.000-00:00",
                       :tx-time-start #inst "1998-01-20T00:00:00.000-00:00",
                       :tx-time-end #inst "9999-12-31T23:59:59.999-00:00"}
                      {:id 7797,
                       :customer-number 145,
                       :row-id 4,
                       :valid-time-start #inst "1998-01-03T00:00:00.000-00:00",
                       :valid-time-end #inst "1998-01-15T00:00:00.000-00:00",
                       :tx-time-start #inst "1998-01-23T00:00:00.000-00:00",
                       :tx-time-end #inst "9999-12-31T23:59:59.999-00:00"}]
                     (temporal-rows kd-tree row-id->row)))

            ;; NOTE: rows differs from book, but covered area is the same.
            ;; Sequenced Delete
            ;; A sequenced deletion performed on January 26: Eva actually purchased the flat on January 5.
            (let [kd-tree (temporal/insert-coordinates kd-tree
                                                       allocator
                                                       id->internal-id
                                                       (temporal/->coordinates {:id 7797
                                                                                :row-id 5
                                                                                :tx-time-start #inst "1998-01-26"
                                                                                :valid-time-start #inst "1998-01-02"
                                                                                :valid-time-end #inst "1998-01-05"
                                                                                :tombstone? true}))]
              (.put row-id->row 5 {:customer-number 145})
              (t/is (= [{:id 7797,
                         :customer-number 145,
                         :row-id 1,
                         :valid-time-start #inst "1998-01-10T00:00:00.000-00:00",
                         :valid-time-end #inst "9999-12-31T23:59:59.999-00:00",
                         :tx-time-start #inst "1998-01-10T00:00:00.000-00:00",
                         :tx-time-end #inst "1998-01-15T00:00:00.000-00:00"}
                        {:id 7797,
                         :customer-number 145,
                         :row-id 1,
                         :valid-time-start #inst "1998-01-10T00:00:00.000-00:00",
                         :valid-time-end #inst "1998-01-15T00:00:00.000-00:00",
                         :tx-time-start #inst "1998-01-15T00:00:00.000-00:00",
                         :tx-time-end #inst "1998-01-23T00:00:00.000-00:00"}
                        {:id 7797,
                         :customer-number 827,
                         :row-id 2,
                         :valid-time-start #inst "1998-01-15T00:00:00.000-00:00",
                         :valid-time-end #inst "9999-12-31T23:59:59.999-00:00",
                         :tx-time-start #inst "1998-01-15T00:00:00.000-00:00",
                         :tx-time-end #inst "1998-01-20T00:00:00.000-00:00"}
                        {:id 7797,
                         :customer-number 827,
                         :row-id 2,
                         :valid-time-start #inst "1998-01-15T00:00:00.000-00:00",
                         :valid-time-end #inst "1998-01-20T00:00:00.000-00:00",
                         :tx-time-start #inst "1998-01-20T00:00:00.000-00:00",
                         :tx-time-end #inst "9999-12-31T23:59:59.999-00:00"}
                        {:id 7797,
                         :customer-number 145,
                         :row-id 4,
                         :valid-time-start #inst "1998-01-03T00:00:00.000-00:00",
                         :valid-time-end #inst "1998-01-15T00:00:00.000-00:00",
                         :tx-time-start #inst "1998-01-23T00:00:00.000-00:00",
                         :tx-time-end #inst "1998-01-26T00:00:00.000-00:00"}
                        {:id 7797,
                         :customer-number 145,
                         :row-id 4,
                         :valid-time-start #inst "1998-01-05T00:00:00.000-00:00",
                         :valid-time-end #inst "1998-01-15T00:00:00.000-00:00",
                         :tx-time-start #inst "1998-01-26T00:00:00.000-00:00",
                         :tx-time-end #inst "9999-12-31T23:59:59.999-00:00"}]
                       (temporal-rows kd-tree row-id->row)))

              ;; NOTE: rows differs from book, but covered area is the same.
              ;; Sequenced Update
              ;; A sequenced update performed on January 28: Peter actually purchased the flat on January 12.
              (let [kd-tree (temporal/insert-coordinates kd-tree
                                                         allocator
                                                         id->internal-id
                                                         (temporal/->coordinates {:id 7797
                                                                                  :row-id 6
                                                                                  :tx-time-start #inst "1998-01-28"
                                                                                  :valid-time-start #inst "1998-01-12"
                                                                                  :valid-time-end #inst "1998-01-15"}))]
                (.put row-id->row 6 {:customer-number 827})
                (t/is (= [{:id 7797,
                           :customer-number 145,
                           :row-id 1,
                           :valid-time-start #inst "1998-01-10T00:00:00.000-00:00",
                           :valid-time-end #inst "9999-12-31T23:59:59.999-00:00",
                           :tx-time-start #inst "1998-01-10T00:00:00.000-00:00",
                           :tx-time-end #inst "1998-01-15T00:00:00.000-00:00"}
                          {:id 7797,
                           :customer-number 145,
                           :row-id 1,
                           :valid-time-start #inst "1998-01-10T00:00:00.000-00:00",
                           :valid-time-end #inst "1998-01-15T00:00:00.000-00:00",
                           :tx-time-start #inst "1998-01-15T00:00:00.000-00:00",
                           :tx-time-end #inst "1998-01-23T00:00:00.000-00:00"}
                          {:id 7797,
                           :customer-number 827,
                           :row-id 2,
                           :valid-time-start #inst "1998-01-15T00:00:00.000-00:00",
                           :valid-time-end #inst "9999-12-31T23:59:59.999-00:00",
                           :tx-time-start #inst "1998-01-15T00:00:00.000-00:00",
                           :tx-time-end #inst "1998-01-20T00:00:00.000-00:00"}
                          {:id 7797,
                           :customer-number 827,
                           :row-id 2,
                           :valid-time-start #inst "1998-01-15T00:00:00.000-00:00",
                           :valid-time-end #inst "1998-01-20T00:00:00.000-00:00",
                           :tx-time-start #inst "1998-01-20T00:00:00.000-00:00",
                           :tx-time-end #inst "9999-12-31T23:59:59.999-00:00"}
                          {:id 7797,
                           :customer-number 145,
                           :row-id 4,
                           :valid-time-start #inst "1998-01-03T00:00:00.000-00:00",
                           :valid-time-end #inst "1998-01-15T00:00:00.000-00:00",
                           :tx-time-start #inst "1998-01-23T00:00:00.000-00:00",
                           :tx-time-end #inst "1998-01-26T00:00:00.000-00:00"}
                          {:id 7797,
                           :customer-number 145,
                           :row-id 4,
                           :valid-time-start #inst "1998-01-05T00:00:00.000-00:00",
                           :valid-time-end #inst "1998-01-15T00:00:00.000-00:00",
                           :tx-time-start #inst "1998-01-26T00:00:00.000-00:00",
                           :tx-time-end #inst "1998-01-28T00:00:00.000-00:00"}
                          {:id 7797,
                           :customer-number 145,
                           :row-id 4,
                           :valid-time-start #inst "1998-01-05T00:00:00.000-00:00",
                           :valid-time-end #inst "1998-01-12T00:00:00.000-00:00",
                           :tx-time-start #inst "1998-01-28T00:00:00.000-00:00",
                           :tx-time-end #inst "9999-12-31T23:59:59.999-00:00"}
                          {:id 7797,
                           :customer-number 827,
                           :row-id 6,
                           :valid-time-start #inst "1998-01-12T00:00:00.000-00:00",
                           :valid-time-end #inst "1998-01-15T00:00:00.000-00:00",
                           :tx-time-start #inst "1998-01-28T00:00:00.000-00:00",
                           :tx-time-end #inst "9999-12-31T23:59:59.999-00:00"}]
                         (temporal-rows kd-tree row-id->row))

                      (t/testing "rebuilding tree results in tree with same points"
                        (let [points (mapv vec (kd/kd-tree->seq kd-tree))]
                          (with-open [new-tree (kd/->node-kd-tree allocator (shuffle points))
                                      ^Node rebuilt-tree (reduce (fn [acc point]
                                                                   (kd/kd-tree-insert acc allocator point)) nil (shuffle points))]
                            (t/is (= (sort points)
                                     (sort (mapv vec (kd/kd-tree->seq new-tree)))))
                            (t/is (= (sort points)
                                     (sort (mapv vec (kd/kd-tree->seq rebuilt-tree)))))))))))))))))

(t/deftest kd-tree-sanity-check
  (let [points [[7 2] [5 4] [9 6] [4 7] [8 1] [2 3]]
        test-dir (util/->path "target/kd-tree-sanity-check")]
    (util/delete-dir test-dir)
    (with-open [allocator (RootAllocator.)
                kd-tree (kd/->node-kd-tree allocator points)
                ^Node insert-kd-tree (reduce
                                      (fn [acc point]
                                        (kd/kd-tree-insert acc allocator point))
                                      nil
                                      points)
                ^VectorSchemaRoot column-kd-tree (kd/->column-kd-tree allocator kd-tree 2)
                ^VectorSchemaRoot disk-kd-tree-from-points (->> (kd/->disk-kd-tree allocator (.resolve test-dir "kd_tree_1.arrow") points 2)
                                                                (kd/->mmap-kd-tree allocator))
                ^VectorSchemaRoot disk-kd-tree-from-tree (->> (kd/->disk-kd-tree allocator (.resolve test-dir "kd_tree_2.arrow") kd-tree 2)
                                                              (kd/->mmap-kd-tree allocator))]
      (t/is (= [[7 2] [5 4] [2 3] [8 1]]

               (-> kd-tree
                   (kd/kd-tree-range-search [0 0] [8 4])
                   (->> (kd/kd-tree->seq kd-tree)))

               (-> insert-kd-tree
                   (kd/kd-tree-range-search [0 0] [8 4])
                   (->> (kd/kd-tree->seq insert-kd-tree)))

               (-> column-kd-tree
                   (kd/kd-tree-range-search [0 0] [8 4])
                   (->> (kd/kd-tree->seq column-kd-tree)))

               (-> disk-kd-tree-from-points
                   (kd/kd-tree-range-search [0 0] [8 4])
                   (->> (kd/kd-tree->seq disk-kd-tree-from-points)))

               (-> disk-kd-tree-from-tree
                   (kd/kd-tree-range-search [0 0] [8 4])
                   (->> (kd/kd-tree->seq disk-kd-tree-from-tree))))
            "wikipedia-test")

      (t/testing "seq"
        (t/is (= (kd/kd-tree->seq kd-tree)
                 (kd/kd-tree->seq column-kd-tree)
                 (kd/kd-tree->seq disk-kd-tree-from-points)
                 (kd/kd-tree->seq disk-kd-tree-from-tree))))

      (t/testing "depth"
        (t/is (= 3
                 (kd/kd-tree-depth kd-tree)
                 (kd/kd-tree-depth column-kd-tree)
                 (kd/kd-tree-depth disk-kd-tree-from-points)
                 (kd/kd-tree-depth disk-kd-tree-from-tree))))

      (t/testing "size"
        (t/is (= (count points)
                 (kd/kd-tree-size kd-tree)
                 (kd/kd-tree-size insert-kd-tree)
                 (kd/kd-tree-size column-kd-tree)
                 (kd/kd-tree-size disk-kd-tree-from-points)
                 (kd/kd-tree-size disk-kd-tree-from-tree))))

      (t/testing "empty tree"
        (with-open [^Node kd-tree (kd/->node-kd-tree allocator [[1 2]])]
          (t/is (= [[1 2]] (kd/kd-tree->seq kd-tree))))

        (t/is (nil? (kd/->node-kd-tree allocator [])))
        (t/is (zero? (kd/kd-tree-size (kd/->node-kd-tree allocator []))))

        (with-open [^Node kd-tree (kd/kd-tree-insert nil allocator [1 2])]
          (t/is (= [[1 2]] (kd/kd-tree->seq kd-tree))))

        (with-open [^Node kd-tree (kd/kd-tree-delete nil allocator [1 2])]
          (t/is (empty? (kd/kd-tree->seq kd-tree)))
          (t/is (zero? (kd/kd-tree-size kd-tree)))))

      (t/testing "arrow"
        (t/is (= (.contentToTSVString column-kd-tree)
                 (.contentToTSVString disk-kd-tree-from-points)
                 (.contentToTSVString disk-kd-tree-from-tree))))

      (t/testing "merge"
        (with-open [new-tree-with-tombstone (kd/->node-kd-tree allocator [[4 7] [8 1] [2 3]])]
          (let [node-to-delete [2 1]
                ^Node new-tree-with-tombstone (kd/kd-tree-delete new-tree-with-tombstone allocator node-to-delete)]
            (t/is (= 3 (kd/kd-tree-size new-tree-with-tombstone)))
            (t/is (= 4 (kd/kd-tree-value-count new-tree-with-tombstone)))
            (with-open [old-tree-with-node-to-be-deleted (kd/->node-kd-tree allocator [[7 2] [5 4] [9 6] node-to-delete])
                        ^VectorSchemaRoot column-kd-tree (kd/->column-kd-tree allocator
                                                                              new-tree-with-tombstone
                                                                              2)
                        ^Node merged-tree (kd/merge-kd-trees allocator old-tree-with-node-to-be-deleted column-kd-tree)
                        rebuilt-tree (kd/rebuild-node-kd-tree allocator merged-tree)]
              (t/is (= 4 (kd/kd-tree-size old-tree-with-node-to-be-deleted)))

              (t/is (= (kd/kd-tree->seq kd-tree)
                       (kd/kd-tree->seq merged-tree)
                       (kd/kd-tree->seq rebuilt-tree)))

              (t/is (= (kd/kd-tree-size kd-tree)
                       (kd/kd-tree-size merged-tree)
                       (kd/kd-tree-size rebuilt-tree)))

              (t/testing "merged tree"
                (with-open [dynamic-tree (kd/->node-kd-tree allocator [[4 7] [8 1] [2 3]])
                            old-tree (kd/->node-kd-tree allocator [[7 2] [5 4] [9 6] node-to-delete])
                            ^VectorSchemaRoot static-tree (kd/->column-kd-tree allocator old-tree 2)
                            merged-tree (kd/->merged-kd-tree static-tree dynamic-tree)]
                  (t/is (= 7 (kd/kd-tree-size merged-tree)))
                  (t/is (= 3 (kd/kd-tree-depth merged-tree)))

                  (let [unknown-node [0 0]
                        expected-nodes (set (map vec (kd/kd-tree->seq rebuilt-tree)))
                        merged-tree (kd/kd-tree-delete merged-tree allocator node-to-delete)]
                    (t/is (= 6 (kd/kd-tree-size merged-tree)))
                    (t/is (= 6 (kd/kd-tree-size (kd/kd-tree-delete merged-tree allocator node-to-delete))))
                    (t/is (= 6 (kd/kd-tree-size (kd/kd-tree-delete merged-tree allocator unknown-node))))
                    (t/is (= expected-nodes (set (map vec (kd/kd-tree->seq merged-tree)))))

                    (let [node-to-insert [10 10]
                          merged-tree (kd/kd-tree-insert merged-tree allocator node-to-insert)]
                      (t/is (= 7 (kd/kd-tree-size merged-tree)))
                      (t/is (= (conj expected-nodes node-to-insert)
                               (set (map vec (kd/kd-tree->seq merged-tree)))))

                      (let [merged-tree (kd/kd-tree-delete merged-tree allocator node-to-insert)]
                        (t/is (= 6 (kd/kd-tree-size merged-tree)))
                        (t/is (= expected-nodes (set (map vec (kd/kd-tree->seq merged-tree))))))))

                  (t/testing "empty dynamic tree"
                    (let [node-to-insert [10 10]]
                      (with-open [merged-tree (kd/->merged-kd-tree static-tree)]
                        (t/is (= 4 (kd/kd-tree-size merged-tree)))
                        (t/is (empty? (kd/kd-tree->seq merged-tree (kd/kd-tree-range-search merged-tree node-to-insert node-to-insert))))
                        (with-open [^MergedKdTree merged-tree (kd/kd-tree-insert merged-tree allocator node-to-insert)]
                          (t/is (= 5 (kd/kd-tree-size merged-tree)))
                          (t/is (= 4 (kd/kd-tree-size static-tree)))
                          (t/is (= [node-to-insert]
                                   (kd/kd-tree->seq merged-tree (kd/kd-tree-range-search merged-tree node-to-insert node-to-insert)))))))))))))))))

(t/deftest empty-record-batch
  (with-open [allocator (RootAllocator.)
              ^ArrowRecordBatch record-batch (@#'kd/->empty-record-batch allocator (@#'kd/->column-kd-tree-schema 3) 10)]

    (t/is (= [{:length 10, :nullCount 0}
              {:length 10, :nullCount 0}
              {:length 10, :nullCount 0}
              {:length 10, :nullCount 0}
              {:length 30, :nullCount 0}]
             (mapv (comp #(dissoc % :class) bean)
                   (.getNodes record-batch))))

    (t/is (= [{:offset 0,
               :size 2}
              {:offset 8,
               :size 10}
              {:offset 24,
               :size 2}
              {:offset 32,
               :size 80}
              {:offset 112,
               :size 2}
              {:offset 120,
               :size 40}
              {:offset 160,
               :size 2}
              {:offset 168,
               :size 4}
              {:offset 176,
               :size 240}]
             (mapv (comp #(dissoc % :class) bean)
                   (.getBuffersLayout record-batch))))))
