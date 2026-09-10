(ns warifu.cells.settle
  "warifu.settle — capture an authorized hold and settle on-chain (T+0, zero fee).

  1:1 port of `cells/settle.py`. kotoba-EAVT-native (ADR-2605262130). Substrate injected via
  SubstratePort. SettlementRouter transfers USDC holder/wakai-float → merchant smart account; gas
  is sponsored by etzhayyim-paymaster; merchant fee = 0. Zero fee + T+0 finality invariants.

  Conventions (refund/authorize/capture.cljc sibling house style):
    - @dataclass (settle's own) CaptureRequest / SettleResult → maps with kebab keyword keys;
      Python field defaults preserved (amount-usdc nil ⇒ full remaining, fee-usdc 0, finality \"T+0\")
    - the hold map read from the substrate keeps STRING keys verbatim
      (\"amount_usdc\"/\"captured_usdc\"/\"merchant_did\"/\"funding\") — kotoba payload keys
    - EAVT facts are [E A V T] vectors; attribute is the \"warifu/…\" string verbatim"
  (:require [warifu.cells.substrate :as substrate]))

(def tithe-eligible
  "Purposes that flow through the 10% TitheRouter auto-split (donation streams, not merchant
  sales). Python TITHE_ELIGIBLE frozenset — a module constant, not referenced by run (preserved
  for surface fidelity)."
  #{"donation" "kisha" "grant" "tithe"})

(defn make-settle-request
  "Construct settle's CaptureRequest map (`amount-usdc` nil ⇒ full authorized; `idempotency-key`
  defaults \"\"). Port of the @dataclass CaptureRequest local to settle.py."
  [auth-id & [{:keys [amount-usdc idempotency-key]
               :or {amount-usdc nil idempotency-key ""}}]]
  {:auth-id auth-id :amount-usdc amount-usdc :idempotency-key idempotency-key})

(defn- settle-result
  "Construct a SettleResult map. Port of the @dataclass SettleResult (defaults: settled false,
  fee-usdc 0 always, finality \"T+0\", eavt-facts [])."
  [{:keys [settled settlement-id tx fee-usdc finality reason eavt-facts]
    :or {settled false settlement-id nil tx nil fee-usdc 0
         finality "T+0" reason nil eavt-facts []}}]
  {:settled settled
   :settlement-id settlement-id
   :tx tx
   :fee-usdc fee-usdc
   :finality finality
   :reason reason
   :eavt-facts eavt-facts})

(defn run
  "Port of `SettleCell.run(req)`. Pure over the injected `subst`. Loads the hold, computes the
  remaining authorized amount, validates the requested amount, performs the on-chain transfer via
  SettlementRouter, writes the zero-fee T+0 EAVT settlement facts, and returns a SettleResult map.
  Mirrors the Python early-returns exactly."
  [subst req]
  (let [hold (substrate/load-hold subst (:auth-id req))]
    (if (nil? hold)
      (settle-result {:reason "auth_hold not found / not approved"})
      (let [remaining (- (get hold "amount_usdc") (get hold "captured_usdc" 0))
            req-amount (:amount-usdc req)
            amount (if (some? req-amount) req-amount remaining)]
        (if (or (<= amount 0) (> amount remaining))
          (settle-result {:reason "amount exceeds remaining authorized amount"})
          (let [[settlement-id tx] (substrate/settle-transfer
                                    subst {:merchant-did (get hold "merchant_did")
                                           :amount-usdc amount
                                           :funding (get hold "funding")
                                           :auth-id (:auth-id req)})
                facts [[settlement-id "warifu/kind" "settlement" settlement-id]
                       [settlement-id "warifu/auth_id" (:auth-id req) settlement-id]
                       [settlement-id "warifu/amount_usdc" amount settlement-id]
                       [settlement-id "warifu/merchant_did" (get hold "merchant_did") settlement-id]
                       [settlement-id "warifu/fee_usdc" 0 settlement-id]
                       [settlement-id "warifu/finality" "T+0" settlement-id]
                       [settlement-id "warifu/tx" tx settlement-id]]]
            (substrate/write-facts subst facts)
            (settle-result {:settled true :settlement-id settlement-id :tx tx
                            :fee-usdc 0 :eavt-facts facts})))))))

(defn settle
  "Port of `settle(req, substrate=None) -> SettleResult`. Defaults the substrate to the
  loud-failing UnwiredSubstrate sentinel when none is injected."
  ([req] (settle req (substrate/unwired-substrate)))
  ([req subst] (run subst req)))
