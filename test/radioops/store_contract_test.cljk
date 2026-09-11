(ns radioops.store-contract-test
  "Contract tests for `radioops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [radioops.store :as store]))

(deftest mem-store-station-lookup
  (testing "MemStore can store and retrieve stations by ID (string keys)"
    (let [stations {"s1" {:station-id "s1" :name "Alpha Radio" :registered? true :verified? true}}
          s (store/mem-store stations)]
      (is (some? (store/station s "s1")))
      (is (nil? (store/station s "s99"))))))

(deftest mem-store-all-stations
  (testing "MemStore returns all stations in sorted order"
    (let [stations {"s2" {:station-id "s2" :name "Bravo Broadcasting"}
                    "s1" {:station-id "s1" :name "Alpha Radio"}
                    "s3" {:station-id "s3" :name "Charlie FM"}}
          s (store/mem-store stations)
          all-s (store/all-stations s)]
      (is (= 3 (count all-s)))
      (is (= "s1" (:station-id (first all-s))))
      (is (= "s3" (:station-id (last all-s)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-broadcast-record :station-id "s1" :value {:segment "morning show"}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-stations
  (testing "MemStore with-stations replaces the station directory"
    (let [s (store/mem-store {})
          new-stations {"s1" {:station-id "s1" :name "Alpha Radio"}}]
      (is (= 0 (count (store/all-stations s))))
      (store/with-stations s new-stations)
      (is (= 1 (count (store/all-stations s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo stations"
    (let [s (store/seed-db)]
      (is (> (count (store/all-stations s)) 0))
      (is (some? (store/station s "station-1")))
      (is (some? (store/station s "station-2")))
      (is (some? (store/station s "station-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for station-id"
    (let [demo (store/demo-data)
          stations (:stations demo)]
      (doseq [[k v] stations]
        (is (string? k) "keys must be strings")
        (is (string? (:station-id v)) "station-id must be string")
        (is (= k (:station-id v)) "key must match station-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
