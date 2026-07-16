(ns radioops.governor
  "BroadcastOpsGovernor -- the independent compliance layer that earns
  the BroadcastOpsAdvisor the right to commit. The advisor has no
  notion of whether a station is actually registered and verified,
  whether its own proposed `:effect` secretly claims a direct actuation
  instead of a mere proposal, or whether it has silently drifted into a
  permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- OPERATIONS COORDINATION
  ONLY (broadcast-record logging, broadcast-operation scheduling,
  equipment-maintenance coordination, content-concern flagging). It
  NEVER performs or authorizes:
    - finalizing an on-air-content decision (what actually airs, or
      whether a segment/program runs as scheduled)
    - an emergency-alert-broadcast decision (whether/when to activate
      or issue an Emergency Alert System transmission)

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Station unverified        -- the target station record (the
                                     broadcaster's own record of a
                                     licensed radio station) must exist
                                     AND be independently confirmed
                                     `:registered?`/`:verified?` in the
                                     store before ANY proposal for it
                                     may commit or even escalate. Never
                                     trusts a proposal's own claim about
                                     the station -- re-derived from the
                                     station's own store record, the
                                     same 'ground truth, not self-
                                     report' discipline every sibling
                                     actor's governor uses.
    2. Effect not :propose       -- every proposal's `:effect` MUST be
                                     `:propose`. Any other effect value
                                     is, by construction, a claim to
                                     directly actuate/commit outside
                                     governance -- HARD block, not
                                     merely low-confidence.
    3. Scope exclusion           -- ANY proposal (regardless of op)
                                     whose op, rationale, summary,
                                     citations or draft value touches
                                     finalizing-an-on-air-content-
                                     decision / emergency-alert-
                                     broadcast-decision territory is a
                                     HARD, PERMANENT block -- this
                                     actor's charter excludes that
                                     territory structurally, not as a
                                     rollout milestone. Evaluated
                                     UNCONDITIONALLY on every proposal.
                                     An op outside the closed four-op
                                     allowlist is the SAME failure mode
                                     (an advisor proposing something it
                                     was never authorized to propose)
                                     and is folded into this same
                                     check.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-content-concern` -- ALWAYS escalates to a human, regardless
  of confidence, regardless of how clean the proposal otherwise is.
  `radioops.phase` independently agrees: `:flag-content-concern` is
  never a member of any phase's `:auto` set either -- two layers, not
  one.

  NOTE on scope-excluded-terms phrasing (a known self-tripping bug in
  this actor family): terms are written as the finalization/execution
  ACTION or its decision-outcome noun phrase (\"on-air content
  decision\", \"emergency alert broadcast decision\"), never as a bare
  noun (\"content\", \"broadcast\", \"emergency\"). A bare-noun term
  would accidentally match inside this advisor's own legitimate
  `:flag-content-concern` rationale, which routinely discusses on-air-
  incident/emergency-alert topics as OBSERVATIONS, not as finalized
  decisions -- see
  `radioops.advisor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`."
  (:require [clojure.string :as str]
            [radioops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-broadcast-record :schedule-broadcast-operation
    :flag-content-concern :coordinate-equipment-maintenance})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-content-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- finalizing an on-air-
  content decision, or an emergency-alert-broadcast decision. Scanned
  across the proposal's op/summary/rationale/cites/value, never
  trusting the advisor's own framing of its intent.

  Each term is phrased as the finalization/execution ACTION or its
  decision-outcome noun phrase, never a bare noun -- a bare noun (e.g.
  \"content\" or \"emergency\") would self-trip on this actor's own
  legitimate `:flag-content-concern` proposals, which legitimately
  discuss on-air-incident and emergency-alert topics as raw
  observations, never as finalized decisions."
  ["on-air content decision" "on-air-content decision" "on air content decision"
   "finalize on-air content" "finalize the on-air content" "on-air decision"
   "emergency alert broadcast decision" "emergency-alert broadcast decision"
   "emergency broadcast decision" "emergency alert decision" "emergency alert broadcast"
   "オンエア内容の確定" "放送内容の確定" "番組内容の確定" "オンエアの確定" "オンエア内容確定"
   "緊急警報放送の確定" "緊急警報放送の発令確定" "eas発令の確定"
   "緊急放送の発令承認" "緊急警報放送の発令承認" "eas起動の確定" "緊急警報放送の起動確定"])

;; ----------------------------- checks -----------------------------

(defn- station-unverified-violations
  "The target station must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the
  proposal's own `:station-id` claim without a store lookup."
  [{:keys [station-id]} st]
  (let [r (store/station st station-id)]
    (when-not (and r (:registered? r) (:verified? r))
      [{:rule :station-unverified
        :detail (str station-id " は未登録または未検証の放送局 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches finalizing-an-on-air-content-decision/
  emergency-alert-broadcast-decision territory, regardless of
  confidence or how clean every other check is. Evaluated
  UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob (str/lower-case %)) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "オンエア内容の確定判断/緊急警報放送(EAS)の発令確定判断領域に触れる提案は永久に禁止"}])))

(defn check
  "Censors a BroadcastOpsAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [station-id (or (:station-id proposal) (:station-id request))
        hard (into []
                   (concat (station-unverified-violations {:station-id station-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :station-id (:station-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
