(ns radioops.store
  "SSoT for the ISIC-6010 radio-broadcasting operations COORDINATION
  actor, behind a `Store` protocol so the backend is a swap, not a
  rewrite -- the same seam every `cloud-itonami-isic-*` actor in this
  fleet uses.

  This actor coordinates the back-office operations of a licensed
  radio broadcast station: playlist/segment/on-air-log production-
  record logging, programming/segment scheduling proposals, transmitter/
  studio-equipment maintenance coordination, and content-concern
  flagging (FCC-compliance / on-air-incident / emergency-alert-system
  concerns raised by a program director or the advisor). It never
  touches finalizing an on-air-content decision (what actually airs) or
  an emergency-alert-broadcast decision (whether/when to activate the
  Emergency Alert System) -- see `radioops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `stations` directory keyed by `:station-id` STRING (never
  a keyword -- consistent keying from the start, avoiding the silent-
  miss bug that plagued an earlier shepherd attempt).

  A registered/verified station record (the broadcaster's own record of
  a licensed radio station) must exist before ANY proposal for it may
  ever commit or escalate -- `radioops.governor`'s
  `station-unverified-violations` re-derives this from the station's own
  `:registered?`/`:verified?` fields, never from proposal self-report,
  the SAME 'ground truth, not self-report' discipline every sibling
  actor's own governor uses.

  The ledger stays append-only: which station a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by
  whom is always a query over an immutable log.")

(defprotocol Store
  (station [s station-id] "Registered station record, or nil.
    Station map: {:station-id .. :name .. :call-sign .. :registered? bool :verified? bool}.")
  (all-stations [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-stations [s stations] "replace/seed the station directory (map station-id->station)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained station directory covering both the happy
  path and the governor's own hard checks, so the actor + tests run
  offline."
  []
  {:stations
   {"station-1" {:station-id "station-1" :name "Harbor Community Radio" :call-sign "KHCR"
                 :registered? true :verified? true}
    "station-2" {:station-id "station-2" :name "Civic FM" :call-sign "WCVF"
                 :registered? true :verified? true}
    "station-3" {:station-id "station-3" :name "Undisclosed Low-Power Station" :call-sign "WULP"
                 :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (station [_ station-id] (get-in @a [:stations station-id]))
  (all-stations [_] (sort-by :station-id (vals (:stations @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-stations [s stations] (when (seq stations) (swap! a assoc :stations stations)) s))

(defn seed-db
  "A MemStore seeded with the demo station directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `stations` map (station-id string
  -> station map) -- the primary test/dev entry point. `stations` may
  be empty (an unregistered-everywhere store)."
  [stations]
  (->MemStore (atom {:stations (or stations {}) :ledger [] :coordination-log []})))
