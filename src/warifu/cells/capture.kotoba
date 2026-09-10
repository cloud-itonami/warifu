(ns warifu.cells.capture
  "warifu.capture — full/partial capture of an approved hold.

  1:1 port of `cells/capture.py`. kotoba-EAVT-native (ADR-2605262130). Substrate injected via
  SubstratePort. Converts an approved `auth_hold` into a `capture` entity (full or partial); the
  `settle` cell performs the on-chain USDC transfer. Zero fee (warifu/fee_usdc always 0).

  Conventions (refund.cljc / authorize.cljc sibling house style):
    - @dataclass CaptureRequest / CaptureResult → maps with kebab keyword keys; Python field
      defaults preserved (amount-usdc nil ⇒ full remaining, idempotency-key \"\", fee-usdc 0)
    - the hold map read from the substrate keeps STRING keys verbatim
      (\"amount_usdc\"/\"captured_usdc\") — kotoba payload keys
    - EAVT facts are [E A V T] vectors; attribute is the \"warifu/…\" string verbatim"
  (:require [warifu.cells.substrate :as substrate]))

(defn make-capture-request
  "Construct a CaptureRequest map. Port of the @dataclass CaptureRequest (`amount-usdc` nil ⇒ full
  remaining; `idempotency-key` defaults \"\")."
  [auth-id & [{:keys [amount-usdc idempotency-key]
               :or {amount-usdc nil idempotency-key ""}}]]
  {:auth-id auth-id :amount-usdc amount-usdc :idempotency-key idempotency-key})

(defn- capture-result
  "Construct a CaptureResult map. Port of the @dataclass CaptureResult (defaults: captured false,
  amount-usdc 0, remaining-usdc 0, fee-usdc 0 always, eavt-facts [])."
  [{:keys [captured capture-id amount-usdc remaining-usdc fee-usdc reason eavt-facts]
    :or {captured false capture-id nil amount-usdc 0 remaining-usdc 0
         fee-usdc 0 reason nil eavt-facts []}}]
  {:captured captured
   :capture-id capture-id
   :amount-usdc amount-usdc
   :remaining-usdc remaining-usdc
   :fee-usdc fee-usdc
   :reason reason
   :eavt-facts eavt-facts})

(defn run
  "Port of `CaptureCell.run(req)`. Pure over the injected `subst`. Loads the hold, computes the
  remaining authorized amount, validates the requested capture, records it, writes the zero-fee
  EAVT capture facts, and returns a CaptureResult map. Mirrors the Python early-returns exactly."
  [subst req]
  (let [hold (substrate/load-hold subst (:auth-id req))]
    (if (nil? hold)
      (capture-result {:reason "auth_hold not found / not approved"})
      (let [remaining (- (get hold "amount_usdc") (get hold "captured_usdc" 0))]
        (if (<= remaining 0)
          (capture-result {:reason "hold already fully captured"})
          (let [req-amount (:amount-usdc req)
                amount (if (some? req-amount) req-amount remaining)]
            (if (or (<= amount 0) (> amount remaining))
              (capture-result {:reason "capture exceeds remaining authorized amount"})
              (let [capture-id (substrate/record-capture subst (:auth-id req) amount)
                    new-remaining (- remaining amount)
                    facts [[capture-id "warifu/kind" "capture" capture-id]
                           [capture-id "warifu/auth_id" (:auth-id req) capture-id]
                           [capture-id "warifu/amount_usdc" amount capture-id]
                           [capture-id "warifu/remaining_usdc" new-remaining capture-id]
                           [capture-id "warifu/fee_usdc" 0 capture-id]]]
                (substrate/write-facts subst facts)
                (capture-result {:captured true
                                 :capture-id capture-id
                                 :amount-usdc amount
                                 :remaining-usdc new-remaining
                                 :eavt-facts facts})))))))))

(defn capture
  "Port of `capture(req, substrate=None) -> CaptureResult`. Defaults the substrate to the
  loud-failing UnwiredSubstrate sentinel when none is injected."
  ([req] (capture req (substrate/unwired-substrate)))
  ([req subst] (run subst req)))
