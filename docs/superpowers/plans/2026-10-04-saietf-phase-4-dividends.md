# SaiETF Phase 4 Automatic Dividends Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically discover, reconcile, calculate, display, and notify Taiwan stock and ETF dividend events without overwriting confirmed user receipts.

**Architecture:** Parse official announcement sources into versioned candidate events, reconcile them through a deterministic identity/state machine, calculate portfolio eligibility from ledger lots and the locked finance core, then sync idempotently at startup and daily with WorkManager.

**Tech Stack:** Kotlin, Coroutines, OkHttp, kotlinx.serialization, Room, WorkManager, Jetpack Compose, JUnit, MockWebServer, Compose UI Test.

**Spec:** `docs/superpowers/specs/2026-10-04-saietf-android-design.md`

## Global Constraints

- Prefer official MOPS, TWSE, and TPEx data; TPEx ETF announcements are a distinct adapter where required.
- Never overwrite a confirmed actual dividend receipt, actual fee, actual tax, or manual override.
- Gross dividend, NHI threshold 20,000, NHI rate 2.11%, and transfer fee 10 are calculated only by Phase 1 `FinanceEngine`.
- Sync is safe to retry and safe after process death; one official revision maps to one persisted event revision.
- Store source URL/identifier, observed time, effective revision, status, and confidence for auditability.

## Review Focus

- Repeated pulls and overlapping providers must not create duplicate events.
- A revised announcement updates one projected event while preserving version history.
- Eligibility uses the correct ex-date lot position and excludes later purchases.
- Offline startup shows cached status honestly and queues retry without claiming completion.

---

### Task 1: Define Official Dividend Source Contracts and Fixture Adapters

**Files:**
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/data/DividendSource.kt`
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/data/MopsDividendSource.kt`
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/data/TwseDividendSource.kt`
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/data/TpexDividendSource.kt`
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/data/TpexEtfDividendSource.kt`
- Create: `feature/dividends/src/test/resources/dividends/**/*.json`
- Create: `feature/dividends/src/test/kotlin/tw/saietf/feature/dividends/data/DividendSourceContractTest.kt`

**Interfaces:**
- `interface DividendSource { val id: String; suspend fun fetch(symbols: Set<TwSymbol>, since: LocalDate): DividendCandidateBatch }`
- `DividendCandidate` carries symbol, event type, ex-date, record date, payment date, cash/stock amount, currency, official ID, published/revised time, and source evidence.

- [ ] **Step 1: Write a failing shared contract suite for normal, partial-date, revised, cancelled, malformed, empty, timeout, and rate-limited fixtures.**
- [ ] **Step 2: Run `./gradlew :feature:dividends:test --tests '*DividendSourceContractTest'` and verify failures.**
- [ ] **Step 3: Implement each parser and typed diagnostics with fixture-only network tests and bounded retry rules.**
- [ ] **Step 4: Run all source tests offline and verify each fixture preserves its official identifier and revision time.**
- [ ] **Step 5: Commit with message `feat: add official dividend source adapters`.**

### Task 2: Implement Event Identity, Reconciliation, and Version History

**Files:**
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/domain/DividendIdentity.kt`
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/domain/DividendReconciler.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/entity/DividendEventEntity.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/entity/DividendRevisionEntity.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/dao/DividendDao.kt`
- Create: `feature/dividends/src/test/kotlin/tw/saietf/feature/dividends/domain/DividendReconcilerTest.kt`

**Interfaces:**
- `interface DividendReconciler { fun reconcile(current: DividendAggregate?, candidates: List<DividendCandidate>): ReconcileResult }`
- States: `ANNOUNCED`, `DATE_CONFIRMED`, `AMOUNT_CONFIRMED`, `PAID_EXPECTED`, `RECEIPT_CONFIRMED`, `CANCELLED`; confirmed receipt and manual override are terminal against automatic field replacement.

- [ ] **Step 1: Write failing tests for duplicate providers, official precedence, date/amount revision, cancellation, manual override, and receipt protection.**
- [ ] **Step 2: Run `./gradlew :feature:dividends:test --tests '*DividendReconcilerTest'` and verify failures.**
- [ ] **Step 3: Implement stable identity, field-level provenance, monotonic version history, and atomic event/revision persistence.**
- [ ] **Step 4: Run reconciliation and database tests; verify repeated identical batches cause zero writes.**
- [ ] **Step 5: Commit with message `feat: reconcile versioned dividend events`.**

### Task 3: Calculate Eligibility and Expected Net Receipts

**Files:**
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/domain/DividendEligibilityService.kt`
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/domain/DividendProjectionService.kt`
- Create: `feature/dividends/src/test/kotlin/tw/saietf/feature/dividends/domain/DividendEligibilityServiceTest.kt`
- Create: `feature/dividends/src/test/kotlin/tw/saietf/feature/dividends/domain/DividendProjectionGoldenTest.kt`

**Interfaces:**
- `DividendEligibilityService.eligibleShares(portfolioId, symbol, exDate)` derives shares from the immutable ledger as of the governing market cutoff.
- `DividendProjectionService.project(event, eligibleShares)` calls `FinanceEngine.calculateNetDividend` and stores gross, NHI, transfer fee, and expected net separately.

- [ ] **Step 1: Write failing tests for buys/sells around ex-date, multiple portfolios, corrections, fractional inputs rejection, 20,000 threshold boundaries, and actual receipt preservation.**
- [ ] **Step 2: Run the two dividend domain test classes and verify failures.**
- [ ] **Step 3: Implement eligibility projection and the finance-core adapter without duplicating any fee/NHI formula.**
- [ ] **Step 4: Run `./gradlew :core:finance:test :feature:dividends:test` and verify exact golden outputs.**
- [ ] **Step 5: Commit with message `feat: calculate dividend eligibility and receipts`.**

### Task 4: Add Idempotent Startup and Daily Synchronization

**Files:**
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/data/DividendRepository.kt`
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/sync/DividendSyncCoordinator.kt`
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/sync/DividendSyncWorker.kt`
- Create: `feature/dividends/src/test/kotlin/tw/saietf/feature/dividends/sync/DividendSyncCoordinatorTest.kt`
- Create: `feature/dividends/src/androidTest/kotlin/tw/saietf/feature/dividends/sync/DividendSyncWorkerTest.kt`

**Interfaces:**
- `suspend fun DividendSyncCoordinator.sync(reason: SyncReason): DividendSyncReceipt` uses a stable batch key and a Room checkpoint.
- WorkManager constraints require network, use unique periodic work, and enqueue an immediate non-blocking startup refresh when cache policy requires it.

- [ ] **Step 1: Write failing tests for concurrent startup/periodic sync, retry after process death, partial provider failure, offline state, and revised batch replay.**
- [ ] **Step 2: Run dividend sync unit and instrumentation tests and verify failures.**
- [ ] **Step 3: Implement source orchestration, transactional checkpoints, unique WorkManager scheduling, backoff, and observable sync status.**
- [ ] **Step 4: Run sync tests with forced worker stop/restart and verify no duplicate event, revision, or projection.**
- [ ] **Step 5: Commit with message `feat: automate idempotent dividend sync`.**

### Task 5: Build Dividend Calendar, Event Details, and Notifications

**Files:**
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/ui/DividendRoutes.kt`
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/ui/DividendCalendarScreen.kt`
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/ui/DividendEventScreen.kt`
- Create: `feature/dividends/src/main/kotlin/tw/saietf/feature/dividends/ui/DividendViewModel.kt`
- Create: `platform/surfaces/src/main/kotlin/tw/saietf/platform/surfaces/DividendNotificationPublisher.kt`
- Create: `app/src/androidTest/kotlin/tw/saietf/app/DividendJourneyTest.kt`

**Interfaces:**
- Routes: `dividends/calendar?portfolioId={id}` and `dividends/{eventId}`.
- UI displays date type, expected/confirmed status, source, last update, eligible shares, gross/deductions/net, and manual confirmation action.

- [ ] **Step 1: Write failing tests for month navigation, status/source labels, revision display, receipt confirmation, notification deduplication, and deep links.**
- [ ] **Step 2: Run `./gradlew :feature:dividends:test :app:connectedDebugAndroidTest` and verify failures.**
- [ ] **Step 3: Implement calendar/list/detail UI, accessible state descriptions, confirmation flow, channels, and stable notification receipts.**
- [ ] **Step 4: Run all dividend tests including offline cached UI, revised announcement, confirmed receipt, and process-restart journeys.**
- [ ] **Step 5: Commit with message `feat: add automatic dividend calendar and alerts`.**
