(ns radioops.advisor
  "BroadcastOpsAdvisor -- the *contained intelligence node* for the
  ISIC-6010 radio-broadcasting operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: broadcast-record logging (playlist/segment/on-air-log
  data), broadcast-operation scheduling (programming/segment
  scheduling), equipment-maintenance coordination (transmitter/studio-
  equipment maintenance coordination), and content-concern flagging
  (FCC-compliance / on-air-incident / emergency-alert-system concerns).
  CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record and NEVER a direct actuation -- every proposal's
  `:effect` is always `:propose`. Every output is censored downstream
  by `radioops.governor` before anything touches the SSoT.

  This advisor NEVER drafts a finalized on-air-content decision (what
  actually airs) or an emergency-alert-broadcast decision (whether/when
  to activate the Emergency Alert System) -- those are permanently out
  of scope for this actor, not merely un-implemented.
  `radioops.governor`'s `scope-exclusion-violations` independently
  re-scans every proposal for exactly this failure mode (a compromised
  or confused advisor drifting into scope it must never touch) and
  HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op          kw             ; echoes the request op
     :station-id  str
     :summary     str            ; human-facing draft / finding
     :rationale   str            ; why -- SCANNED by the scope-exclusion gate
     :cites       [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect      :propose       ; ALWAYS :propose -- never a direct actuation
     :value       map            ; the draft payload a human/system would review
     :confidence  0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-broadcast-record
  "Draft a broadcast-record log entry: playlist/segment/on-air-log
  data. Pure back-office logging of observed on-air-operations facts --
  never a judgment about what should air."
  [_db {:keys [station-id patch]}]
  {:op          :log-broadcast-record
   :station-id  station-id
   :summary     (str station-id " の放送記録（プレイリスト/セグメント/オンエアログ）を記録: " (pr-str (keys patch)))
   :rationale   "プレイリスト・セグメント・オンエアログなど放送運用事実の記録のみ。番組内容そのものの放送可否判断は行わない。"
   :cites       [station-id]
   :effect      :propose
   :value       (merge {:station-id station-id} patch)
   :confidence  0.94})

(defn- propose-broadcast-schedule
  "Draft a programming/segment scheduling proposal (a calendar entry,
  never a direct on-air dispatch or a call on what actually airs)."
  [_db {:keys [station-id patch]}]
  {:op          :schedule-broadcast-operation
   :station-id  station-id
   :summary     (str station-id " の番組編成（セグメント）スケジュールを提案: " (pr-str (keys patch)))
   :rationale   "番組編成・セグメントの日程調整のみ。オンエアで実際に何を流すかの確定判断ではない。"
   :cites       [station-id]
   :effect      :propose
   :value       (merge {:station-id station-id} patch)
   :confidence  0.89})

(defn- propose-equipment-maintenance
  "Draft a transmitter/studio-equipment maintenance coordination
  proposal (a maintenance-window scheduling entry only -- never a
  direct equipment actuation or a call on on-air content)."
  [_db {:keys [station-id patch]}]
  {:op          :coordinate-equipment-maintenance
   :station-id  station-id
   :summary     (str station-id " の送信所/スタジオ設備の保守調整を提案: " (pr-str (keys patch)))
   :rationale   "送信所・スタジオ設備の保守日程調整のみ。設備の直接操作や番組内容そのものの確定判断は伴わない。"
   :cites       [station-id]
   :effect      :propose
   :value       (merge {:station-id station-id} patch)
   :confidence  0.90})

(defn- propose-content-concern
  "Surface a content-concern (potential FCC-compliance issue, on-air
  incident, or emergency-alert-system concern observed at a station)
  for HUMAN triage. This op ALWAYS escalates in `radioops.governor` --
  never auto-committed at any phase -- regardless of how confident the
  advisor is that the concern is real."
  [_db {:keys [station-id patch]}]
  {:op          :flag-content-concern
   :station-id  station-id
   :summary     (str station-id " のコンテンツ懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale   "FCC等の放送規制遵守・オンエア事故・緊急警報放送(EAS)に関する懸念の観察事実の報告。常に人間の確認・判断が必要。"
   :cites       [station-id]
   :effect      :propose
   :value       (merge {:station-id station-id} patch)
   :confidence  (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-broadcast-record (propose-broadcast-record _db request)
                   :schedule-broadcast-operation (propose-broadcast-schedule _db request)
                   :coordinate-equipment-maintenance (propose-equipment-maintenance _db request)
                   :flag-content-concern (propose-content-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually finalized the on-air content decision and authorized the emergency alert broadcast decision")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t          :advisor-proposal
   :op         (:op proposal)
   :station-id (:station-id proposal)
   :summary    (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
