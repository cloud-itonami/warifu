(ns warifu.cells.test-settle
  "Tests for warifu.settle (ADR-2605302000 port). Injects an in-memory SubstratePort fake and
  exercises: full settle (amount nil ⇒ remaining), partial settle, hold-not-found decline,
  over-amount decline, the zero-fee + T+0 EAVT settlement facts, and that settle-transfer receives
  the hold's merchant_did/funding."
  (:require [clojure.test :refer [deftest is]]
            [warifu.cells.substrate :as substrate]
            [warifu.cells.settle :as s]))

(defn- mk
  "In-memory SubstratePort fake over a holds map {auth-id → hold}. settle-transfer records the opts
  it was called with, returns a fixed [settlement-id tx]; write-facts accumulates."
  [holds0]
  (let [facts (atom []) seen (atom nil)]
    {:facts facts :seen seen
     :port (reify substrate/SubstratePort
             (load-hold [_ aid] (get holds0 aid))
             (settle-transfer [_ opts] (reset! seen opts) ["settle-1" "0xtx-settle-1"])
             (write-facts [_ fs] (swap! facts into fs)))}))

(deftest test-full-settle
  (let [f (mk {"auth-1" {"amount_usdc" 500 "captured_usdc" 0 "merchant_did" "did:web:m" "funding" "debit"}})
        res (s/run (:port f) (s/make-settle-request "auth-1"))]
    (is (= true (:settled res)))
    (is (= "settle-1" (:settlement-id res)))
    (is (= "0xtx-settle-1" (:tx res)))
    (is (= 0 (:fee-usdc res)))
    (is (= "T+0" (:finality res)))
    (is (= 7 (count (:eavt-facts res))))
    (is (= ["settle-1" "warifu/kind" "settlement" "settle-1"] (first (:eavt-facts res))))
    (is (some #(= % ["settle-1" "warifu/fee_usdc" 0 "settle-1"]) (:eavt-facts res)))
    (is (some #(= % ["settle-1" "warifu/finality" "T+0" "settle-1"]) (:eavt-facts res)))
    ;; settle-transfer received the hold's merchant_did + funding + full amount
    (is (= {:merchant-did "did:web:m" :amount-usdc 500 :funding "debit" :auth-id "auth-1"} @(:seen f)))
    (is (= 7 (count @(:facts f))))))

(deftest test-partial-settle
  (let [f (mk {"auth-1" {"amount_usdc" 500 "captured_usdc" 100 "merchant_did" "did:web:m" "funding" "credit"}})
        res (s/run (:port f) (s/make-settle-request "auth-1" {:amount-usdc 250}))]
    (is (= true (:settled res)))
    (is (= 250 (:amount-usdc @(:seen f))))         ; 250 ≤ 400 remaining
    (is (= "credit" (:funding @(:seen f))))
    (is (some #(= % ["settle-1" "warifu/amount_usdc" 250 "settle-1"]) (:eavt-facts res)))))

(deftest test-hold-not-found
  (let [f (mk {})
        res (s/run (:port f) (s/make-settle-request "missing"))]
    (is (= false (:settled res)))
    (is (= "auth_hold not found / not approved" (:reason res)))
    (is (empty? @(:facts f)))))

(deftest test-over-amount-declines
  (let [f (mk {"auth-1" {"amount_usdc" 500 "captured_usdc" 200 "merchant_did" "did:web:m" "funding" "debit"}})
        res (s/run (:port f) (s/make-settle-request "auth-1" {:amount-usdc 400}))]  ; > 300 remaining
    (is (= false (:settled res)))
    (is (= "amount exceeds remaining authorized amount" (:reason res)))
    (is (nil? @(:seen f)))))                        ; transfer never attempted

(deftest test-tithe-eligible-and-unwired
  (is (contains? s/tithe-eligible "donation"))
  (is (contains? s/tithe-eligible "tithe"))
  (is (not (contains? s/tithe-eligible "internal-purchase")))
  ;; settle/1 uses the unwired substrate → load-hold loud-fails
  (is (thrown? clojure.lang.ExceptionInfo (s/settle (s/make-settle-request "auth-1")))))
