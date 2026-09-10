(ns warifu.cells.test-eavt-schema
  "Tests for warifu.eavt-schema (ADR-2605302000 port). Verifies validate-facts accepts conforming
  facts and flags each violation kind: wrong arity, non-string E/T, unknown attribute, wrong value
  type, and invariant breaches (the zero-fee + T+0 + known-kind/funding/status invariants); and
  that assert-valid throws on any violation."
  (:require [clojure.test :refer [deftest is]]
            [warifu.cells.eavt-schema :as e]))

(def ^:private good
  [["s1" "warifu/kind" "settlement" "s1"]
   ["s1" "warifu/amount_usdc" 500 "s1"]
   ["s1" "warifu/funding" "debit" "s1"]
   ["s1" "warifu/fee_usdc" 0 "s1"]
   ["s1" "warifu/finality" "T+0" "s1"]
   ["s1" "warifu/status" "open" "s1"]])

(deftest test-valid-facts-pass
  (is (= [] (e/validate-facts good)))
  (is (nil? (e/assert-valid good))))

(deftest test-arity-violation
  (is (= 1 (count (e/validate-facts [["s1" "warifu/kind" "settlement"]]))))   ; 3-tuple
  (is (re-find #"not a 4-tuple" (first (e/validate-facts [["a" "b"]])))))

(deftest test-non-string-e-or-t
  (let [vs (e/validate-facts [[1 "warifu/kind" "settlement" "s1"]])]
    (is (some #(re-find #"E and T must be str" %) vs))))

(deftest test-unknown-attribute
  (is (some #(re-find #"unknown attribute 'warifu/bogus'" %)
            (e/validate-facts [["s1" "warifu/bogus" "x" "s1"]]))))

(deftest test-wrong-value-type
  ;; amount_usdc expects int, got str
  (is (some #(re-find #"'warifu/amount_usdc' expects int" %)
            (e/validate-facts [["s1" "warifu/amount_usdc" "500" "s1"]]))))

(deftest test-invariant-violations
  ;; fee must be 0
  (is (some #(re-find #"'warifu/fee_usdc' invariant violated" %)
            (e/validate-facts [["s1" "warifu/fee_usdc" 5 "s1"]])))
  ;; finality must be T+0
  (is (some #(re-find #"'warifu/finality' invariant violated" %)
            (e/validate-facts [["s1" "warifu/finality" "T+2" "s1"]])))
  ;; kind must be a known kind
  (is (some #(re-find #"'warifu/kind' invariant violated" %)
            (e/validate-facts [["s1" "warifu/kind" "bogus" "s1"]])))
  ;; amount must be >= 0
  (is (some #(re-find #"'warifu/amount_usdc' invariant violated" %)
            (e/validate-facts [["s1" "warifu/amount_usdc" -1 "s1"]])))
  ;; status must be a known dispute status
  (is (some #(re-find #"'warifu/status' invariant violated" %)
            (e/validate-facts [["s1" "warifu/status" "bogus" "s1"]]))))

(deftest test-assert-valid-throws
  (is (thrown? clojure.lang.ExceptionInfo (e/assert-valid [["s1" "warifu/fee_usdc" 1 "s1"]])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"EAVT schema violations"
                        (e/assert-valid [["s1" "warifu/kind" "bogus" "s1"]]))))

(deftest test-sets
  (is (contains? e/kinds "auth_hold"))
  (is (contains? e/fundings "credit"))
  (is (contains? e/dispute-statuses "absorbed")))
