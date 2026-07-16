(ns radioops.phase-test
  "Unit tests of `radioops.phase` rollout logic."
  (:require [clojure.test :refer [deftest is testing]]
            [radioops.phase :as phase]))

(def clean-verdict {:hard? false :escalate? false})
(def low-conf-verdict {:hard? false :escalate? true})
(def hard-verdict {:hard? true :escalate? false})

(deftest phase-0-read-only
  (testing "phase 0 allows no writes"
    (doseq [op [:log-broadcast-record :schedule-broadcast-operation
                :coordinate-equipment-maintenance :flag-content-concern]]
      (let [{:keys [disposition]} (phase/gate 0 {:op op} :commit)]
        (is (= :hold disposition)
            (str "phase 0 must hold all ops including " op))))))

(deftest phase-1-broadcast-record-only
  (testing "phase 1 allows only broadcast-record logging, requires approval"
    (let [{:keys [disposition reason]} (phase/gate 1 {:op :log-broadcast-record} :commit)]
      (is (= :escalate disposition))
      (is (= :phase-approval reason)))
    (let [{:keys [disposition]} (phase/gate 1 {:op :schedule-broadcast-operation} :commit)]
      (is (= :hold disposition)))))

(deftest phase-2-adds-coordination-ops
  (testing "phase 2 allows coordination ops, still requires approval"
    (doseq [op [:log-broadcast-record :schedule-broadcast-operation
                :coordinate-equipment-maintenance]]
      (let [{:keys [disposition]} (phase/gate 2 {:op op} :commit)]
        (is (= :escalate disposition)
            (str "phase 2 op " op " requires approval"))))))

(deftest phase-3-auto-commits-clean-ops
  (testing "phase 3 auto-commits clean, high-conf non-concern ops"
    (let [{:keys [disposition]} (phase/gate 3 {:op :log-broadcast-record} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :schedule-broadcast-operation} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :coordinate-equipment-maintenance} :commit)]
      (is (= :commit disposition)))))

(deftest content-concern-holds-when-not-enabled
  (testing ":flag-content-concern holds in phases 0-2 (not yet enabled)"
    (doseq [ph [0 1 2]]
      (let [{:keys [disposition]} (phase/gate ph {:op :flag-content-concern} :escalate)]
        (is (= :hold disposition)
            (str "phase " ph " has not enabled flag-content-concern yet"))))))

(deftest content-concern-escalates-when-enabled
  (testing ":flag-content-concern ALWAYS escalates when enabled, even if governor says commit"
    (let [{:keys [disposition]} (phase/gate 3 {:op :flag-content-concern} :commit)]
      (is (= :escalate disposition)
          "phase 3 must escalate content concerns regardless of governor disposition"))))

(deftest hard-hold-always-wins
  (testing "a governor HARD hold stays HOLD regardless of phase"
    (doseq [ph [0 1 2 3]]
      (let [{:keys [disposition]} (phase/gate ph {:op :log-broadcast-record} :hold)]
        (is (= :hold disposition)
            (str "phase " ph " must respect governor HARD hold"))))))

(deftest verdict->disposition-maps-correctly
  (testing "verdict->disposition correctly translates governor verdict to base disposition"
    (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false})))
    (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
    (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false})))))

(deftest flag-content-concern-never-in-any-phase-auto-set
  (testing "structural invariant: :flag-content-concern must never appear in any phase's :auto set, at any phase"
    (doseq [[ph {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-content-concern))
          (str "phase " ph "'s :auto set must never contain :flag-content-concern")))))
