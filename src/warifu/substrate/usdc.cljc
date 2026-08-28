(ns warifu.substrate.usdc
  "warifu's REAL settlement substrate — USDC on Base L2, no @etzhayyim/sdk.

  warifu shipped at R0: `warifu.cells.substrate/UnwiredSubstrate` throws on
  every method, and the ns docstring said \"In production an
  `@etzhayyim/sdk`-backed adapter is injected (R1)\". That adapter never
  existed here, and the SDK it named is a TypeScript package frozen at its
  2026-07-01 commits while all six of its dependencies became Clojure
  (superproject ADR-2608281200). This namespace is the R1 adapter, built
  instead on this workspace's own portable primitives:

    erc20.core                        calldata + decoding, zero deps, dual-platform
    kotoba.lang.base-l2.rpc           eth_call over an injected ITransport
    kotoba.lang.base-l2.paymaster     ERC-4337 sponsored write (no key held here)

  WHAT THIS DOES AND DOES NOT REPLACE. `SubstratePort` has eleven methods and
  only three of them move value:

    usdc-balance        an ERC-20 balanceOf read
    settle-transfer     USDC to the merchant
    reverse-settlement  USDC back out, for a refund

  The other eight are a RECORD plane — cards, holds, captures, settlements,
  disputes, EAVT facts. Those are not a chain concern and this namespace does
  not invent a store for them. `UsdcSubstrate` is therefore a DECORATOR, in the
  same shape as `warifu.cells.guarded-substrate/GuardedSubstrate`: it wraps a
  record backend, delegates the eight record methods to it unchanged, and
  replaces the three value methods with real on-chain calls. Composing the two
  decorators gives you a guarded, real-money substrate:

    (-> records ->UsdcSubstrate-with-chain guarded/->GuardedSubstrate)

  NO KEY IS HELD HERE (ADR-2605231525). Writes go out as ERC-4337
  UserOperations through a caller-supplied `paymaster` bundle, and the
  UserOperation is signed inside the caller's `SmartAccount` (typically a
  WebAuthn passkey). This namespace only encodes calldata and reads receipts.

  ORDER OF OPERATIONS — CHAIN FIRST, RECORDS SECOND, deliberately. The port
  has no two-phase commit, so one of the two halves must go first and the
  choice decides which way a partial failure fails:

    records first  -> the ledger says a merchant was paid when no money moved,
                      and there is nothing on chain to reconcile against. A
                      false positive of payment.
    chain first    -> money moved and the ledger does not know it yet, but the
                      tx hash exists and is discoverable. A false negative,
                      recoverable by reconciliation.

  The second is strictly more recoverable, and it matches this workspace's
  stated position that the on-chain tx is the ledger of record and the local
  store is an index (cloud-itonami ADR-0018). So: transfer, then record. If
  the record write throws, this namespace rethrows with the orphaned tx hash
  under `:warifu/orphaned-tx` — the one fact reconciliation needs — rather
  than swallowing it.

  KNOWN GAP, REFUSED RATHER THAN GUESSED: `funding \"credit\"`. A credit-funded
  settlement draws a 0% CreditLine rather than moving the holder's own USDC, so
  reversing one repays a line AND unwinds whatever float fronted the money.
  Which account is that float, and whether it is repaid on chain or in the
  ledger, is a domain decision nobody has recorded. `settle-transfer` and
  `reverse-settlement` therefore THROW on credit funding instead of moving
  money on a guess. Debit is unambiguous and is implemented."
  (:require [warifu.cells.substrate :as substrate]
            [erc20.core :as erc20]
            [kotoba.lang.base-l2.rpc :as rpc]
            [kotoba.lang.base-l2.paymaster :as paymaster]))

;; ── chain config ─────────────────────────────────────────────────────

(def base-usdc
  "USDC on Base L2. Same address `treasury.core/chains` carries for \"base\";
  repeated here as a default so a caller can construct a chain map without
  taking a dependency on treasury, and overridable via `:usdc`."
  "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913")

(def base-rpc-default
  "Base L2 public JSON-RPC. `treasury.core/chains` lists fallbacks
  (1rpc.io / meowrpc / publicnode) for callers that need them."
  "https://mainnet.base.org")

(defn chain
  "Build the chain half of the substrate.

    :transport      REQUIRED. Satisfies `kotoba.lang.base-l2.rpc/ITransport`.
                    This library performs zero network I/O itself.
    :address-of     REQUIRED. `(fn [subject] \"0x…\" | nil)` resolving a warifu
                    account id or a merchant DID to an EVM address. Returning
                    nil FAILS THE CALL — see `resolve-address`.
    :bundle         REQUIRED for writes. The `paymaster` bundle
                    {:bundler :smart-account :paymaster-address :gas-overrides}.
    :refund-bundle  Optional. The bundle refunds are paid from; defaults to
                    `:bundle`. Distinct because a refund is paid by whatever
                    float fronted the settlement, which need not be the payer.
    :usdc           Token address, defaults to `base-usdc`.
    :rpc-url        defaults to `base-rpc-default`."
  [{:keys [transport address-of bundle refund-bundle usdc rpc-url]}]
  (when-not transport
    (throw (ex-info "warifu.substrate.usdc/chain requires a :transport (satisfies kotoba.lang.base-l2.rpc/ITransport)" {})))
  (when-not (fn? address-of)
    (throw (ex-info "warifu.substrate.usdc/chain requires an :address-of resolver — this substrate never guesses a payee address" {})))
  {:transport transport
   :address-of address-of
   :bundle bundle
   :refund-bundle (or refund-bundle bundle)
   :usdc (or usdc base-usdc)
   :rpc-url (or rpc-url base-rpc-default)})

;; ── guards ───────────────────────────────────────────────────────────

(defn resolve-address
  "`subject` (a warifu account id or a merchant DID) -> its EVM address.

  Throws when the resolver has no answer. This is the single most important
  refusal in this namespace: an unresolved payee must never fall back to a
  default, a zero address, or the subject string itself. Money sent to a
  guessed address is not recoverable."
  [chain subject kind]
  (let [a ((:address-of chain) subject)]
    (if (and (string? a) (re-matches #"0x[0-9a-fA-F]{40}" a))
      a
      (throw (ex-info (str "warifu.substrate.usdc: cannot resolve " (name kind)
                           " to an EVM address — refusing to move money to a guess")
                      {:warifu/unresolved kind :subject subject :resolved a})))))

(defn- assert-minor-units
  "`amount` must be a positive integer in USDC minor units (6 decimals), which
  is what every warifu cell passes. A decimal string or a float here would be
  a units bug that silently sends the wrong amount by a factor of 10^6."
  [amount]
  (when-not (and (integer? amount) (pos? amount))
    (throw (ex-info "warifu.substrate.usdc: amount must be a positive integer in USDC minor units"
                    {:amount amount})))
  amount)

(defn- assert-debit
  "Refuse credit-funded value movement — see the ns docstring's KNOWN GAP."
  [funding op]
  (when-not (= funding "debit")
    (throw (ex-info (str "warifu.substrate.usdc: " op " supports funding \"debit\" only; "
                         "credit-line settlement/refund needs a recorded float decision "
                         "and is refused rather than guessed")
                    {:warifu/unsupported-funding funding :op op})))
  funding)

(defn- dec-string->int
  "`erc20/decode-uint`'s decimal string -> an integer the cells can compare
  with `>=`. BigInteger under :clj, js/BigInt under :cljs — NOT a double.
  erc20 returns a string precisely because a uint256 does not fit one, and
  undoing that with `parse-long` would corrupt a large balance silently."
  [s]
  #?(:clj (biginteger s) :cljs (js/BigInt s)))

;; ── the three value-moving operations ────────────────────────────────

(defn balance-of
  "ERC-20 `balanceOf` for `account`, in USDC minor units."
  [chain account]
  (-> (rpc/eth-call (:transport chain) (:rpc-url chain) (:usdc chain)
                    (erc20/balance-of (resolve-address chain account :account)))
      erc20/decode-uint
      dec-string->int))

(defn transfer!
  "Send `amount` USDC minor units to `to-address` as a sponsored ERC-4337
  UserOperation. Returns the L2 tx hash.

  The calldata is built by `paymaster`/`abi` from the signature rather than by
  `erc20/transfer`. Both produce identical bytes — `calldata-agreement-test`
  asserts exactly that against each other — and going through `abi` here keeps
  the sponsored path on one encoder."
  [chain bundle to-address amount]
  (paymaster/sponsored-write-contract!
   {:address (:usdc chain)
    :function-signature "transfer(address,uint256)"
    :arg-types ["address" "uint256"]
    :arg-values [to-address (str amount)]
    :value 0}
   bundle))

(defn- with-orphan-guard
  "Run `record-fn` after money has already moved. If it throws, rethrow with
  the tx hash attached: the transfer is done and irreversible, and the hash is
  the only thing reconciliation can work from."
  [tx op record-fn]
  (try
    (record-fn)
    (catch #?(:clj Exception :cljs :default) e
      (throw (ex-info (str "warifu.substrate.usdc: " op " moved USDC on chain but the record "
                           "write failed — the transfer is NOT reversed. Reconcile from the tx.")
                      {:warifu/orphaned-tx tx :op op}
                      e)))))

(defn settle!
  "Transfer USDC to the merchant, then let `records` mint and persist the
  settlement. Returns `[settlement-id tx]` — the REAL tx hash, not whatever
  the record backend produced for its own bookkeeping."
  [chain records {:keys [merchant-did amount-usdc funding] :as opts}]
  (assert-minor-units amount-usdc)
  (assert-debit funding "settle-transfer")
  (let [to (resolve-address chain merchant-did :merchant)
        tx (transfer! chain (:bundle chain) to amount-usdc)
        [settlement-id _fake-tx]
        (with-orphan-guard tx "settle-transfer"
          #(substrate/settle-transfer records opts))]
    [settlement-id tx]))

(defn reverse!
  "Refund `amount` of `settlement-id` back to the holder, then let `records`
  mint the refund and do its bookkeeping. Returns `[refund-id tx]`.

  Reads the settlement through `records` first — the holder address and the
  funding kind are not in the caller's arguments, and refunding to the wrong
  party is exactly what `resolve-address` exists to prevent."
  [chain records settlement-id amount-usdc]
  (assert-minor-units amount-usdc)
  (let [s (or (substrate/load-settlement records settlement-id)
              (throw (ex-info "warifu.substrate.usdc: unknown settlement — refusing to refund"
                              {:settlement-id settlement-id})))
        _ (assert-debit (get s "funding") "reverse-settlement")
        to (resolve-address chain (get s "holder") :holder)
        tx (transfer! chain (:refund-bundle chain) to amount-usdc)
        [refund-id _fake-tx]
        (with-orphan-guard tx "reverse-settlement"
          #(substrate/reverse-settlement records settlement-id amount-usdc))]
    [refund-id tx]))

;; ── the decorator ────────────────────────────────────────────────────

(defrecord UsdcSubstrate [records chain]
  substrate/SubstratePort
  ;; ── record plane: delegated unchanged ──
  (resolve-card [_ card-token]       (substrate/resolve-card records card-token))
  (credit-available [_ account]      (substrate/credit-available records account))
  (place-hold [_ account opts]       (substrate/place-hold records account opts))
  (load-hold [_ auth-id]             (substrate/load-hold records auth-id))
  (record-capture [_ auth-id amt]    (substrate/record-capture records auth-id amt))
  (load-settlement [_ settlement-id] (substrate/load-settlement records settlement-id))
  (open-dispute [_ opts]             (substrate/open-dispute records opts))
  (write-facts [_ facts]             (substrate/write-facts records facts))
  ;; ── value plane: real USDC on Base ──
  (usdc-balance [_ account]          (balance-of chain account))
  (settle-transfer [_ opts]          (settle! chain records opts))
  (reverse-settlement [_ sid amt]    (reverse! chain records sid amt)))

(defn usdc-substrate
  "Wrap a record backend so its three value-moving methods go to real USDC on
  Base. `chain-config` is passed to `chain`."
  [records chain-config]
  (->UsdcSubstrate records (chain chain-config)))
