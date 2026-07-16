(ns radioops.phase
  "Phase 0->3 staged rollout for the ISIC-6010 radio-broadcasting
  operations-coordination actor.

    Phase 0  read-only            -- no writes, still governor-gated.
    Phase 1  assisted-logging     -- broadcast-record logging allowed,
                                     every write needs human approval.
    Phase 2  assisted-coordination-- adds broadcast-operation
                                     scheduling and equipment-
                                     maintenance coordination, still
                                     approval.
    Phase 3  supervised auto      -- governor-clean, high-confidence
                                     `:log-broadcast-record`/
                                     `:schedule-broadcast-operation`/
                                     `:coordinate-equipment-maintenance`
                                     may auto-commit.
                                     `:flag-content-concern` NEVER
                                     auto-commits, at any phase.

  `:flag-content-concern` is deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come. Flagging a content concern always
  needs a human to actually look at it. `radioops.governor`'s own
  `always-escalate-ops` enforces the same invariant independently --
  two layers, not one, agree on this."
  (:require [radioops.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: `:flag-content-concern` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member
;; of any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops
  allowed to auto-commit when governor-clean>}."
  {0 {:label "read-only"              :writes #{}                                                                       :auto #{}}
   1 {:label "assisted-logging"       :writes #{:log-broadcast-record}                                                  :auto #{}}
   2 {:label "assisted-coordination"  :writes #{:log-broadcast-record :schedule-broadcast-operation
                                               :coordinate-equipment-maintenance}                                        :auto #{}}
   3 {:label "supervised-auto"        :writes write-ops
      :auto #{:log-broadcast-record :schedule-broadcast-operation :coordinate-equipment-maintenance}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE
    (:phase-approval), even if the governor was clean.
  - `:flag-content-concern` is never auto-eligible at any phase, so it
    always escalates once the governor clears it (or holds if the
    governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a BroadcastOpsGovernor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
