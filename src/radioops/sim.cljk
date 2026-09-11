(ns radioops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean broadcast-record
  logging request through intake -> advise -> govern -> decide ->
  approval -> commit at phase 1 (assisted-logging, always approval),
  then re-runs the same op at phase 3 (supervised-auto, clean + high
  confidence -> auto-commit), then a broadcast-operation scheduling
  request and an equipment-maintenance coordination request (both
  auto-commit clean at phase 3), then a content-concern flag (ALWAYS
  escalates, at any phase -- approve, then commit), then HARD-hold
  scenarios: an unregistered station, a station registered but not yet
  verified, a proposal whose own `:effect` is not `:propose`, and a
  proposal that has drifted into the permanently-excluded on-air-
  content-decision/emergency-alert-broadcast-decision scope."
  (:require [langgraph.graph :as g]
            [radioops.advisor :as advisor]
            [radioops.store :as store]
            [radioops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "program-director-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        director-phase-1 {:actor-id "director-1" :actor-role :program-director :phase 1}
        director-phase-3 {:actor-id "director-1" :actor-role :program-director :phase 3}
        actor (op/build db)]

    (println "== log-broadcast-record station-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-broadcast-record :station-id "station-1"
                                  :patch {:segment "morning show" :playlist-count 14}} director-phase-1)]
      (println r)
      (println "-- human program director approves --")
      (println (approve! actor "t1")))

    (println "\n== log-broadcast-record station-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-broadcast-record :station-id "station-1"
                                  :patch {:segment "afternoon drive" :playlist-count 18}} director-phase-3))

    (println "\n== schedule-broadcast-operation station-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-broadcast-operation :station-id "station-1"
                                  :patch {:segment "evening news" :date "2026-08-01"}} director-phase-3))

    (println "\n== coordinate-equipment-maintenance station-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-equipment-maintenance :station-id "station-1"
                                  :patch {:equipment "transmitter" :window "2026-08-03T02:00"}} director-phase-3))

    (println "\n== flag-content-concern station-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :flag-content-concern :station-id "station-1"
                                 :patch {:concern "possible EAS test-tone mistimed during a live segment" :confidence 0.92}} director-phase-3)]
      (println r)
      (println "-- human program director reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== log-broadcast-record station-99 (unregistered station -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-broadcast-record :station-id "station-99"
                                  :patch {:segment "unknown"}} director-phase-3))

    (println "\n== log-broadcast-record station-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-broadcast-record :station-id "station-3"
                                  :patch {:segment "draft"}} director-phase-3))

    (println "\n== schedule-broadcast-operation station-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t8" {:op :schedule-broadcast-operation :station-id "station-1"
                                           :patch {:segment "midday talk"}} director-phase-3)))

    (println "\n== log-broadcast-record station-1, advisor drifts into on-air-content/emergency-alert scope -> HARD hold, permanent ==")
    (println (exec-op actor "t9" {:op :log-broadcast-record :station-id "station-1"
                                   :out-of-scope? true
                                   :patch {}} director-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
