(ns warifu.cells.eavt-schema
  "warifu EAVT datom schema — the kotoba write contract for the `warifu/*` namespace.

  1:1 port of `cells/eavt_schema.py` (ADR-2605302000 / ADR-2605262130). Cells emit facts as
  [E A V T] vectors; this module is the single definition of which attributes exist and what value
  types / invariants they carry. The real @etzhayyim/sdk kotoba adapter MUST call assert-valid
  before writing, so malformed or fee-leaking datoms can never reach the QuadStore.

  Entity classes (warifu/kind): auth_hold | capture | settlement | refund | dispute.
  Python AssertionError → (throw (ex-info ...)). A fact's value type is :str (string?) or :int
  (integer?); the optional invariant predicate enforces e.g. the zero-fee + T+0 invariants."
  (:require [kotoba.lang.text :as str]))

(def kinds #{"auth_hold" "capture" "settlement" "refund" "dispute"})
(def fundings #{"debit" "credit"})
(def dispute-statuses #{"open" "evidence" "chigiri" "resolved" "absorbed"})

;; attribute → [value-type-kind invariant-predicate-or-nil]. value-type-kind ∈ :str | :int.
(def attrs
  {"warifu/kind"          [:str #(contains? kinds %)]
   "warifu/card_token"    [:str nil]
   "warifu/amount_usdc"   [:int #(>= % 0)]
   "warifu/remaining_usdc" [:int #(>= % 0)]
   "warifu/funding"       [:str #(contains? fundings %)]
   "warifu/purpose"       [:str nil]
   "warifu/merchant_did"  [:str nil]
   "warifu/fee_usdc"      [:int #(= % 0)]            ; 決済手数料ゼロ invariant
   "warifu/note"          [:str nil]
   "warifu/auth_id"       [:str nil]
   "warifu/settlement_id" [:str nil]
   "warifu/finality"      [:str #(= % "T+0")]         ; T+0 final invariant
   "warifu/tx"            [:str nil]
   "warifu/reason_code"   [:str nil]
   "warifu/opened_by"     [:str nil]
   "warifu/status"        [:str #(contains? dispute-statuses %)]
   "warifu/evidence_cid"  [:str nil]})

(defn- type-ok? [kind v]
  (case kind :str (string? v) :int (integer? v)))

(defn- type-name [v]
  (cond (string? v) "str" (integer? v) "int" (boolean? v) "bool" (nil? v) "NoneType"
        :else (.getName (class v))))

(defn validate-facts
  "Return a vector of violation strings (empty == all facts conform). Port of validate_facts:
  each fact must be a 4-tuple [E A V T]; E and T must be strings; A must be a known attribute;
  V must match the attribute's value type and satisfy its invariant predicate."
  [facts]
  (vec
   (mapcat
    (fn [i fact]
      (if-not (and (vector? fact) (= 4 (count fact)))
        [(str "[" i "] not a 4-tuple (E,A,V,T): " (pr-str fact))]
        (let [[e a v t] fact
              base (when (or (not (string? e)) (not (string? t)))
                     [(str "[" i "] E and T must be str (entity id / tx): " (pr-str fact))])
              spec (get attrs a)]
          (if (nil? spec)
            (concat base [(str "[" i "] unknown attribute '" a "'")])
            (let [[kind pred] spec]
              (cond
                (not (type-ok? kind v))
                (concat base [(str "[" i "] '" a "' expects " (name kind) ", got " (type-name v) "=" (pr-str v))])
                (and pred (not (pred v)))
                (concat base [(str "[" i "] '" a "' invariant violated: value=" (pr-str v))])
                :else base))))))
    (range) facts)))

(defn assert-valid
  "Raise (ex-info) if any fact violates the schema. Use before a kotoba write. Port of
  assert_valid (Python AssertionError)."
  [facts]
  (let [violations (validate-facts facts)]
    (when (seq violations)
      (throw (ex-info (str "EAVT schema violations:\n  " (str/join "\n  " violations))
                      {:warifu/schema-violations violations})))))
