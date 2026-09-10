(ns warifu.cells.test-guarded-substrate
  "Tests for warifu.guarded-substrate (ADR-2605302000 port). A backend-recording SubstratePort fake
  confirms: write-facts validates BEFORE delegating (valid facts pass through + reach the backend;
  schema-violating facts throw and never reach the backend), and all other operations delegate
  unchanged to the backend."
  (:require [clojure.test :refer [deftest is]]
            [warifu.cells.substrate :as substrate]
            [warifu.cells.guarded-substrate :as g]))

(defn- backend
  "Recording SubstratePort fake — captures facts written + answers a couple of reads."
  []
  (let [written (atom [])]
    {:written written
     :port (reify substrate/SubstratePort
             (resolve-card [_ ct] (when (= ct "tok") "acct"))
             (usdc-balance [_ _] 999)
             (credit-available [_ _] 0)
             (place-hold [_ _ _] "auth-x")
             (load-hold [_ _] nil)
             (record-capture [_ _ _] "cap-x")
             (settle-transfer [_ _] ["settle-x" "0xtx"])
             (load-settlement [_ _] nil)
             (reverse-settlement [_ _ _] ["refund-x" "0xtx"])
             (open-dispute [_ _] "dispute-x")
             (write-facts [_ fs] (swap! written into fs) nil))}))   ; write_facts → None

(deftest test-valid-write-passes-through
  (let [b (backend)
        gs (g/guarded-substrate (:port b))
        facts [["s1" "warifu/kind" "settlement" "s1"] ["s1" "warifu/fee_usdc" 0 "s1"]]]
    (is (nil? (substrate/write-facts gs facts)))
    (is (= facts @(:written b)))))                 ; reached the backend

(deftest test-schema-violation-fails-closed
  (let [b (backend)
        gs (g/guarded-substrate (:port b))]
    ;; fee_usdc must be 0 → assert-valid throws → backend never written
    (is (thrown? clojure.lang.ExceptionInfo
                 (substrate/write-facts gs [["s1" "warifu/fee_usdc" 99 "s1"]])))
    (is (empty? @(:written b)))))

(deftest test-other-ops-delegate
  (let [gs (g/guarded-substrate (:port (backend)))]
    (is (= "acct" (substrate/resolve-card gs "tok")))
    (is (= 999 (substrate/usdc-balance gs "acct")))
    (is (= "auth-x" (substrate/place-hold gs "acct" {:amount-usdc 1})))
    (is (= "cap-x" (substrate/record-capture gs "auth-x" 1)))
    (is (= ["settle-x" "0xtx"] (substrate/settle-transfer gs {:amount-usdc 1})))
    (is (= ["refund-x" "0xtx"] (substrate/reverse-settlement gs "s1" 1)))
    (is (= "dispute-x" (substrate/open-dispute gs {:reason-code "fraud"})))))
