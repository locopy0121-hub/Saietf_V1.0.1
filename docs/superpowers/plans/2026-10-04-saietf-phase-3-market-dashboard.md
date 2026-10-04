# SaiETF Phase 3 Market Center Dashboard and Market Wall Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide resilient Taiwan equity quotes, a portfolio dashboard with an embedded market wall, instrument details, and charts that clearly disclose freshness and source state.

**Architecture:** Normalize every provider behind `MarketSource`, select quotes centrally with session-aware freshness rules, cache selected values in Room, and expose the same selected quote stream to dashboard, charts, Widget, and overlay consumers.

**Tech Stack:** Kotlin, Coroutines/Flow, kotlinx.serialization, OkHttp, Room, Jetpack Compose, Canvas, JUnit, MockWebServer, Compose UI Test.

**Spec:** `docs/superpowers/specs/2026-10-04-saietf-android-design.md`

## Global Constraints

- First-party/public TWSE and TPEx endpoints are the default sources; optional credentialed providers are isolated adapters.
- Network tests use committed fixtures and MockWebServer, never live endpoints.
- A prior Taipei trading-session quote cannot be selected as live merely because it has more fields.
- Every quote shown to a user includes selected source, exchange time, received time, and live/delayed/stale/unavailable status.
- Chinese instrument name or short name is primary; ticker remains visible as a secondary identifier.

## Review Focus

- Validate source failover, clock skew, market breaks, holidays, after-hours final values, and next-session invalidation.
- Confirm dashboard, detail page, Widget-facing repository, and chart header consume one selected quote revision.
- Confirm no “即時” label is shown when data is delayed or stale.
- Confirm market-wall mode and order persist independently of the underlying portfolio order.

---

### Task 1: Define Quote Contracts and the Session-Aware Market Center

**Files:**
- Create: `core/market/src/main/kotlin/tw/saietf/core/market/MarketSource.kt`
- Create: `core/market/src/main/kotlin/tw/saietf/core/market/MarketModels.kt`
- Create: `core/market/src/main/kotlin/tw/saietf/core/market/MarketCalendar.kt`
- Create: `core/market/src/main/kotlin/tw/saietf/core/market/FreshnessPolicy.kt`
- Create: `core/market/src/main/kotlin/tw/saietf/core/market/MarketCenter.kt`
- Create: `core/market/src/test/kotlin/tw/saietf/core/market/MarketCenterTest.kt`

**Interfaces:**
- `interface MarketSource { val id: String; suspend fun fetch(request: QuoteRequest): SourceQuoteBatch }`
- `interface MarketCenter { suspend fun refresh(symbols: Set<TwSymbol>): RefreshResult; fun observe(symbol: TwSymbol): Flow<NormalizedQuote> }`
- `NormalizedQuote` includes price, change, changePercent, volume, session date, exchange timestamp, received timestamp, source ID, source revision, and `QuoteStatus`.

- [ ] **Step 1: Write failing tests for live selection, delayed fallback, stale rejection, market break, after-hours close, next-day reset, clock skew, and unavailable state.**
- [ ] **Step 2: Run `./gradlew :core:market:test --tests '*MarketCenterTest'` and verify failures.**
- [ ] **Step 3: Implement immutable models, Taiwan session calendar abstraction, freshness scoring, deterministic source precedence, and diagnostics.**
- [ ] **Step 4: Run `./gradlew :core:market:test --tests '*MarketCenterTest'` and verify every session case passes with a fake clock.**
- [ ] **Step 5: Commit with message `feat: add session-aware market center`.**

### Task 2: Implement TWSE, TPEx, and Optional Real-Time Adapters

**Files:**
- Create: `core/market/src/main/kotlin/tw/saietf/core/market/source/TwseMarketSource.kt`
- Create: `core/market/src/main/kotlin/tw/saietf/core/market/source/TpexMarketSource.kt`
- Create: `core/market/src/main/kotlin/tw/saietf/core/market/source/CredentialedRealtimeSource.kt`
- Create: `core/market/src/test/resources/market/twse/*.json`
- Create: `core/market/src/test/resources/market/tpex/*.json`
- Create: `core/market/src/test/kotlin/tw/saietf/core/market/source/MarketSourceContractTest.kt`

**Interfaces:**
- Each adapter maps provider fields to `SourceQuoteBatch` and returns typed partial/failure diagnostics instead of inventing values.
- `CredentialedRealtimeSource` receives credentials through runtime configuration and is disabled when absent.

- [ ] **Step 1: Write a shared failing contract suite for normal, missing-field, suspended, malformed, rate-limited, timeout, and partial-batch fixtures.**
- [ ] **Step 2: Run `./gradlew :core:market:test --tests '*MarketSourceContractTest'` and verify adapters are missing.**
- [ ] **Step 3: Implement provider parsing, symbol batching, bounded retry/backoff, rate-limit handling, and redacted diagnostics.**
- [ ] **Step 4: Run all adapter tests offline and verify no test opens a public network connection.**
- [ ] **Step 5: Commit with message `feat: add Taiwan market source adapters`.**

### Task 3: Persist Selected Quotes and Intraday Series

**Files:**
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/entity/SelectedQuoteEntity.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/entity/IntradayPointEntity.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/dao/MarketDao.kt`
- Create: `core/market/src/main/kotlin/tw/saietf/core/market/SelectedQuoteRepository.kt`
- Create: `core/market/src/main/kotlin/tw/saietf/core/market/RoomSelectedQuoteRepository.kt`
- Create: `core/market/src/test/kotlin/tw/saietf/core/market/SelectedQuoteRepositoryTest.kt`

**Interfaces:**
- `interface SelectedQuoteRepository { fun observe(symbol: TwSymbol): Flow<NormalizedQuote>; fun observeMany(symbols: Set<TwSymbol>): Flow<Map<TwSymbol, NormalizedQuote>>; suspend fun accept(batch: SelectionBatch) }`
- Cache writes are atomic per source revision; intraday points are unique by `(symbol, sessionDate, exchangeTimestamp)`.

- [ ] **Step 1: Write failing tests for revision ordering, duplicate points, stale cached startup, next-session reset, retention, and transaction rollback.**
- [ ] **Step 2: Run `./gradlew :core:market:test --tests '*SelectedQuoteRepositoryTest'` and verify failures.**
- [ ] **Step 3: Implement Room mapping, bounded retention, status recomputation with the current clock, and atomic accept operations.**
- [ ] **Step 4: Run `./gradlew :core:database:test :core:market:test` and verify deterministic restart behavior.**
- [ ] **Step 5: Commit with message `feat: cache selected quotes and intraday series`.**

### Task 4: Build the Dashboard and Embedded Market Wall

**Files:**
- Create: `feature/dashboard/src/main/kotlin/tw/saietf/feature/dashboard/DashboardRepository.kt`
- Create: `feature/dashboard/src/main/kotlin/tw/saietf/feature/dashboard/DashboardViewModel.kt`
- Create: `feature/dashboard/src/main/kotlin/tw/saietf/feature/dashboard/DashboardScreen.kt`
- Create: `feature/dashboard/src/main/kotlin/tw/saietf/feature/dashboard/MarketWall.kt`
- Create: `feature/dashboard/src/main/kotlin/tw/saietf/feature/dashboard/MarketWallPreferences.kt`
- Create: `feature/dashboard/src/test/kotlin/tw/saietf/feature/dashboard/DashboardViewModelTest.kt`
- Create: `feature/dashboard/src/androidTest/kotlin/tw/saietf/feature/dashboard/MarketWallTest.kt`

**Interfaces:**
- `DashboardRepository.observe(portfolioId)` combines canonical portfolio summaries and selected quotes without finance recalculation.
- Market wall supports four persisted display modes, user order, reverse-order toggle, refresh state, source/freshness labels, and direct instrument navigation.

- [ ] **Step 1: Write failing tests for total-card mappings, four wall modes, order/reverse persistence, Chinese names, stale labels, empty/error states, and safe-area layout.**
- [ ] **Step 2: Run `./gradlew :feature:dashboard:test :feature:dashboard:connectedDebugAndroidTest` and verify failures.**
- [ ] **Step 3: Implement the total asset/P&L cards, holdings summary, embedded market wall, accessibility semantics, and refresh affordance.**
- [ ] **Step 4: Run dashboard tests at compact and large font scales and verify no clipped system-bar or navigation content.**
- [ ] **Step 5: Commit with message `feat: add dashboard market wall`.**

### Task 5: Add Instrument Details, Charts, and Consistency Diagnostics

**Files:**
- Create: `feature/dashboard/src/main/kotlin/tw/saietf/feature/dashboard/InstrumentDetailScreen.kt`
- Create: `feature/dashboard/src/main/kotlin/tw/saietf/feature/dashboard/IntradayChart.kt`
- Create: `feature/dashboard/src/main/kotlin/tw/saietf/feature/dashboard/MiniChart.kt`
- Create: `feature/settings/src/main/kotlin/tw/saietf/feature/settings/MarketDiagnosticsScreen.kt`
- Create: `app/src/androidTest/kotlin/tw/saietf/app/MarketConsistencyJourneyTest.kt`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Route: `market/{symbol}` renders the selected quote, provenance, holdings context, and current-session chart.
- Diagnostics lists provider health, last success/failure, cache age, selected revision, and redacted error reason.

- [ ] **Step 1: Write failing tests for chart gaps, suspended symbols, next-day chart reset, provenance, and cross-surface revision consistency.**
- [ ] **Step 2: Run `./gradlew :feature:dashboard:test :app:connectedDebugAndroidTest` and verify failures.**
- [ ] **Step 3: Implement detail/chart rendering, diagnostics, and one end-to-end fake-provider journey used by CI.**
- [ ] **Step 4: Run `./gradlew :core:market:test :feature:dashboard:test :app:connectedDebugAndroidTest` and verify all market gates pass.**
- [ ] **Step 5: Commit with message `feat: add market details charts and diagnostics`.**
