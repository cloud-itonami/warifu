(ns warifu.cells.test-refund
  "warifu.refund tests — port of the refund-cell assertions from the Python
  covering tests (test_cells.py / test_guarded_substrate.py / test_eavt_schema.py),
  plus the substrate-guarding (UnwiredSubstrate loud-fail) assertions.

  The Python tests drive refund end-to-end through `InMemorySubstrate` after an
  authorize→settle. Here we port ONLY the refund cell's surface: we construct
  the post-settle substrate state directly (a settlements map) — an in-memory
  fake implementing the SubstratePort the refund cell depends on — and exercise
  the refund logic + its EAVT facts + the loud-fail sentinel.

  DEFERRED (not ported — they exercise sibling cells, not refund):
    - the full authorize/capture/settle/dispute lifecycle (test_cells.py) — those
      cells are separate ports; here the settlement is seeded directly.
    - GuardedSubstrate's write-guard rejection of fee-leaking / unknown-attr
      writes (test_guarded_substrate.py) — that is the eavt_schema/guarded_substrate
      port's responsibility; we port the refund-MOVES-money-through-the-substrate
      assertion (debit refund returns to holder, credit refund repays the 0% line)."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [warifu.cells.substrate :as substrate]
            [warifu.cells.refund :as refund]))

;; ── in-memory SubstratePort fake (refund subset of InMemorySubstrate) ──
;;
;; Models balances (debit) + credit (0% line) + a settlements map + an
;; append-only facts log, behind an atom. `reverse_settlement` mirrors the
;; Python InMemorySubstrate.reverse_settlement exactly (debit → holder balance
;; up, credit → 0% line up, refunded_usdc accrues, merchant debited).

(defrecord InMemorySubstrate [state]
  substrate/SubstratePort
  (load-settlement [_ settlement-id]
    (get-in @state [:settlements settlement-id]))
  (reverse-settlement [_ settlement-id amount-usdc]
    (let [s (get-in @state [:settlements settlement-id])
          holder (get s "holder")
          merchant (get s "merchant_did")
          funding (get s "funding")
          n (inc (:n @state))
          refund-id (str "refund-" n)]
      (swap! state
             (fn [st]
               (let [st (assoc st :n n)
                     st (update-in st [:settlements settlement-id "refunded_usdc"]
                                   (fnil + 0) amount-usdc)
                     st (update-in st [:balances merchant] (fnil - 0) amount-usdc)
                     st (if (= funding "debit")
                          (update-in st [:balances holder] (fnil + 0) amount-usdc)
                          (update-in st [:credit holder] (fnil + 0) amount-usdc))]
                 st)))
      [refund-id (str "0xtx-" refund-id)]))
  (write-facts [_ facts]
    (swap! state update :facts into facts)
    nil))

(defn- fresh-substrate
  "A post-settle in-memory substrate. Seeds one debit settlement (settle-D,
  holder acct-A debited 200000, so acct-A holds the pre-settle 1.0 USDC minus
  the settled 200000 = 800000) and one credit settlement (settle-C, holder
  acct-B drew 200000 of its 0.5 USDC 0% line, so credit acct-B = 300000)."
  []
  (->InMemorySubstrate
   (atom {:n 0
          :balances {"acct-A" 800000 "did:m" 200000}
          :credit {"acct-B" 300000}
          :settlements
          {"settle-D" {"holder" "acct-A" "merchant_did" "did:m"
                       "amount_usdc" 200000 "funding" "debit" "refunded_usdc" 0}
           "settle-C" {"holder" "acct-B" "merchant_did" "did:m"
                       "amount_usdc" 200000 "funding" "credit" "refunded_usdc" 0}}
          :facts []})))

(defn- has-attr? [facts attr]
  (some (fn [[_ a _ _]] (= a attr)) facts))

;; ── refund happy + over-refund + unknown (test_cells.py) ──────────

(deftest refund-partial-zero-fee
  (testing "partial refund (fee 0) + returned to holder (debit)"
    (let [sub (fresh-substrate)
          rf (refund/refund (refund/make-refund-request "settle-D" {:amount-usdc 50000}) sub)]
      (is (:refunded rf) "partial refund (fee 0)")
      (is (= 0 (:fee-usdc rf)) "fee is zero")
      (is (= 50000 (:amount-usdc rf)))
      ;; refund returned to holder: 800000 + 50000 = 850000
      (is (= 850000 (get-in @(:state sub) [:balances "acct-A"]))
          "refund returned to holder"))))

(deftest refund-over-and-unknown-rejected
  (testing "over-refund rejected + unknown settlement rejected"
    (let [sub (fresh-substrate)
          over (refund/refund (refund/make-refund-request "settle-D" {:amount-usdc 200001}) sub)]
      ;; refundable is 200000; 200001 > refundable → rejected
      (is (not (:refunded over)) "over-refund rejected")
      (is (= "refund exceeds refundable amount" (:reason over))))
    (let [sub (fresh-substrate)
          nf (refund/refund (refund/make-refund-request "settle-nope") sub)]
      (is (not (:refunded nf)) "refund unknown settlement rejected")
      (is (= "settlement not found" (:reason nf))))))

(deftest refund-already-fully-refunded
  (testing "a second full refund of the same settlement is rejected"
    (let [sub (fresh-substrate)
          first-rf (refund/refund (refund/make-refund-request "settle-D") sub)]
      (is (:refunded first-rf) "first full refund ok")
      (is (= 200000 (:amount-usdc first-rf)) "full refundable = amount_usdc")
      (let [second-rf (refund/refund (refund/make-refund-request "settle-D") sub)]
        (is (not (:refunded second-rf)) "already fully refunded")
        (is (= "already fully refunded" (:reason second-rf)))))))

;; ── credit settle draws 0% line, refund repays it (test_cells.py) ──

(deftest credit-refund-repays-zero-pct-line
  (testing "credit refund repays the 0% line (full refund, amount nil)"
    (let [sub (fresh-substrate)]
      ;; pre: credit acct-B = 300000 (drew 200000 of the 0.5 USDC line)
      (refund/refund (refund/make-refund-request "settle-C") sub)
      ;; post: 0% line repaid back to 500000
      (is (= 500000 (get-in @(:state sub) [:credit "acct-B"]))
          "credit refund repays 0% line"))))

;; ── EAVT facts: zero-fee + escrow-refund purpose + kind (test_eavt_schema.py) ──

(deftest refund-eavt-facts-conform
  (testing "refund emits the warifu/* EAVT facts with zero-fee + escrow-refund"
    (let [sub (fresh-substrate)
          rf (refund/refund (refund/make-refund-request "settle-D" {:amount-usdc 100000}) sub)
          facts (:eavt-facts rf)]
      (is (has-attr? facts "warifu/kind") "kind fact present")
      ;; kind value is "refund"
      (is (= "refund" (some (fn [[_ a v _]] (when (= a "warifu/kind") v)) facts)))
      ;; purpose is escrow-refund (the only permitted refund purpose)
      (is (= refund/refund-purpose
             (some (fn [[_ a v _]] (when (= a "warifu/purpose") v)) facts))
          "purpose = escrow-refund")
      ;; fee fact present and zero (決済手数料ゼロ invariant)
      (is (= 0 (some (fn [[_ a v _]] (when (= a "warifu/fee_usdc") v)) facts))
          "fee_usdc = 0")
      ;; facts landed in the backend ledger
      (is (seq (:facts @(:state sub))) "facts persisted to substrate")
      ;; every fact is a 4-tuple [E A V T] with string E and T
      (is (every? (fn [[e _ _ t]] (and (string? e) (string? t))) facts)
          "every fact is [E A V T] with string E/T"))))

;; ── substrate guarding: UnwiredSubstrate fails loudly ─────────────

(deftest unwired-substrate-loud-fail
  (testing "the default UnwiredSubstrate sentinel throws on use (no silent settle)"
    ;; refund with no injected substrate defaults to UnwiredSubstrate, whose
    ;; load_settlement throws ex-info — a forgotten injection never silently moves money.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not wired"
          (refund/refund (refund/make-refund-request "settle-D")))
        "refund with no substrate raises the unwired guard")
    (let [u (substrate/unwired-substrate)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reverse_settlement"
            (substrate/reverse-settlement u "settle-D" 1))
          "reverse_settlement loud-fails")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"write_facts"
            (substrate/write-facts u []))
          "write_facts loud-fails"))))

(defn -main [& _]
  (run-tests 'warifu.cells.test-refund))
