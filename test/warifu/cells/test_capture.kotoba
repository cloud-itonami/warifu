(ns warifu.cells.test-capture
  "Tests for warifu.capture (ADR-2605302000 port). Injects an in-memory SubstratePort fake and
  exercises: full capture (amount nil ⇒ remaining), partial capture + remaining math, hold-not-found
  decline, already-fully-captured decline, over-capture decline, and the zero-fee EAVT capture facts."
  (:require [clojure.test :refer [deftest is]]
            [warifu.cells.substrate :as substrate]
            [warifu.cells.capture :as c]))

(defn- mk
  "In-memory SubstratePort fake over a holds map {auth-id → hold}. record-capture returns a fixed
  id and bumps captured_usdc; write-facts accumulates into the returned atom."
  [holds0]
  (let [holds (atom holds0) facts (atom [])]
    {:facts facts :holds holds
     :port (reify substrate/SubstratePort
             (load-hold [_ aid] (get @holds aid))
             (record-capture [_ aid amt]
               (swap! holds update-in [aid "captured_usdc"] (fnil + 0) amt)
               "cap-1")
             (write-facts [_ fs] (swap! facts into fs)))}))

(deftest test-full-capture
  (let [f (mk {"auth-1" {"amount_usdc" 500 "captured_usdc" 0}})
        res (c/run (:port f) (c/make-capture-request "auth-1"))]
    (is (= true (:captured res)))
    (is (= "cap-1" (:capture-id res)))
    (is (= 500 (:amount-usdc res)))           ; nil → full remaining
    (is (= 0 (:remaining-usdc res)))
    (is (= 5 (count (:eavt-facts res))))
    (is (= ["cap-1" "warifu/kind" "capture" "cap-1"] (first (:eavt-facts res))))
    (is (some #(= % ["cap-1" "warifu/fee_usdc" 0 "cap-1"]) (:eavt-facts res)))
    (is (= 5 (count @(:facts f))))))

(deftest test-partial-capture-remaining
  (let [f (mk {"auth-1" {"amount_usdc" 500 "captured_usdc" 100}})   ; 400 remaining
        res (c/run (:port f) (c/make-capture-request "auth-1" {:amount-usdc 250}))]
    (is (= true (:captured res)))
    (is (= 250 (:amount-usdc res)))
    (is (= 150 (:remaining-usdc res)))        ; 400 - 250
    (is (some #(= % ["cap-1" "warifu/remaining_usdc" 150 "cap-1"]) (:eavt-facts res)))))

(deftest test-hold-not-found
  (let [f (mk {})
        res (c/run (:port f) (c/make-capture-request "missing"))]
    (is (= false (:captured res)))
    (is (= "auth_hold not found / not approved" (:reason res)))
    (is (empty? @(:facts f)))))

(deftest test-already-fully-captured
  (let [f (mk {"auth-1" {"amount_usdc" 500 "captured_usdc" 500}})
        res (c/run (:port f) (c/make-capture-request "auth-1"))]
    (is (= false (:captured res)))
    (is (= "hold already fully captured" (:reason res)))))

(deftest test-over-capture-declines
  (let [f (mk {"auth-1" {"amount_usdc" 500 "captured_usdc" 0}})
        res (c/run (:port f) (c/make-capture-request "auth-1" {:amount-usdc 600}))]
    (is (= false (:captured res)))
    (is (= "capture exceeds remaining authorized amount" (:reason res)))))

(deftest test-module-entry-unwired
  ;; capture/1 uses the unwired substrate → load-hold loud-fails (forgotten-injection guard)
  (is (thrown? clojure.lang.ExceptionInfo (c/capture (c/make-capture-request "auth-1")))))
