(ns warifu.cells.refund
  "warifu.refund — reverse a settled transaction (purpose always escrow-refund).

  1:1 port of `cells/refund.py` (replacing the broken stage-0 port-failed stub).
  kotoba-EAVT-native (ADR-2605262130). Substrate injected via SubstratePort.
  Reverses USDC from merchant back to holder (debit) or repays the 0% CreditLine
  (credit). Zero fee — refunds move utility ONE-WAY (Charter §2 escrow-refund
  carve-out). NO Stripe/PayPal/fiat: settlement is USDC on Base L2 + ERC-4337
  via the injected substrate; the cell never touches money or a held key
  (ADR-2605231525).

  Conventions (funadaiku sea_trial cell.cljc + shionome regime_observer +
  mimamori/methods/bond.cljc house style):
    - @dataclass RefundRequest / RefundResult → plain maps with kebab keyword
      keys; Python field defaults preserved (amount-usdc nil ⇒ full refundable,
      idempotency-key \"\", fee-usdc 0)
    - the settlement map read from the substrate keeps STRING keys verbatim
      (\"amount_usdc\"/\"refunded_usdc\") — they are kotoba payload keys
    - EAVT facts are [E A V T] vectors; the attribute is the \"warifu/…\" string
      verbatim (kotoba write contract, cells/eavt_schema.py)
    - the refund cell is a pure fn over an injected substrate; the charter
      escrow-refund purpose is the only permitted refund purpose (closed vocab)"
  (:require [warifu.cells.substrate :as substrate]))

;; ── constants ─────────────────────────────────────────────────────

(def refund-purpose
  "The ONLY permitted refund purpose (charter escrow-refund carve-out).
  Python `REFUND_PURPOSE = \"escrow-refund\"`."
  "escrow-refund")

;; ── RefundRequest / RefundResult (dataclass → map, kebab keys) ────

(defn make-refund-request
  "Construct a RefundRequest map. Port of the @dataclass RefundRequest.
  `:amount-usdc` nil ⇒ full refundable; `:idempotency-key` defaults \"\".
  Trailing opts map carries the defaulted fields."
  [settlement-id & [{:keys [amount-usdc idempotency-key reason]
                     :or {amount-usdc nil idempotency-key "" reason nil}}]]
  {:settlement-id settlement-id
   :amount-usdc amount-usdc
   :idempotency-key idempotency-key
   :reason reason})

(defn- refund-result
  "Construct a RefundResult map. Port of the @dataclass RefundResult — all
  fields with their Python defaults (refunded false, fee-usdc 0 always,
  eavt-facts [])."
  [& [{:keys [refunded refund-id amount-usdc tx fee-usdc reason eavt-facts]
       :or {refunded false refund-id nil amount-usdc 0 tx nil
            fee-usdc 0 reason nil eavt-facts []}}]]
  {:refunded refunded
   :refund-id refund-id
   :amount-usdc amount-usdc
   :tx tx
   :fee-usdc fee-usdc
   :reason reason
   :eavt-facts eavt-facts})

;; ── RefundCell.run (1:1 port) ─────────────────────────────────────

(defn run
  "Port of `RefundCell.run(req)`. Pure over the injected `subst` (a
  SubstratePort). Loads the settlement, computes the refundable remainder,
  validates the requested amount, reverses it on the substrate, writes the
  EAVT facts (zero-fee, escrow-refund purpose), and returns a RefundResult map.

  Mirrors the Python early-returns exactly:
    settlement not found        → {:refunded false :reason \"settlement not found\"}
    refundable ≤ 0              → {:refunded false :reason \"already fully refunded\"}
    amount ≤ 0 or > refundable  → {:refunded false :reason \"refund exceeds refundable amount\"}"
  [subst req]
  (let [settlement-id (:settlement-id req)
        s (substrate/load-settlement subst settlement-id)]
    (if (nil? s)
      (refund-result {:reason "settlement not found"})
      (let [refundable (- (get s "amount_usdc")
                          (get s "refunded_usdc" 0))]
        (if (<= refundable 0)
          (refund-result {:reason "already fully refunded"})
          (let [req-amount (:amount-usdc req)
                amount (if (some? req-amount) req-amount refundable)]
            (if (or (<= amount 0) (> amount refundable))
              (refund-result {:reason "refund exceeds refundable amount"})
              (let [[refund-id tx] (substrate/reverse-settlement
                                    subst settlement-id amount)
                    facts [[refund-id "warifu/kind" "refund" refund-id]
                           [refund-id "warifu/settlement_id" settlement-id refund-id]
                           [refund-id "warifu/amount_usdc" amount refund-id]
                           [refund-id "warifu/purpose" refund-purpose refund-id]
                           [refund-id "warifu/fee_usdc" 0 refund-id]
                           [refund-id "warifu/tx" tx refund-id]]]
                (substrate/write-facts subst facts)
                (refund-result {:refunded true
                                :refund-id refund-id
                                :amount-usdc amount
                                :tx tx
                                :eavt-facts facts})))))))))

;; ── module-level entry point (1:1 port of `refund(req, substrate)`) ──

(defn refund
  "Port of `refund(req, substrate=None) -> RefundResult`. Defaults the
  substrate to the loud-failing UnwiredSubstrate sentinel when none is
  injected (Python `RefundCell(substrate).run(req)` with
  `substrate or UnwiredSubstrate()`)."
  ([req] (refund req (substrate/unwired-substrate)))
  ([req subst] (run subst req)))
