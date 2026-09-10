(ns warifu.cells.guarded-substrate
  "GuardedSubstrate — schema-enforcing decorator for any SubstratePort (ADR-2605302000).

  1:1 port of `cells/guarded_substrate.py`. The production write path: wraps a backing
  SubstratePort and runs eavt-schema/assert-valid on EVERY write-facts before delegating — so
  malformed or fee-leaking datoms can never reach the kotoba QuadStore. All other operations
  (reads / holds / ERC-4337 settlement UserOps) pass through unchanged. No platform key is
  introduced (ADR-2605231525): the guard is pure validation; signing stays in the backend.

  Python's __getattr__ pass-through → a defrecord that implements the full SubstratePort protocol,
  delegating every method to the backend; only write-facts is intercepted (assert-valid first)."
  (:require [warifu.cells.substrate :as substrate]
            [warifu.cells.eavt-schema :as eavt]))

(defrecord GuardedSubstrate [backend]
  substrate/SubstratePort
  (resolve-card [_ card-token]        (substrate/resolve-card backend card-token))
  (usdc-balance [_ account]           (substrate/usdc-balance backend account))
  (credit-available [_ account]       (substrate/credit-available backend account))
  (place-hold [_ account opts]        (substrate/place-hold backend account opts))
  (load-hold [_ auth-id]              (substrate/load-hold backend auth-id))
  (record-capture [_ auth-id amt]     (substrate/record-capture backend auth-id amt))
  (settle-transfer [_ opts]           (substrate/settle-transfer backend opts))
  (load-settlement [_ settlement-id]  (substrate/load-settlement backend settlement-id))
  (reverse-settlement [_ sid amt]     (substrate/reverse-settlement backend sid amt))
  (open-dispute [_ opts]              (substrate/open-dispute backend opts))
  (write-facts [_ facts]
    (eavt/assert-valid facts)         ; kotoba write contract — fail-closed before persist
    (substrate/write-facts backend facts)))

(defn guarded-substrate
  "Wrap a backing SubstratePort so every write-facts is schema-validated first. Port of
  `GuardedSubstrate(backend)`."
  [backend]
  (->GuardedSubstrate backend))
