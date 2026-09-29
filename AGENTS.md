# warifu 割符 — CLAUDE (actor instructions)

Open-source, **zero-merchant-fee** card payment service (credit + debit), **API/wire-compatible**
with the existing card ecosystem. Settles on-chain (USDC on Base L2 + ERC-4337). ADR-2605302000.

## Gates / prohibitions (constitutional — do NOT weaken)

- **Zero fee is structural**: never introduce a merchant discount rate, interchange, or network
  assessment. Real costs (gas, fraud) are socialised via `etzhayyim-paymaster`/Public Fund and
  `wakai` mutual aid — never per-transaction extraction.
- **Credit is interest-free (riba-free / qard ḥasan)**: `creditInterestBps = 0`. No profit-bearing
  late fees. Default handling = L3 評価 penalty + wakai absorption only.
- **Payment-purpose invariant (ADR-2605192115 §3)**: Phase 1 ships **closed-loop** — only the
  SBT↔SBT carve-out purposes (`internal-purchase`/`internal-subscription`/`internal-promo`) plus
  `escrow-refund`. **External `purchase`/`subscription` is PROHIBITED** until BOTH (a) a Council
  **Lv7+ unanimity** amendment of ADR-2605192115 and (b) routing through the commercial vendor arm
  (ADR-2605301036) as merchant-of-record. `SettlementRouter` enforces a purpose allow-list; do not
  bypass it.
- **No platform-held signing key** (ADR-2605231525): cardholder signing is WebAuthn-passkey /
  smart-account only. No server master credential in Workers / pods / CronJobs / CI.
- **Substrate**: ledger = `kotoba` EAVT (ADR-2605262130). No RisingWave / Postgres / Lance /
  projection layer. Substrate access only via `@etzhayyim/sdk`.
- **Inference**: Murakumo-only (ADR-2605215000) — no commercial GPU.
- **No raw PAN**: network tokenization only (self TSP). Card metadata under
  `com.etzhayyim.encrypted.*` (ADR-2605181100).
- **No third-party processors**: no Stripe/PayPal/Square/Visa/MC integration. We re-implement an
  *interoperable* open spec; we do not import or route through commercial card rails.

## TIGHT pairs

- `wakai` 和会 (ADR-2605263500) — credit float + fraud/chargeback loss mutualisation (NOT insurance).
- `toritate` 執帳 (ADR-2605262900) — 100% on-chain accounting + audit.
- `chigiri` 契 (ADR-2605262700) — dispute / chargeback legal procedure (UPL-prohibited substrate).

## Layout

```
orgs/etzhayyim/com-etzhayyim-warifu/
├── manifest.edn                    # canonical actor metadata + economics + purpose allow-list
├── adr.md                          # mirror/pointer to 90-docs/adr/2605302000
├── cells/                          # kotoba-EAVT-native cells (cljc; Python pruned per ADR-2606160842)
│   ├── authorize.cljc              # auth (debit hold / credit reserve) -> EAVT auth_hold
│   ├── capture.cljc / settle.cljc / refund.cljc / dispute.cljc
│   ├── substrate.cljc              # SubstratePort DI seam + UnwiredSubstrate sentinel
│   ├── eavt_schema.cljc / guarded_substrate.cljc  # write contract + fail-closed guard
│   └── lex/                        # per-cell lexicons (cell I/O contracts)
│                                   # implementation is under src/warifu
├── test/warifu/                    # tests; run bb test
└── wire/                           # external JSON compatibility projections only
└── lex/README.md                   # points to 10-protocol/warifu/ com.etzhayyim.card.* lexicons
```

Companion layers: `10-protocol/warifu/` (lexicons), `50-infra/warifu-contracts/` (Solidity),
compat gateways (`*-stripe-compat` / `iso8583-gateway` / `hce-tsp`).

## Roadmap

- **R0 (this scaffold)**: lexicons + kotoba schema + `WarifuCard`/`SettlementRouter` scaffold +
  Stripe-shaped REST (debit, closed-loop). `authorize` + `settle` cells.
- **R1**: `CreditLine` (0%) + wakai underwrite + HCE/TSP (NFC) + `capture`/`refund` cells.
- **R2**: `iso8583-gateway` (terminal) + `dispute` flow (chigiri).
- **R3 (GATED)**: external open-loop bridge (requires ADR-2605192115 Lv7+ amendment).
