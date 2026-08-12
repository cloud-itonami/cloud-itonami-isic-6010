(ns radioops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2: this repo had a product face
  (`docs/index.html`) but NO operator console and no generator at all.

  Everything on the generated page is produced by actually RUNNING this
  repo's own actor stack -- `radioops.operation` (a langgraph-clj
  StateGraph) -> `radioops.governor` (the independent BroadcastOps
  censor) -> `radioops.phase` (the rollout gate) -> `radioops.store`
  (the SSoT + append-only ledger). No number, station id, call sign,
  confidence, rule name or hold reason on the page is hand-typed: the
  station directory comes from `radioops.store/demo-data`, the op
  allowlist and confidence floor from `radioops.governor`, the phase
  table from `radioops.phase/phases`, and every row of the ledger,
  coordination-log, run trace and hold detail from a live `g/run*`.

  The scenario deliberately reaches every disposition this actor can
  produce, including THREE structurally different kinds of hold, which
  the page keeps separate because they mean different things:

    * HARD governor hold -- `:t :governor-hold` WITH violations. A
      permanent, un-overridable compliance block. It NEVER reaches a
      human: the graph routes :decide -> :hold without ever passing
      through :request-approval, so no approval can lift it.
    * phase hold        -- `:t :governor-hold` with EMPTY violations
      plus a `:phase-reason`. A rollout-stage block (the op is not yet
      enabled at this phase). Not a compliance judgement; it lifts by
      advancing the phase.
    * human-rejected hold -- `:t :approval-rejected`. This one DID
      reach a human, who declined.

  `-main` refuses to write the file at all if the run produced zero
  HARD holds -- a console that shows no real governor block would be
  advertising a governor nobody exercised.

  Deterministic: no timestamps, no clock reads, no randomness, and
  every map iteration is explicitly ordered, so two runs against the
  same seed are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [radioops.advisor :as advisor]
            [radioops.governor :as governor]
            [radioops.operation :as op]
            [radioops.phase :as phase]
            [radioops.store :as store]))

;; ----------------------------- drifted advisors -----------------------------
;; Both of these reproduce a *compromised or confused* BroadcastOpsAdvisor
;; -- exactly the failure mode `radioops.governor` exists to catch. They
;; are the only way to exercise the `:effect-not-propose` and
;; `:op-not-allowed` HARD rules end-to-end, because the default mock
;; advisor is (correctly) incapable of emitting either. `radioops.sim`
;; uses the same technique for `:effect :commit`.

(defn- direct-actuation-advisor
  "An advisor that claims a DIRECT ACTUATION -- `:effect :commit`
  instead of `:propose`. HARD-blocked by `:effect-not-propose`."
  []
  (reify advisor/Advisor
    (-advise [_ _store request]
      (assoc (advisor/infer nil request) :effect :commit))))

(defn- out-of-charter-advisor
  "An advisor that has drifted outside the closed four-op allowlist and
  proposes an op this actor was never authorized to propose. Its
  `:effect` is a well-formed `:propose` and its target station is
  verified, so `:op-not-allowed` is the ONLY thing standing between it
  and a commit."
  []
  (reify advisor/Advisor
    (-advise [_ _store {:keys [station-id]}]
      {:op         :authorize-emergency-alert-broadcast
       :station-id station-id
       :summary    (str station-id " の緊急警報放送(EAS)を発令する")
       :rationale  "憲章外の判断領域に踏み込んだ advisor の再現"
       :cites      [station-id]
       :effect     :propose
       :value      {:station-id station-id}
       :confidence 0.71})))

;; ----------------------------- the scenario -----------------------------

(defn- ctx
  "The requesting operator's context. Same human throughout; only the
  rollout phase varies, so the page can show the phase gate moving."
  [ph]
  {:actor-id "director-1" :actor-role :program-director :phase ph})

(defn- run-step!
  "Drives ONE coordination request through the real compiled actor. If
  the actor pauses for approval (`interrupt-before #{:request-approval}`)
  and this step carries an `:approve` decision, resumes it with that
  decision -- a real human-in-the-loop resume, not a simulated one."
  [{:keys [actor id label phase request approve]}]
  (let [paused (:state (g/run* actor {:request request :context (ctx phase)}
                               {:thread-id id}))
        final  (if (and approve (= :escalate (:disposition paused)))
                 (:state (g/run* actor {:approval approve}
                                 {:thread-id id :resume? true}))
                 paused)]
    {:id id :label label :phase phase :request request
     :approve approve :state final}))

(defn run-demo!
  "Seeds a fresh store and drives 13 coordination requests through the
  REAL actor. Returns `{:db store :runs [..]}` -- `:runs` carries each
  graph run's final channel state, which is where the approval-request /
  approval-granted audit facts live (the store ledger, by design, only
  receives commit and hold facts).

  Coverage, all against `radioops.store/demo-data`'s three stations:

    committed  -- station-1 logs a broadcast record, schedules an
                  operation and coordinates transmitter maintenance,
                  all governor-clean at phase 3 (supervised auto);
                  station-2 logs a record at phase 1 (writes enabled
                  but nothing is auto-eligible -> :phase-approval
                  escalation, human approves) and schedules an
                  operation clean at phase 3; station-1 flags a
                  content concern, which ALWAYS escalates at every
                  phase (`:always-escalate`) and is approved.
    human hold -- station-2 flags a content concern and the program
                  director REJECTS it.
    phase hold -- station-2 requests equipment maintenance at phase 0
                  (read-only); the governor is clean, the phase gate
                  is not (:phase-disabled).
    HARD holds -- station-99 (absent from the directory) and station-3
                  (registered but NOT verified) both hit
                  :station-unverified; a drifted advisor claiming
                  `:effect :commit` hits :effect-not-propose; the
                  default advisor pushed into out-of-scope territory
                  hits :scope-excluded; a drifted advisor proposing an
                  op outside the closed allowlist hits :op-not-allowed.
                  None of the five ever reaches :request-approval."
  []
  (let [db     (store/seed-db)
        actor  (op/build db)
        direct (op/build db {:advisor (direct-actuation-advisor)})
        drift  (op/build db {:advisor (out-of-charter-advisor)})
        plan
        [{:actor actor :id "t01" :phase 3
          :label "放送記録のログ（phase 3・governor clean → 自動コミット）"
          :request {:op :log-broadcast-record :station-id "station-1"
                    :patch {:segment "morning show" :playlist-count 14}}}
         {:actor actor :id "t02" :phase 3
          :label "番組編成スケジュールの提案（phase 3・自動コミット）"
          :request {:op :schedule-broadcast-operation :station-id "station-1"
                    :patch {:segment "evening news" :date "2026-08-01"}}}
         {:actor actor :id "t03" :phase 3
          :label "送信所設備の保守調整（phase 3・自動コミット）"
          :request {:op :coordinate-equipment-maintenance :station-id "station-1"
                    :patch {:equipment "transmitter" :window "2026-08-03T02:00"}}}
         {:actor actor :id "t04" :phase 1
          :label "放送記録のログ（phase 1・自動対象外 → 人間が承認）"
          :request {:op :log-broadcast-record :station-id "station-2"
                    :patch {:segment "afternoon drive" :playlist-count 18}}
          :approve {:status :approved :by "program-director-2"}}
         {:actor actor :id "t05" :phase 3
          :label "番組編成スケジュールの提案（phase 3・自動コミット）"
          :request {:op :schedule-broadcast-operation :station-id "station-2"
                    :patch {:segment "civic town hall" :date "2026-08-05"}}}
         {:actor actor :id "t06" :phase 3
          :label "コンテンツ懸念フラグ（常にエスカレート → 人間が承認）"
          :request {:op :flag-content-concern :station-id "station-1"
                    :patch {:concern "possible EAS test-tone mistimed during a live segment"
                            :confidence 0.92}}
          :approve {:status :approved :by "program-director-1"}}
         {:actor actor :id "t07" :phase 3
          :label "コンテンツ懸念フラグ（常にエスカレート → 人間が却下）"
          :request {:op :flag-content-concern :station-id "station-2"
                    :patch {:concern "sponsor identification announcement missing from a paid segment"
                            :confidence 0.88}}
          :approve {:status :rejected :by "program-director-2"}}
         {:actor actor :id "t08" :phase 0
          :label "設備保守の調整（phase 0 read-only → phase hold）"
          :request {:op :coordinate-equipment-maintenance :station-id "station-2"
                    :patch {:equipment "studio console" :window "2026-08-09T03:00"}}}
         {:actor actor :id "t09" :phase 3
          :label "未登録の放送局への提案（HARD hold）"
          :request {:op :log-broadcast-record :station-id "station-99"
                    :patch {:segment "unknown"}}}
         {:actor actor :id "t10" :phase 3
          :label "登録済みだが未検証の放送局への提案（HARD hold）"
          :request {:op :log-broadcast-record :station-id "station-3"
                    :patch {:segment "draft"}}}
         {:actor direct :id "t11" :phase 3
          :label "advisor が直接実行を主張（:effect :commit → HARD hold）"
          :request {:op :schedule-broadcast-operation :station-id "station-1"
                    :patch {:segment "midday talk"}}}
         {:actor actor :id "t12" :phase 3
          :label "advisor がオンエア確定/EAS 発令領域へ逸脱（HARD hold・恒久）"
          :request {:op :log-broadcast-record :station-id "station-1"
                    :out-of-scope? true :patch {}}}
         {:actor drift :id "t13" :phase 3
          :label "advisor が許可 op 一覧の外を提案（HARD hold）"
          :request {:op :flag-content-concern :station-id "station-2"
                    :patch {:concern "advisor drift"}}}]]
    {:db db :runs (mapv run-step! plan)}))

;; ----------------------------- hold classification -----------------------------

(defn hard-hold?
  "A HARD governor hold: a compliance violation the graph routes
  straight to :hold, never through :request-approval. `:violations`
  is non-empty ONLY for the governor's three hard checks -- a phase
  hold carries the same `:t` with an empty vector."
  [f]
  (and (= :governor-hold (:t f)) (seq (:violations f))))

(defn- phase-hold? [f]
  (and (= :governor-hold (:t f)) (empty? (:violations f))))

(defn- human-hold? [f] (= :approval-rejected (:t f)))

(defn hard-holds
  "Every HARD governor hold on the store's append-only ledger."
  [db]
  (filterv hard-hold? (store/ledger db)))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- nm [v] (if (keyword? v) (name v) (str v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- tag [cls txt] (str "<span class=\"" cls "\">" txt "</span>"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- tbl [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows) (str (str/join "\n" rows) "\n") "")
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (if lead (str "    <p class=\"muted\">" lead "</p>\n") "")
       body
       "  </section>\n"))

(defn- ops-list [ops]
  (if (seq ops)
    (str/join " " (map #(code (str %)) (sort-by nm ops)))
    (tag "muted" "（なし）")))

;; ----------------------------- derived sections -----------------------------

(defn- summary-section [db runs]
  (let [led    (vec (store/ledger db))
        recs   (vec (store/coordination-log db))
        hard   (filterv hard-hold? led)
        phaseh (filterv phase-hold? led)
        humanh (filterv human-hold? led)
        commits (filterv #(= :committed (:t %)) led)
        approved (filterv #(some (fn [f] (= :approval-granted (:t f))) (:audit (:state %))) runs)
        asked    (filterv #(some (fn [f] (= :approval-requested (:t f))) (:audit (:state %))) runs)
        rules  (sort (distinct (mapcat #(map :rule (:violations %)) hard)))]
    (section
     "この実行の要約"
     (str "下の数値はすべてこのページを生成した1回の実行の結果です。"
          "生成器 " (code "radioops.render-html") " が "
          (code "clojure -M:dev:render-html") " で実 actor を走らせ、"
          "その store と audit ledger だけを読んで描画しています。")
     (tbl ["指標" "値" "出所"]
          [(row "駆動した調整リクエスト" (tag "num" (count runs)) (str "1 リクエスト = 1 " (code "g/run*") " グラフ実行"))
           (row "監査台帳のファクト数" (tag "num" (count led)) (code "radioops.store/ledger"))
           (row "コミットされた調整レコード" (tag "num" (count recs)) (code "radioops.store/coordination-log"))
           (row "コミット判定" (tag "ok" (count commits)) (str "台帳の " (code ":t :committed")))
           (row "人間に承認を求めた回数" (tag "warn" (count asked)) (str "実行 audit の " (code ":t :approval-requested")))
           (row "人間が承認した回数" (tag "ok" (count approved)) (str "実行 audit の " (code ":t :approval-granted")))
           (row "人間が却下したホールド" (tag "err" (count humanh)) (str "台帳の " (code ":t :approval-rejected")))
           (row "フェーズ・ホールド（compliance ではない）" (tag "warn" (count phaseh))
                (str (code ":t :governor-hold") " かつ " (code ":violations []")))
           (row (str "<strong>HARD governor hold（人間に届かない）</strong>")
                (tag "critical" (count hard))
                (str (code ":t :governor-hold") " かつ " (code ":violations") " 非空"))
           (row "HARD hold が踏んだ異なるルール" (tag "critical" (count rules))
                (str/join " " (map #(code (str %)) rules)))]))))

(defn- stations-section [db]
  (let [led (vec (store/ledger db))
        for-station (fn [sid pred] (count (filterv #(and (= sid (:station-id %)) (pred %)) led)))]
    (section
     "放送局レジストリ（SSoT）"
     (str "局は " (code "radioops.store/demo-data") " のシード。"
          (code ":registered?") " / " (code ":verified?") " は governor が"
          "提案の自己申告ではなく<strong>この store のレコードから毎回引き直す</strong>ため、"
          "未検証の局はどの提案も先へ進めません。")
     (tbl ["局 ID" "名称" "コールサイン" "登録" "検証" "コミット" "HARD hold"]
          (for [{:keys [station-id name call-sign registered? verified?]} (store/all-stations db)]
            (row (code station-id)
                 (esc name)
                 (code call-sign)
                 (if registered? (tag "ok" "registered") (tag "err" "未登録"))
                 (if verified? (tag "ok" "verified") (tag "critical" "未検証"))
                 (tag "num" (for-station station-id #(= :committed (:t %))))
                 (let [n (for-station station-id hard-hold?)]
                   (if (pos? n) (tag "critical" n) (tag "muted" 0)))))))))

(defn- phase-section []
  (section
   "ロールアウト・フェーズ・ゲート"
   (str "表は " (code "radioops.phase/phases") " をそのまま描いたものです。"
        "既定は phase " (code (str phase/default-phase)) "。"
        (code ":writes") " に無い op はフェーズ・ホールド、"
        (code ":writes") " に在って " (code ":auto") " に無い op は"
        "governor が clean でも人間の承認へ回されます。"
        (code ":flag-content-concern") " はどのフェーズの " (code ":auto")
        " にも<strong>恒久的に</strong>属しません。")
   (tbl ["phase" "ラベル" "書き込み可 (:writes)" "自動コミット可 (:auto)"]
        (for [[ph {:keys [label writes auto]}] (sort-by key (seq phase/phases))]
          (row (tag "num" ph)
               (str (esc label) (when (= ph phase/default-phase)
                                  (str " " (tag "badge" "default"))))
               (ops-list writes)
               (ops-list auto))))))

(defn- governor-section [db]
  (let [led   (vec (store/ledger db))
        hard  (filterv hard-hold? led)
        fired (frequencies (mapcat #(map :rule (:violations %)) hard))
        rejected (count (filterv human-hold? led))
        escalate-fired (count (filterv #(and (= :committed (:t %))
                                             (contains? governor/always-escalate-ops (:op %)))
                                       led))
        rule-row (fn [rule kind desc]
                   (let [n (get fired rule 0)]
                     (row (code (str rule))
                          (if (= :hard kind)
                            (tag "critical" "HARD・恒久・上書き不可")
                            (tag "warn" "SOFT・人間へエスカレート"))
                          desc
                          (if (pos? n) (tag "critical" n) (tag "muted" 0)))))]
    (section
     "BroadcastOpsGovernor のルールと、この実行で実際に発火した回数"
     (str "governor は advisor とは<strong>別のシステム</strong>で、提案を拒否して HOLD に落とせます。"
          "HARD ルールは3種すべて恒久で、いかなる人間の承認でも上書きできません"
          "（グラフが " (code ":decide") " → " (code ":hold") " と直行し "
          (code ":request-approval") " を通らないため、構造的に届きません）。"
          "信頼度フロアは " (code (str governor/confidence-floor)) "、"
          "許可 op は閉じた一覧 " (ops-list governor/allowed-ops) " のみ。")
     (tbl ["ルール" "種別" "内容" "この実行での発火"]
          [(rule-row :station-unverified :hard
                     "対象局が store に存在し、かつ :registered? / :verified? であること。提案の自己申告は信用しない")
           (rule-row :effect-not-propose :hard
                     (str "提案の " (code ":effect") " は " (code ":propose") " のみ。それ以外はガバナンス外の直接実行の主張"))
           (rule-row :scope-excluded :hard
                     "op/要約/根拠/引用/下書き値のいずれかがオンエア内容の確定判断・緊急警報放送(EAS)の発令確定判断に触れた")
           (rule-row :op-not-allowed :hard
                     "許可 op の閉じた一覧の外。advisor が認可されていない提案をした場合と同じ失敗モード")
           (row (str (code ":always-escalate") " / " (code ":low-confidence"))
                (tag "warn" "SOFT・人間へエスカレート")
                (str (ops-list governor/always-escalate-ops)
                     " は信頼度に関わらず常に人間へ。加えて信頼度が "
                     (code (str governor/confidence-floor)) " 未満なら同じくエスカレート")
                (tag "warn" escalate-fired))
           (row (code ":approver-rejected")
                (tag "err" "人間の判断")
                "エスカレート先の人間が却下した。HARD ルールとは違い、これは人間に届いたホールド"
                (if (pos? rejected) (tag "err" rejected) (tag "muted" 0)))]))))

(defn- run-outcome [{:keys [state]}]
  (let [audit (:audit state)
        last-f (last audit)]
    (cond
      (= :commit (:disposition state)) (tag "ok" "コミット")
      (human-hold? last-f) (tag "err" "人間が却下 → HOLD")
      (hard-hold? last-f)
      (tag "critical" (str "HARD hold · "
                           (str/join ", " (map #(nm (:rule %)) (:violations last-f)))))
      (phase-hold? last-f)
      (tag "warn" (str "phase hold · " (nm (:phase-reason last-f))))
      :else (tag "muted" (nm (:disposition state))))))

(defn- run-path [{:keys [state]}]
  (let [audit (:audit state)
        req (first (filter #(= :approval-requested (:t %)) audit))
        grant (first (filter #(= :approval-granted (:t %)) audit))
        rej (first (filter #(= :approval-rejected (:t %)) audit))]
    (cond
      grant (str (tag "warn" (str "承認要求 · " (nm (:reason req))))
                 " → " (tag "ok" (str "承認 · " (esc (:by grant)))))
      rej   (str (tag "warn" (str "承認要求 · " (nm (:reason req))))
                 " → " (tag "err" "却下"))
      req   (tag "warn" (str "承認要求 · " (nm (:reason req))))
      :else (tag "muted" "人間を経由せず"))))

(defn- runs-section [runs]
  (section
   "シナリオ実行トレース"
   (str "1 行 = 1 回の " (code "g/run*") "（"
        (code ":intake") " → " (code ":advise") " → " (code ":govern") " → "
        (code ":decide") " → " (code ":commit") " | " (code ":hold") " | "
        (code ":request-approval") "）。"
        "「提案 op」は advisor が実際に返した op で、要求 op と食い違う行は advisor の逸脱です。")
   (tbl ["#" "シナリオ" "phase" "要求 op" "局" "提案 op" "信頼度" "人間の経路" "結果"]
        (for [{:keys [id label phase request state] :as r} runs]
          (row (code id)
               (esc label)
               (tag "num" phase)
               (code (str (:op request)))
               (code (:station-id request))
               (let [pop (-> state :proposal :op)]
                 (if (and pop (not= pop (:op request)))
                   (tag "critical" (code (str pop)))
                   (if pop (code (str pop)) (tag "muted" "—"))))
               (tag "num" (or (-> state :verdict :confidence) "—"))
               (run-path r)
               (run-outcome r))))))

;; ----------------------------- drafted proposals -----------------------------
;;
;; The trace above says WHETHER a run committed; these two sections say
;; WHAT was actually drafted. That distinction is load-bearing: a held
;; proposal never reaches the SSoT, so its payload exists ONLY on the
;; graph run's `:proposal` channel. Reading the store alone, an operator
;; can see that something was stopped but not what it would have done --
;; which is exactly the thing a reviewer needs in order to judge whether
;; the governor stopped the right thing.

(defn- stopped-by
  "What actually stopped this run, derived from the run's own audit
  channel (not from the label)."
  [{:keys [state]}]
  (let [last-f (last (:audit state))]
    (cond
      (= :commit (:disposition state)) (tag "ok" "何も止めていない（コミット済み）")
      (human-hold? last-f)             (tag "err" "人間（承認者が却下）")
      (hard-hold? last-f)
      (tag "critical" (str "governor · "
                           (str/join ", " (map #(nm (:rule %)) (:violations last-f)))))
      (phase-hold? last-f)             (tag "warn" (str "phase gate · " (nm (:phase-reason last-f))))
      :else                            (tag "muted" (nm (:disposition state))))))

(defn- kv-pairs
  "A proposal's draft payload as stable, sorted key/value chips, so a
  held proposal's content is legible instead of a single `pr-str` blob."
  [m]
  (let [m (dissoc m :station-id)]
    (if (seq m)
      (str/join " "
                (for [k (sort-by nm (keys m))]
                  (str (code (str k)) "=" (tag "num" (esc (pr-str (get m k)))))))
      (tag "muted" "（空）"))))

(defn- proposals-section [runs]
  (section
   "advisor が起草した提案の全文（コミットされなかったものを含む）"
   (str "上のトレースは<strong>止まったかどうか</strong>を、この表は"
        "<strong>何が起草されたか</strong>を示します。"
        "ホールドされた提案は SSoT に一切書かれないため、その中身は"
        "グラフ実行の " (code ":proposal") " チャネルにしか存在しません"
        "——台帳だけを読んでも「何が止められたのか」は分かりません。"
        "行は " (code "radioops.advisor") " が実際に返した提案そのもので、"
        (code ":effect") " が " (code ":propose") " 以外の行は advisor の逸脱です。")
   (tbl ["#" "op" "局" ":effect" "信頼度" "advisor の要約" "下書き payload" "何が止めたか"]
        (for [{:keys [id state] :as r} runs
              :let [p (:proposal state)]]
          (row (code id)
               (if (:op p) (code (str (:op p))) (tag "muted" "—"))
               (code (or (:station-id p) "—"))
               (if (= :propose (:effect p))
                 (code ":propose")
                 (tag "critical" (code (str (:effect p)))))
               (tag "num" (or (:confidence p) "—"))
               (if (:summary p) (esc (:summary p)) (tag "muted" "—"))
               (kv-pairs (:value p))
               (stopped-by r))))))

(def ^:private op-columns
  "Per-op domain columns. The keys are the `:patch` fields this actor's
  own advisor copies into a proposal's `:value`, so each op kind is
  shown in the vocabulary of the operation it actually is, rather than
  as a generic map dump."
  [[:log-broadcast-record "放送記録のログ（プレイリスト/セグメント/オンエアログ）"
    [[:segment "セグメント"] [:playlist-count "曲数"]]]
   [:schedule-broadcast-operation "番組編成スケジュールの提案"
    [[:segment "セグメント"] [:date "日付"]]]
   [:coordinate-equipment-maintenance "送信所/スタジオ設備の保守調整"
    [[:equipment "対象設備"] [:window "保守ウィンドウ"]]]
   [:flag-content-concern "コンテンツ懸念フラグ（どの phase でも自動コミットしない）"
    [[:concern "懸念の内容"] [:confidence "申告信頼度"]]]])

(defn- op-breakout-sections [runs]
  (str/join
   (for [[op title cols] op-columns
         :let [rs (filter #(= op (-> % :state :proposal :op)) runs)]]
     (section
      title
      (str (code (str op)) " として起草された提案 " (tag "num" (count rs)) " 件。"
           "値はすべてこの実行の実データで、"
           (code "radioops.advisor") " が要求の " (code ":patch") " から組み立てたものです。")
      (tbl (concat ["#" "局"] (map second cols) ["結果"])
           (for [{:keys [id state] :as r} rs
                 :let [v (-> state :proposal :value)]]
             (apply row
                    (concat [(code id) (code (:station-id v "—"))]
                            (for [[k _] cols]
                              (if (contains? v k)
                                (esc (str (get v k)))
                                (tag "muted" "—")))
                            [(run-outcome r)]))))))))

(defn- hard-holds-section [db]
  (let [hard (hard-holds db)]
    (section
     "HARD governor hold の詳細（人間に届かないホールド）"
     (str "この " (tag "critical" (count hard))
          " 件は " (code ":request-approval") " を一度も通っていません。"
          "グラフは " (code ":decide") " で " (code ":hold") " へ直行するため、"
          "承認者がいてもこれらを解除する経路そのものが存在しません。"
          "詳細文は governor が生成した原文です。")
     (tbl ["#" "op" "局" "ルール" "governor の詳細" "信頼度"]
          (map-indexed
           (fn [i f]
             (row (tag "num" (inc i))
                  (code (str (:op f)))
                  (code (:station-id f))
                  (str/join " " (map #(tag "critical" (code (str (:rule %)))) (:violations f)))
                  (esc (str/join " / " (keep :detail (:violations f))))
                  (tag "num" (:confidence f))))
           hard)))))

(defn- ledger-section [db]
  (let [led (vec (store/ledger db))
        kind (fn [f]
               (cond
                 (= :committed (:t f)) (tag "ok" "committed")
                 (hard-hold? f) (tag "critical" "HARD hold")
                 (phase-hold? f) (tag "warn" "phase hold")
                 (human-hold? f) (tag "err" "人間が却下")
                 :else (tag "muted" (nm (:t f)))))]
    (section
     "監査台帳（append-only）"
     (str "書き込むのは " (code ":commit") " ノードと " (code ":hold") " ノードだけで、"
          "台帳は追記のみ。どの局のどの操作が、どんな根拠で、コミット/ホールドされたかは"
          "常にこの不変ログへのクエリになります。"
          "<strong>この台帳には承認ファクト（" (code ":approval-requested")
          " / " (code ":approval-granted") "）が入りません</strong>"
          "——それらはグラフ実行の audit チャネルにしか残らず、下の「承認者の帰属」で扱います。")
     (tbl ["#" "ファクト" "種別" "op" "局" "actor" "根拠 (:basis)" "信頼度"]
          (map-indexed
           (fn [i {:keys [t op station-id actor basis confidence] :as f}]
             (row (tag "num" (inc i))
                  (code (str t))
                  (kind f)
                  (code (str op))
                  (code station-id)
                  (esc (or actor "—"))
                  (if (seq basis)
                    (str/join " " (map #(code (str %)) basis))
                    (tag "muted" (str (nm (:phase-reason f "—")))))
                  (tag "num" (or confidence "—"))))
           led)))))

(defn- approver-attribution
  "Measured, not assumed. Walks the ACTUAL stored records and the
  ACTUAL ledger to find where (if anywhere) the approving human's id
  survives, so this disclosure self-corrects if the store or the graph
  is later changed."
  [db runs]
  (let [recs     (vec (store/coordination-log db))
        led      (vec (store/ledger db))
        granted  (keep #(first (filter (fn [f] (= :approval-granted (:t f)))
                                       (:audit (:state %))))
                       runs)
        in-value   (count (filter #(contains? (:value %) :approved-by) recs))
        in-payload (count (filter #(contains? (:payload %) :approved-by) recs))
        in-ledger  (count (filter #(or (:by %) (= :approval-granted (:t %))) led))]
    {:records (count recs)
     :granted (count granted)
     :approvers (sort (distinct (keep :by granted)))
     :in-value in-value
     :in-payload in-payload
     :in-ledger in-ledger}))

(defn- records-section [db runs]
  (let [recs (vec (store/coordination-log db))
        {:keys [granted approvers in-value in-payload in-ledger]} (approver-attribution db runs)
        verdict
        (cond
          (zero? granted)
          (tag "muted" "この実行では人間の承認を伴うコミットが1件も無いため判定不能")

          (and (pos? in-payload) (zero? in-value))
          (tag "warn"
               (str "承認者 ID は保持されるが <code>[:payload :approved-by]</code> にだけ載る。"
                    "<code>[:value :approved-by]</code> は落ちる"))

          (and (pos? in-payload) (pos? in-value))
          (tag "ok" "承認者 ID は <code>:value</code> と <code>:payload</code> の両方に保持される")

          :else
          (tag "err" "承認者 ID はどのレコードにも保持されていない"))]
    (section
     "コミット済み調整レコードと、承認者の帰属"
     (str "レコードは " (code "radioops.store/commit-record!") " が SSoT へ書いたもの。"
          "承認者の帰属は<strong>この実行の実データを歩いて判定</strong>しています"
          "（決め打ちの記述ではないので、実装が変われば次の生成でこの記述も変わります）。")
     (str
      (tbl ["#" "op" "局" ":value（承認者キー）" ":payload の :approved-by" "下書き値"]
           (map-indexed
            (fn [i {:keys [op station-id value payload]}]
              (row (tag "num" (inc i))
                   (code (str op))
                   (code station-id)
                   (if (contains? value :approved-by)
                     (tag "ok" (esc (:approved-by value)))
                     (tag "muted" "無し"))
                   (if (contains? payload :approved-by)
                     (tag "ok" (esc (:approved-by payload)))
                     (tag "muted" "無し"))
                   (code (pr-str (dissoc value :station-id)))))
            recs))
      "    <p><strong>実測した帰属:</strong> " verdict "。</p>\n"
      "    <p class=\"muted\">内訳（すべてこの実行から計測）: 承認されたコミット "
      (tag "num" granted) " 件"
      (if (seq approvers)
        (str "（承認者 " (str/join " " (map #(code %) approvers)) "）")
        "")
      "、レコード " (tag "num" (count recs)) " 件のうち "
      (code ":value") " に承認者キーを持つもの " (tag "num" in-value) " 件、"
      (code ":payload") " に持つもの " (tag "num" in-payload) " 件。"
      "監査台帳側で承認者を保持しているファクトは " (tag "num" in-ledger) " 件"
      (if (zero? in-ledger)
        "——つまり<strong>台帳だけを読んでも「誰が承認したか」は分かりません</strong>。"
        "。")
      "</p>\n"))))

(defn- scope-section []
  (section
   "恒久的スコープ除外（この actor の憲章）"
   (str "この actor はオンエア内容の確定判断（実際に何が流れるか）と"
        "緊急警報放送(EAS)の発令確定判断を<strong>永久に</strong>行いません。"
        "ロールアウトの未達ではなく構造的な除外です。"
        "governor は提案の " (code ":op") " " (code ":summary") " "
        (code ":rationale") " " (code ":cites") " " (code ":value")
        " を1つの文字列に平坦化し、下の語を大小無視で走査します。"
        "語は必ず<strong>確定・発令という行為</strong>として書かれ、裸の名詞では"
        "書かれません——裸の名詞にすると、この actor 自身の正当な "
        (code ":flag-content-concern") " の根拠文（オンエア事故や EAS を"
        "<em>観察事実として</em>語る）に誤爆するためです。")
   (tbl ["#" "走査語"]
        (map-indexed (fn [i t] (row (tag "num" (inc i)) (code t)))
                     governor/scope-excluded-terms))))

;; ----------------------------- document -----------------------------

(defn render
  "Pure: `db` (a store already driven by `run-demo!`) + that run's
  `runs` -> the complete HTML document string. No clock, no randomness,
  every map iteration explicitly ordered."
  [db runs]
  (let [hard (hard-holds db)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-6010 · radio broadcasting operations — Operator Console</title>\n"
     "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style>\n"
     "</head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>ラジオ放送 運用調整（ISIC 6010） — Operator Console</h1>\n"
     "</header>\n"
     "<p class=\"subtitle\">"
     (tag "badge" "read-only サンプル") " "
     (tag "badge" "governor-gated") " "
     (tag "badge" (str "HARD hold " (count hard) " 件")) " "
     (tag "badge" "オンエア確定判断・EAS 発令は恒久的に対象外")
     "</p>\n"
     "<main>\n"
     (summary-section db runs)
     (stations-section db)
     (phase-section)
     (governor-section db)
     (runs-section runs)
     (proposals-section runs)
     (op-breakout-sections runs)
     (hard-holds-section db)
     (ledger-section db)
     (records-section db runs)
     (scope-section)
     "</main>\n"
     "<footer>\n"
     "  <p>このページは手書きではありません。"
     "<code>radioops.render-html</code> が <code>radioops.operation</code>"
     "（langgraph-clj StateGraph）を実際に走らせ、その "
     "<code>radioops.store</code> の SSoT と append-only 監査台帳だけを読んで生成しています。"
     "再生成は <code>clojure -M:dev:render-html</code>。"
     "同じシードに対して決定的（タイムスタンプ・乱数なし）なので、"
     "2回生成すればバイト単位で一致します。</p>\n"
     "  <p>cloud-itonami-isic-6010 · AGPL-3.0-or-later</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs]} (run-demo!)
        hs (hard-holds db)]
    (when (empty? hs)
      (throw (ex-info "no governor hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (io/make-parents out)
    (spit out (render db runs) :encoding "UTF-8")
    (println "wrote" out
             "(" (count (store/ledger db)) "ledger facts,"
             (count (store/coordination-log db)) "committed records,"
             (count hs) "HARD governor holds,"
             (count (distinct (mapcat #(map :rule (:violations %)) hs))) "distinct hard rules )")))
