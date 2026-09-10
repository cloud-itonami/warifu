(ns warifu.cells.substrate
  "warifu substrate port — the DI seam between cells and a real substrate.

  1:1 port of `cells/substrate.py` (the subset the refund cell needs + the
  loud-failing default). Cells depend on the
  `SubstratePort` protocol, never on a concrete client. In production an
  real adapter is injected; tests inject an in-memory fake.

  THE REAL ADAPTER IS `warifu.substrate.usdc` (added 2026-08-28). It is a
  decorator: it wraps a record backend, delegates the eight record methods to
  it unchanged, and replaces the three that move value (`usdc-balance`,
  `settle-transfer`, `reverse-settlement`) with USDC on Base L2 via
  kotoba-lang/erc20 + kotoba-lang/base-l2. This docstring used to name
  `@etzhayyim/sdk` as that adapter; that adapter never existed here, and the
  package it named is TypeScript frozen at its 2026-07-01 commits while all
  six of its dependencies became Clojure (superproject ADR-2608281200). Per ADR-2605231525 no platform key is held; money never lives in the
  cells — `reverse_settlement` emits ERC-4337 UserOps via the adapter.

  Conventions (yobel/ports.cljc + mimamori/methods/bond.cljc house style):
    - SubstratePort as a Clojure defprotocol (the Python `Protocol`)
    - UnwiredSubstrate as a defrecord whose every method throws ex-info — the
      sentinel that fails loudly so a forgotten injection never silently
      settles money (Python `NotImplementedError`)
    - settlement/refund maps use STRING keys verbatim from the Python payload
      (\"amount_usdc\"/\"refunded_usdc\"/\"funding\"/…) — not kebab keywords —
      because they are kotoba payload dict keys, not Clojure structural keys
    - EAVT facts are [E A V T] vectors (kotoba write contract), A is the
      \"warifu/…\" string attribute verbatim")

;; ── SubstratePort (Python Protocol → Clojure protocol) ────────────
;;
;; Grown incrementally as cells are ported: the refund subset
;; (load_settlement/reverse_settlement/write_facts) plus the authorize subset
;; (resolve_card/usdc_balance/credit_available/place_hold). The remaining Python
;; Protocol surface (load_hold/record_capture/settle_transfer/open_dispute) is
;; added when capture/settle/dispute are ported.

(defprotocol SubstratePort
  "The DI seam warifu cells call into (the refund + authorize subset of cells/substrate.py)."
  ;; --- identity / balances (authorize) ---
  (resolve-card [this card-token]
    "Resolve a network card token → holder account, or nil if absent.
     (Python `resolve_card(card_token) -> Optional[str]`.)")
  (usdc-balance [this account]
    "Return the account's USDC minor-unit balance (0 if unknown).
     (Python `usdc_balance(account) -> int`.)")
  (credit-available [this account]
    "Return the account's available 0% CreditLine (0 if unknown).
     (Python `credit_available(account) -> int`.)")
  (place-hold [this account opts]
    "Place an escrow hold; return the auth-id. `opts` carries the Python keyword-only
     args as a map {:card-token :amount-usdc :funding :purpose :merchant-did}.
     (Python `place_hold(account, *, card_token, amount_usdc, funding, purpose, merchant_did) -> str`.)")
  ;; --- holds / captures (capture) ---
  (load-hold [this auth-id]
    "Return the auth_hold map for `auth-id`, or nil if absent.
     (Python `load_hold(auth_id) -> Optional[dict]`.)")
  (record-capture [this auth-id amount-usdc]
    "Record a (full/partial) capture against a hold; return the capture-id.
     (Python `record_capture(auth_id, amount_usdc) -> str`.)")
  ;; --- settlement (settle) ---
  (settle-transfer [this opts]
    "Transfer USDC holder/wakai-float → merchant; return [settlement-id tx]. `opts` carries the
     Python keyword-only args as a map {:merchant-did :amount-usdc :funding :auth-id}.
     (Python `settle_transfer(*, merchant_did, amount_usdc, funding, auth_id) -> (str, str)`.)")
  ;; --- holds / settlements (refund) ---
  (load-settlement [this settlement-id]
    "Return the settlement map for `settlement-id`, or nil if absent.
     (Python `load_settlement(settlement_id) -> Optional[dict]`.)")
  (reverse-settlement [this settlement-id amount-usdc]
    "Reverse `amount-usdc` of a settlement; return [refund-id tx].
     (Python `reverse_settlement(settlement_id, amount_usdc) -> (str, str)`.)")
  ;; --- disputes (dispute) ---
  (open-dispute [this opts]
    "Open a chargeback dispute; return the dispute-id. `opts` carries the Python keyword-only
     args as a map {:settlement-id :reason-code :opened-by-did :amount-usdc :evidence-cids}.
     (Python `open_dispute(*, settlement_id, reason_code, opened_by_did, amount_usdc, evidence_cids) -> str`.)")
  (write-facts [this facts]
    "Append EAVT facts (a seq of [E A V T] vectors) to the ledger.
     (Python `write_facts(facts) -> None`.)"))

;; ── UnwiredSubstrate (loud-failing default sentinel) ──────────────

(defn- unwired-fail
  "The forgotten-injection guard: fail loudly rather than silently not moving money."
  [op]
  (throw (ex-info
          (str "warifu: substrate '" op "' not wired — inject "
               "warifu.substrate.usdc/usdc-substrate (real USDC on Base) "
               "or an in-memory fake")
          {:warifu/unwired-substrate true :op op})))

(defrecord UnwiredSubstrate []
  SubstratePort
  (resolve-card [_ _]         (unwired-fail "resolve_card"))
  (usdc-balance [_ _]         (unwired-fail "usdc_balance"))
  (credit-available [_ _]     (unwired-fail "credit_available"))
  (place-hold [_ _ _]         (unwired-fail "place_hold"))
  (load-hold [_ _]            (unwired-fail "load_hold"))
  (record-capture [_ _ _]     (unwired-fail "record_capture"))
  (settle-transfer [_ _]      (unwired-fail "settle_transfer"))
  (load-settlement [_ _]      (unwired-fail "load_settlement"))
  (reverse-settlement [_ _ _] (unwired-fail "reverse_settlement"))
  (open-dispute [_ _]         (unwired-fail "open_dispute"))
  (write-facts [_ _]          (unwired-fail "write_facts")))

(defn unwired-substrate
  "Construct the default loud-failing UnwiredSubstrate sentinel."
  []
  (->UnwiredSubstrate))
