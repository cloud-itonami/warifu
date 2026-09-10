(ns warifu.cells.test-authorize
  "Tests for warifu.authorize (ADR-2605302000 port). Injects an in-memory SubstratePort fake and
  exercises every decision branch: debit-balance approve, credit-reserve approve, insufficient-funds
  decline, card-not-found decline, Phase-2 purpose gated (and ungated when phase2-enabled), unknown-
  purpose decline, the zero-fee EAVT auth_hold facts, and that the gate short-circuits BEFORE any
  substrate call (so an unwired substrate is never touched on a gated/declined purpose)."
  (:require [clojure.test :refer [deftest is]]
            [warifu.cells.substrate :as substrate]
            [warifu.cells.authorize :as a]))

(defn- mk
  "In-memory SubstratePort fake. cfg = {:cards {token→account} :balances {acct→int} :credit {acct→int}}.
  place-hold returns a fixed id for determinism; write-facts accumulates into the returned atom."
  [cfg]
  (let [facts (atom [])]
    {:facts facts
     :port (reify substrate/SubstratePort
             (resolve-card [_ ct] (get (:cards cfg) ct))
             (usdc-balance [_ acct] (get (:balances cfg) acct 0))
             (credit-available [_ acct] (get (:credit cfg) acct 0))
             (place-hold [_ _ _] "auth-1")
             (write-facts [_ fs] (swap! facts into fs)))}))

(defn- req [overrides]
  (a/make-auth-request (merge {:card-token "tok" :amount-usdc 500 :funding "debit"
                               :purpose "internal-purchase" :merchant-did "did:web:m"
                               :idempotency-key "k1"} overrides)))

(deftest test-debit-approve
  (let [f (mk {:cards {"tok" "acct"} :balances {"acct" 1000}})
        res (a/run (:port f) false (req {}))]
    (is (= "approve" (:decision res)))
    (is (= "auth-1" (:auth-id res)))
    (is (= 8 (count (:eavt-facts res))))
    ;; facts written through the substrate + zero-fee + auth_hold kind
    (is (= 8 (count @(:facts f))))
    (is (= ["auth-1" "warifu/kind" "auth_hold" "auth-1"] (first (:eavt-facts res))))
    (is (some #(= % ["auth-1" "warifu/fee_usdc" 0 "auth-1"]) (:eavt-facts res)))
    (is (some #(= % ["auth-1" "warifu/note" "debit balance hold" "auth-1"]) (:eavt-facts res)))))

(deftest test-credit-approve
  (let [f (mk {:cards {"tok" "acct"} :credit {"acct" 800}})
        res (a/run (:port f) false (req {:funding "credit"}))]
    (is (= "approve" (:decision res)))
    (is (some #(= % ["auth-1" "warifu/funding" "credit" "auth-1"]) (:eavt-facts res)))
    (is (some #(= % ["auth-1" "warifu/note" "credit reserve (0% qard hasan)" "auth-1"]) (:eavt-facts res)))))

(deftest test-insufficient-funds-decline
  (let [f (mk {:cards {"tok" "acct"} :balances {"acct" 100}})    ; < 500
        res (a/run (:port f) false (req {}))]
    (is (= "decline" (:decision res)))
    (is (= "insufficient funds/credit" (:reason res)))
    (is (empty? @(:facts f)))))                                  ; no facts on decline

(deftest test-card-not-found-decline
  (let [f (mk {:cards {} :balances {}})
        res (a/run (:port f) false (req {}))]
    (is (= "decline" (:decision res)))
    (is (= "card not found" (:reason res)))))

(deftest test-phase2-purpose-gated
  ;; "purchase" is a Phase-2 purpose; with phase2-enabled false it is GATED before any substrate
  ;; call — so we can pass the loud-failing unwired substrate and it must NOT be touched.
  (let [res (a/run (substrate/unwired-substrate) false (req {:purpose "purchase"}))]
    (is (= "gated" (:decision res)))
    (is (re-find #"Council Lv7" (:reason res)))))

(deftest test-phase2-purpose-allowed-when-enabled
  (let [f (mk {:cards {"tok" "acct"} :balances {"acct" 1000}})
        res (a/run (:port f) true (req {:purpose "purchase"}))]
    (is (= "approve" (:decision res)))))

(deftest test-unknown-purpose-decline
  ;; unknown/prohibited purpose declines before touching the substrate
  (let [res (a/run (substrate/unwired-substrate) false (req {:purpose "tip"}))]
    (is (= "decline" (:decision res)))
    (is (= "purpose 'tip' not permitted" (:reason res)))))

(deftest test-module-entry-defaults
  ;; authorize/1 uses the unwired substrate; a gated purpose returns without touching it
  (let [res (a/authorize (req {:purpose "subscription"}))]
    (is (= "gated" (:decision res)))))
