(ns warifu.cells.authorize
  "warifu.authorize — authorize a card transaction (debit hold / credit reserve).

  1:1 port of `cells/authorize.py`. Deterministic settlement logic; no LLM. kotoba-EAVT-native
  (ADR-2605262130). Substrate access is injected via SubstratePort (cells/substrate.cljc) — no
  platform key (ADR-2605231525). Zero fee (warifu/fee_usdc always 0). Phase 1 ships closed-loop:
  only the SBT↔SBT carve-out purposes + escrow-refund; external purchase/subscription are GATED
  until a Council Lv7+ amendment (ADR-2605192115) + vendor merchant-of-record (ADR-2605301036).

  Conventions (refund.cljc sibling house style):
    - @dataclass AuthRequest / AuthResult → maps with kebab keyword keys; Python field defaults
      preserved (surface \"rest\", auth-id nil, eavt-facts [])
    - Funding / Decision str-Enums → their value strings (\"debit\"/\"credit\";
      \"approve\"/\"decline\"/\"gated\") — the established enum→value-string convention
    - EAVT facts are [E A V T] vectors; the attribute is the \"warifu/…\" string verbatim
    - the cell is a pure fn over an injected substrate + a phase2-enabled flag"
  (:require [warifu.cells.substrate :as substrate]))

;; ── purpose allow-lists (ADR-2605192115 SBT↔SBT carve-out + escrow-refund) ──

(def phase1-purposes
  "Phase 1 charter-clean purposes (closed-loop). Python PHASE1_PURPOSES frozenset."
  #{"internal-purchase" "internal-subscription" "internal-promo" "escrow-refund"})

(def phase2-gated-purposes
  "Gated until Council Lv7+ amendment + vendor merchant-of-record. Python PHASE2_GATED_PURPOSES."
  #{"purchase" "subscription"})

;; ── Funding / Decision enums → value strings ──────────────────────

(def funding-debit "debit")
(def funding-credit "credit")

(def decision-approve "approve")   ; ISO 8583 RC 00
(def decision-decline "decline")   ; ISO 8583 RC 05
(def decision-gated "gated")       ; purpose constitutionally disabled (Phase 2)

;; ── AuthRequest / AuthResult (dataclass → map, kebab keys) ────────

(defn make-auth-request
  "Construct an AuthRequest map. Port of the @dataclass AuthRequest (`surface` defaults \"rest\").
  `funding` is the value string \"debit\"/\"credit\"."
  [{:keys [card-token amount-usdc funding purpose merchant-did idempotency-key surface]
    :or {surface "rest"}}]
  {:card-token card-token
   :amount-usdc amount-usdc
   :funding funding
   :purpose purpose
   :merchant-did merchant-did
   :idempotency-key idempotency-key
   :surface surface})

(defn- auth-result
  "Construct an AuthResult map. Port of the @dataclass AuthResult (defaults: auth-id nil,
  reason nil, eavt-facts [])."
  [{:keys [decision auth-id reason eavt-facts]
    :or {auth-id nil reason nil eavt-facts []}}]
  {:decision decision
   :auth-id auth-id
   :reason reason
   :eavt-facts eavt-facts})

;; ── purpose gate (1:1 of AuthorizeCell._purpose_ok) ───────────────

(defn- purpose-ok
  "Port of `_purpose_ok(purpose)`. Returns nil when the purpose is permitted; otherwise the
  blocking Decision value-string: \"gated\" (Phase-2 purpose, gate closed) or \"decline\"
  (unknown / prohibited purpose)."
  [purpose phase2-enabled]
  (cond
    (contains? phase1-purposes purpose) nil
    (contains? phase2-gated-purposes purpose) (if phase2-enabled nil decision-gated)
    :else decision-decline))

;; ── AuthorizeCell.run (1:1 port) ──────────────────────────────────

(defn run
  "Port of `AuthorizeCell.run(req)`. Pure over the injected `subst` (a SubstratePort) +
  `phase2-enabled`. Enforces the purpose allow-list, resolves the card token → account, checks
  debit balance / credit availability, places the on-chain hold, writes the zero-fee EAVT
  auth_hold facts, and returns an AuthResult map. Mirrors the Python early-returns exactly."
  [subst phase2-enabled req]
  (let [gate (purpose-ok (:purpose req) phase2-enabled)]
    (if (some? gate)
      (auth-result {:decision gate
                    :reason (if (= gate decision-gated)
                              (str "purpose constitutionally gated — Phase 2 requires Council Lv7+ "
                                   "amendment (ADR-2605192115) + vendor merchant-of-record (ADR-2605301036)")
                              (str "purpose '" (:purpose req) "' not permitted"))})
      (let [account (substrate/resolve-card subst (:card-token req))]
        (if (nil? account)
          (auth-result {:decision decision-decline :reason "card not found"})
          (let [debit? (= (:funding req) funding-debit)
                ok (if debit?
                     (>= (substrate/usdc-balance subst account) (:amount-usdc req))
                     (>= (substrate/credit-available subst account) (:amount-usdc req)))
                note (if debit? "debit balance hold" "credit reserve (0% qard hasan)")]
            (if-not ok
              (auth-result {:decision decision-decline :reason "insufficient funds/credit"})
              (let [auth-id (substrate/place-hold subst account
                                                  {:card-token (:card-token req)
                                                   :amount-usdc (:amount-usdc req)
                                                   :funding (:funding req)
                                                   :purpose (:purpose req)
                                                   :merchant-did (:merchant-did req)})
                    facts [[auth-id "warifu/kind" "auth_hold" auth-id]
                           [auth-id "warifu/card_token" (:card-token req) auth-id]
                           [auth-id "warifu/amount_usdc" (:amount-usdc req) auth-id]
                           [auth-id "warifu/funding" (:funding req) auth-id]
                           [auth-id "warifu/purpose" (:purpose req) auth-id]
                           [auth-id "warifu/merchant_did" (:merchant-did req) auth-id]
                           [auth-id "warifu/fee_usdc" 0 auth-id]
                           [auth-id "warifu/note" note auth-id]]]
                (substrate/write-facts subst facts)
                (auth-result {:decision decision-approve :auth-id auth-id :eavt-facts facts})))))))))

;; ── module-level entry point (1:1 port of `authorize(req, substrate, phase2_enabled)`) ──

(defn authorize
  "Port of `authorize(req, substrate=None, *, phase2_enabled=False) -> AuthResult`. Defaults the
  substrate to the loud-failing UnwiredSubstrate sentinel; phase2-enabled defaults false."
  ([req] (authorize req (substrate/unwired-substrate) false))
  ([req subst] (authorize req subst false))
  ([req subst phase2-enabled] (run subst phase2-enabled req)))
