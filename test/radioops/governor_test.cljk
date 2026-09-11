(ns radioops.governor-test
  "Pure unit tests of `radioops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [radioops.governor :as gov]
            [radioops.store :as store]))

(def station-1 {:station-id "station-1" :name "Harbor Community Radio" :registered? true :verified? true})
(def station-3 {:station-id "station-3" :name "Undisclosed Low-Power Station" :registered? true :verified? false})

(defn- clean-proposal [op station-id]
  {:op op :station-id station-id :summary "s" :rationale "routine broadcast-operations coordination"
   :cites [station-id] :effect :propose :value {} :confidence 0.85})

(deftest station-unregistered-is-hard
  (testing "no station record at all -> HARD hold"
    (let [s (store/mem-store {"station-1" station-1})
          verdict (gov/check {} nil (clean-proposal :log-broadcast-record "unknown-station") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:station-unverified} (map :rule (:violations verdict)))))))

(deftest station-unverified-is-hard
  (testing "station registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"station-3" station-3})
          verdict (gov/check {} nil (clean-proposal :log-broadcast-record "station-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:station-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"station-1" station-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-broadcast-operation "station-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"station-1" station-1})
          verdict (gov/check {} nil (clean-proposal :finalize-broadcast "station-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest on-air-content-decision-is-hard-and-permanent
  (testing "a proposal whose rationale touches finalizing an on-air-content decision is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"station-1" station-1})
          poisoned (assoc (clean-proposal :log-broadcast-record "station-1")
                          :rationale "finalized the on-air content decision on the lead segment's running order"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest emergency-alert-broadcast-decision-content-is-hard
  (testing "a proposal touching an emergency-alert-broadcast decision is HARD-blocked, same as on-air-content"
    (let [s (store/mem-store {"station-1" station-1})
          poisoned (assoc (clean-proposal :log-broadcast-record "station-1")
                          :rationale "authorized the emergency alert broadcast decision for the county-wide test")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest emergency-alert-broadcast-decision-value-content-is-hard
  (testing "a proposal touching an emergency-alert-broadcast decision embedded in the draft value is HARD-blocked"
    (let [s (store/mem-store {"station-1" station-1})
          poisoned (assoc (clean-proposal :schedule-broadcast-operation "station-1")
                          :value {:decision "emergency alert broadcast decision issued for the incoming storm"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest on-air-content-decision-japanese-content-is-hard
  (testing "a JA proposal whose rationale directly claims to finalize on-air content is HARD-blocked"
    (let [s (store/mem-store {"station-1" station-1})
          poisoned (assoc (clean-proposal :coordinate-equipment-maintenance "station-1")
                          :rationale "オンエア内容の確定を行った上での設備調整")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-content-concern-is-not-scope-excluded
  (testing "flagging an observed FCC-compliance/on-air-incident/emergency-alert concern (not a decision) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"station-1" station-1})
          concern (assoc (clean-proposal :flag-content-concern "station-1")
                         :value {:concern "possible EAS test-tone mistimed during a live segment, and whether the on-air chain logged it correctly could not be independently confirmed"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (FCC-compliance/on-air-incident/emergency-alert concern) is exactly what this op exists to surface"))))

(deftest legitimate-equipment-maintenance-is-not-scope-excluded
  (testing "a clean equipment-maintenance coordination proposal that merely mentions it does not finalize on-air content never trips scope-exclusion"
    (let [s (store/mem-store {"station-1" station-1})
          clean (assoc (clean-proposal :coordinate-equipment-maintenance "station-1")
                       :rationale "adjusts only the transmitter maintenance window; does not touch what airs during the outage")
          verdict (gov/check {} nil clean s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "maintenance-scheduling language must not accidentally self-trip the on-air-content/emergency-alert block"))))
