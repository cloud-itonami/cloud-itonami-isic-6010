# cloud-itonami-6010

Open Business Blueprint for **ISIC Rev.5 6010**: radio broadcasting (a
licensed radio broadcast station operator).

This repository designs a forkable OSS business for community radio
broadcasting: broadcast-license scope management, robotics-assisted
transmitter/studio-equipment inspection and maintenance, and program-
schedule/advertising-billing records — run by a qualified operator so
a broadcaster keeps its own licensing and programming history instead
of renting a closed broadcast-operations platform.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (transmitter-tower
inspection, studio/transmission-equipment maintenance) operate under
an actor that proposes actions and an independent **Radio Broadcast
Governor** that gates them. The governor never transmits content
itself; `:high`/`:safety-critical` actions (any program transmission
outside the station's own verified license scope, any content that
would violate a public-interest programming requirement) require
human sign-off.

## Core Contract

```text
intake + identity + broadcast-license scope + program schedule
        |
        v
Broadcast Operations Advisor -> Radio Broadcast Governor -> license record, transmission dispatch, billing record, or human approval
        |
        v
robot actions (gated) + program/maintenance record + billing record + audit ledger
```

No automated advice can dispatch a transmission the governor refuses,
approve programming outside its verified license scope, or publish a
billing record without governor approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `6010`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/phone`](https://github.com/kotoba-lang/phone) — shared telephony/audience-contact-records capability (call-in shows, listener contact lines)

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Implementation: `radioops` operations-coordination actor

The blueprint above is now backed by a real actor: a coordination-only
actor for radio-station broadcast back-office operations, behind an
independent Governor that earns advisor trust through structured
oversight: proposal → advise → govern → decide → commit|hold|escalate.

**This actor coordinates only. It does NOT hold direct on-air-content
authority or emergency-alert-broadcast authority** — see `CRITICAL
scope exclusions` below.

### Features

- **Closed proposal-op allowlist**: `log-broadcast-record`,
  `schedule-broadcast-operation`, `coordinate-equipment-maintenance`,
  `flag-content-concern` (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Station verified** — the target station (a licensed radio
     broadcast station record) must exist AND be registered/verified
     in the store before any proposal for it may commit or escalate.
  2. **Effect is `:propose`** — any other `:effect` value is rejected.
  3. **Scope exclusion** — finalizing an on-air-content decision (what
     actually airs) and an emergency-alert-broadcast decision
     (whether/when to activate the Emergency Alert System) are
     permanently blocked, regardless of confidence or op.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: broadcast-record logging only (approval-gated)
  - Phase 2: + broadcast-operation scheduling, equipment-maintenance
    coordination (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals
    (content concerns always escalate)
- **Append-only audit ledger** — every decision is an immutable log
  entry.
- **langgraph-clj StateGraph** — one request = one supervised run;
  human-in-the-loop via `interrupt-before`.

### CRITICAL scope exclusions

This actor coordinates the back-office operations of a licensed radio
broadcast station: playlist/segment/on-air-log production-record
logging, programming/segment scheduling proposals, transmitter/studio-
equipment maintenance coordination, and content-risk-concern flagging
(FCC-compliance, on-air incidents, Emergency Alert System concerns).

**This actor does NOT:**
- Finalize an on-air-content decision (what actually airs, or whether
  a segment/program runs as scheduled).
- Issue an emergency-alert-broadcast decision (whether/when to
  activate or issue an Emergency Alert System transmission).

Every proposal is `:effect :propose` only. `:flag-content-concern`
always escalates to a human, at every phase, regardless of confidence
— this actor never self-clears a content concern it raises.

### Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

### Test suite

- `test/radioops/governor_test.clj` — unit tests of governor hard
  checks and scope exclusion
- `test/radioops/advisor_test.clj` — advisor proposal shape and
  consistency, including a dedicated regression test that the default
  mock-advisor's own proposals never self-trip the scope-exclusion
  check
- `test/radioops/phase_test.clj` — rollout phase logic
- `test/radioops/governor_contract_test.clj` — full graph integration,
  audit trail
- `test/radioops/store_contract_test.clj` — Store protocol and
  MemStore implementation

### Modules

- `radioops.store` — SSoT (MemStore, String-keyed station directory,
  append-only ledger)
- `radioops.advisor` — contained intelligence node (mock + real-LLM
  seam)
- `radioops.governor` — independent compliance layer
- `radioops.phase` — staged rollout (0→3)
- `radioops.operation` — langgraph-clj StateGraph
- `radioops.sim` — demo driver

All source is portable `.cljc` with no JVM-only interop, per this
fleet's cljs-first runtime-priority rule.

## License

AGPL-3.0-or-later.
