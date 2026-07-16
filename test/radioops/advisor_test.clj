(ns radioops.advisor-test
  "Unit tests of `radioops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [radioops.advisor :as adv]
            [radioops.governor :as gov]
            [radioops.store :as store]))

(def db (store/seed-db))

(deftest propose-broadcast-record-shape
  (testing "broadcast-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-broadcast-record
                           :station-id "station-1"
                           :patch {:segment "morning show" :playlist-count 14}})]
      (is (= :log-broadcast-record (:op p)))
      (is (= "station-1" (:station-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :station-id)))))

(deftest propose-broadcast-schedule-shape
  (testing "broadcast-operation scheduling proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-broadcast-operation
                           :station-id "station-2"
                           :patch {:segment "evening news" :date "2026-08-01"}})]
      (is (= :schedule-broadcast-operation (:op p)))
      (is (= "station-2" (:station-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-equipment-maintenance-shape
  (testing "equipment-maintenance coordination proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-equipment-maintenance
                           :station-id "station-1"
                           :patch {:equipment "transmitter" :window "2026-08-03T02:00"}})]
      (is (= :coordinate-equipment-maintenance (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-content-concern-shape
  (testing "content-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-content-concern
                           :station-id "station-1"
                           :patch {:concern "possible EAS test-tone mistimed during a live segment"}})]
      (is (= :flag-content-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-broadcast-record :schedule-broadcast-operation
                :coordinate-equipment-maintenance :flag-content-concern]]
      (let [p (adv/infer db {:op op :station-id "station-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-broadcast-record :schedule-broadcast-operation
                :coordinate-equipment-maintenance :flag-content-concern]]
      (let [p (adv/infer db {:op op :station-id "station-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "every op's default (clean, in-scope) mock-advisor proposal must clear the governor's scope-exclusion check on its own generated text -- a proposal must never accidentally describe itself using the very terms that would make it permanently blocked"
    (doseq [op [:log-broadcast-record :schedule-broadcast-operation
                :coordinate-equipment-maintenance :flag-content-concern]]
      (let [p (adv/infer db {:op op :station-id "station-1"
                             :patch {:concern "possible unverified EAS test-tone flagged for review"}})
            s (store/mem-store {"station-1" {:station-id "station-1" :name "Harbor Community Radio"
                                              :registered? true :verified? true}})
            verdict (gov/check {:station-id "station-1"} nil p s)]
        (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
            (str "op " op "'s own default proposal text must not self-trip scope-exclusion"))))))

(deftest out-of-scope-hook-trips-scope-exclusion
  (testing "the test-only :out-of-scope? hook produces text the governor correctly HARD-blocks"
    (let [p (adv/infer db {:op :log-broadcast-record :station-id "station-1"
                           :out-of-scope? true :patch {}})
          s (store/mem-store {"station-1" {:station-id "station-1" :name "Harbor Community Radio"
                                            :registered? true :verified? true}})
          verdict (gov/check {:station-id "station-1"} nil p s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))
