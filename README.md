# warifu 割符

Standalone actor repository for guarded zero-fee card authorization,
settlement, refunds, and disputes.

Canonical actor metadata and owned contracts are EDN (`manifest.edn`,
`lex/*.edn`). External compatibility contracts are isolated under
`wire/lex/*.json`. Runtime code lives under `src/warifu`, tests under `test/warifu`.

## Running the tests

```bash
clojure -M:test   # canonical — 64 tests / 225 assertions
bb test           # dependency-free subset — 51 tests / 195 assertions
```

`bb test` is a **subset**, not the suite: it cannot load the USDC adapter's
suite, because babashka cannot load `org.clojure/data.json`, which
`kotoba-lang/base-l2`'s JSON-RPC envelopes need. The JVM runner discovers test
namespaces by regex, so a new test file is picked up there automatically;
`run_tests.clj`'s list is hand-maintained and can go stale.

(Until 2026-08-28 `clojure -M:test` ran **3** of the 51 tests. warifu's suites
are named `test-…` with the prefix, and cognitect-test-runner's default
discovery regex is `.*-test$` with the suffix, so it found only
`warifu.repository-contract-test` and silently skipped the rest. `-r ".*"` in
`deps.edn` is what fixes it.)

## Settlement

The cells never touch money or hold a key: they call a `SubstratePort`
(`warifu.cells.substrate`), and every value-moving method is behind it.

`warifu.substrate.usdc` is the real one — **USDC on Base L2**, built on
`kotoba-lang/erc20` (calldata, zero deps, dual-platform) and
`kotoba-lang/base-l2` (`eth_call` over an injected transport, ERC-4337
sponsored writes). No key is held here: writes go out as UserOperations signed
inside a caller-supplied `SmartAccount`.

It is a **decorator**, in the same shape as `GuardedSubstrate`. Eight of the
eleven port methods are a record plane — cards, holds, captures, settlements,
disputes, EAVT facts — and are delegated to a record backend unchanged. Only
three move value, and only those are replaced:

```clojure
(-> records
    (usdc/usdc-substrate {:transport … :address-of … :bundle …})
    guarded/->GuardedSubstrate)
```

Two refusals are load-bearing and are tested by deleting them:

- **an unresolved payee is never guessed.** `:address-of` returning nil or a
  non-address fails the call. Money sent to a fallback address is not
  recoverable.
- **`funding "credit"` is refused, not approximated.** Reversing a
  credit-funded settlement repays a 0% line *and* unwinds whatever float
  fronted the money; which account that float is has never been recorded.
  Debit is unambiguous and is implemented.

Order of operations is **chain first, records second**, deliberately: a
records-first failure claims a merchant was paid when no money moved and
leaves nothing on chain to reconcile against, while a chain-first failure
leaves a real tx hash. On a record-write failure the adapter rethrows with
`:warifu/orphaned-tx`.
