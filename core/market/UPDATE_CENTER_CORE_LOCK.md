# SaiETF Market Update Center Core Lock

Lock version: **MARKET-CORE-1**
Frozen at SaiETF **V1.1.18**
Frozen commit: `606a67cb3cec560f7a789ef870e4418f0e4e131d`

The market/update-center orchestration core is now treated as immutable infrastructure during the UI recovery line. UI, chart, ETF component and page-layout work must consume this core through its existing contracts instead of changing its arbitration/state rules.

## Frozen core files

- `MarketDataCenter.kt`
- `MarketArbitrator.kt`
- `MarketDataProviderV2.kt`
- `MarketModels.kt`
- `MemoryMarketStore.kt`
- `ProviderCircuitBreaker.kt`

## Frozen behavior

1. A single MarketDataCenter remains the selected-quote SSOT.
2. Streaming/polling providers enter through the existing provider contract.
3. Newer-session / newer-timestamp / sequence arbitration must not regress.
4. Stale or older fallback data must not overwrite fresher accepted data.
5. Fugle-capable streaming remains higher priority when fresh; TWSE MIS / Yahoo remain adapters/fallbacks according to current provider logic.
6. Provider circuit-breaker state, recovery and failure accounting remain intact.
7. Memory StateFlow remains the hot runtime source; Room remains persistence/cache rather than tick-level SSOT.
8. UI refactors must not add a second quote-selection or P&L price path.

## Outside the lock

Provider adapters, credentials, transport/retry implementation, UI rendering, chart rendering, ETF component sources and diagnostics may continue to evolve **without changing the frozen core contracts above**.

## Change procedure

An intentional change to a frozen file requires:
- an explicit unlock decision,
- a new lock version,
- a documented reason,
- updated expected blob hashes,
- all `:core:market:test` tests passing,
- a new APK and upgrade gate.

Silent edits to the frozen core are CI-blocking.
