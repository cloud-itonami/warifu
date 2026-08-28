;; babashka runner — the dependency-free suites only.
;;
;; THIS IS A SUBSET, NOT THE SUITE. `warifu.substrate.test-usdc` is absent on
;; purpose: it exercises the USDC-on-Base adapter, which requires
;; kotoba-lang/erc20 + kotoba-lang/base-l2, and base-l2's JSON-RPC envelopes
;; need org.clojure/data.json, which babashka cannot load (it dies in SCI at
;; data/json.clj:411, with or without an :exclusions workaround — measured
;; 2026-08-28).
;;
;; So a green here does NOT mean the suite passed. The canonical runner is
;;
;;     clojure -M:test        64 tests / 225 assertions
;;     bb test                51 tests / 195 assertions   (this file)
;;
;; and crucially the JVM one DISCOVERS namespaces by regex (`-r ".*"` in
;; deps.edn) rather than reading a hand-maintained list, so a new test file is
;; picked up there automatically. This list can go stale; that one cannot.
(require '[clojure.test :as t])

(def suites
  '[warifu.cells.test-refund
    warifu.cells.test-authorize
    warifu.cells.test-capture
    warifu.cells.test-settle
    warifu.cells.test-dispute
    warifu.cells.test-eavt-schema
    warifu.cells.test-guarded-substrate
    warifu.test-lexicons
    warifu.repository-contract-test])

(apply require suites)
(let [{:keys [fail error]} (apply t/run-tests suites)]
  (when-not (zero? (+ fail error))
    (System/exit 1)))
