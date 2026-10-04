# SaiETF Phase 2 Ledger Portfolios and P&L Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver durable local-first transaction accounting, actual and model portfolios, and idempotent daily P&L snapshots on top of the locked finance core.

**Architecture:** Store an append-only ledger in Room, project all balances through `FinanceEngine`, expose repositories as Flow, and keep UI modules free of accounting formulas. Atomic database transactions and stable idempotency keys protect retries and process death.

**Tech Stack:** Kotlin, Room 2.8.5, Coroutines/Flow, Jetpack Compose, Navigation 2.9.6, JUnit, Robolectric, Compose UI Test.

**Spec:** `docs/superpowers/specs/2026-10-04-saietf-android-design.md`

## Global Constraints

- Consume Phase 1 APIs without copying or modifying finance formulas.
- Ledger entries are immutable; corrections append a compensating/void relationship instead of updating history.
- Every mutating command requires a unique idempotency key and runs in one Room transaction.
- Local dates and daily boundaries use `Asia/Taipei`; persisted instants use UTC.
- Support at least 5,000 ledger rows without loading the full history into a screen.

## Review Focus

- Retrying the same command must return the original result without inserting another row.
- Process death must expose either the old valid state or the fully committed new state.
- Actual and model portfolios must remain separate while sharing read-only instrument metadata.
- UI labels map `unrealizedProfit` to `持有總損益` and `comprehensivePnL` to `含息總報酬` without recomputation.

---

### Task 1: Define and Migrate the Room Schema

**Files:**
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/SaiEtfDatabase.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/entity/LedgerEntryEntity.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/entity/PortfolioEntity.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/entity/ModelAllocationEntity.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/entity/DailySnapshotEntity.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/dao/LedgerDao.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/dao/PortfolioDao.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/dao/DailySnapshotDao.kt`
- Create: `core/database/src/androidTest/kotlin/tw/saietf/core/database/SchemaMigrationTest.kt`

**Interfaces:**
- Schema v1 persists UUIDs, idempotency keys, correction links, UTC instants, Taipei trade dates, actual fee/tax, type discriminators, and snapshot source revision.
- Export Room schemas to `core/database/schemas/` and test every migration path.

- [ ] **Step 1: Write failing schema tests for uniqueness, foreign keys, append-only triggers/DAO surface, and migration round trips.**
- [ ] **Step 2: Run `./gradlew :core:database:connectedDebugAndroidTest` and verify the absent database fails.**
- [ ] **Step 3: Implement entities, converters, indices, DAOs, database transactions, and schema export.**
- [ ] **Step 4: Run `./gradlew :core:database:test :core:database:connectedDebugAndroidTest` and verify all constraints pass.**
- [ ] **Step 5: Commit with message `feat: add local accounting database`.**

### Task 2: Implement the Immutable Ledger Repository

**Files:**
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/repository/LedgerRepository.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/repository/RoomLedgerRepository.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/repository/LedgerCommand.kt`
- Create: `core/database/src/test/kotlin/tw/saietf/core/database/repository/RoomLedgerRepositoryTest.kt`

**Interfaces:**
- `interface LedgerRepository { suspend fun append(command: LedgerCommand): LedgerWriteResult; suspend fun correct(command: CorrectLedgerCommand): LedgerWriteResult; fun observeProjection(portfolioId: String): Flow<LedgerProjection>; fun page(portfolioId: String, cursor: LedgerCursor?, limit: Int): Flow<LedgerPage> }`
- Duplicate idempotency keys return the prior `LedgerWriteResult`; corrections reference an existing row and append exactly once.

- [ ] **Step 1: Write failing tests for append, retry, correction, void, concurrent duplicate submission, oversell rollback, and process-death recovery.**
- [ ] **Step 2: Run `./gradlew :core:database:test --tests '*RoomLedgerRepositoryTest'` and verify failures.**
- [ ] **Step 3: Implement repository transactions, stable ordering, projection through `LedgerProjector`, and keyset pagination.**
- [ ] **Step 4: Run repository tests plus a 5,000-row pagination benchmark fixture and verify no duplicate or skipped rows.**
- [ ] **Step 5: Commit with message `feat: add immutable idempotent ledger repository`.**

### Task 3: Build Transaction Entry and History Screens

**Files:**
- Create: `feature/transactions/src/main/kotlin/tw/saietf/feature/transactions/TransactionRoutes.kt`
- Create: `feature/transactions/src/main/kotlin/tw/saietf/feature/transactions/TransactionEditorViewModel.kt`
- Create: `feature/transactions/src/main/kotlin/tw/saietf/feature/transactions/TransactionEditorScreen.kt`
- Create: `feature/transactions/src/main/kotlin/tw/saietf/feature/transactions/TransactionHistoryScreen.kt`
- Create: `feature/transactions/src/test/kotlin/tw/saietf/feature/transactions/TransactionEditorViewModelTest.kt`
- Create: `feature/transactions/src/androidTest/kotlin/tw/saietf/feature/transactions/TransactionFlowTest.kt`

**Interfaces:**
- Routes: `transactions/new?portfolioId={id}`, `transactions/{entryId}`, `transactions/history?portfolioId={id}`.
- Editor accepts buy, sell, dividend receipt, fee/tax actuals, trade mode, timestamp, note, and correction reason.

- [ ] **Step 1: Write failing ViewModel/UI tests for validation, duplicate taps, corrections, list paging, and finance-preview parity.**
- [ ] **Step 2: Run `./gradlew :feature:transactions:test :feature:transactions:connectedDebugAndroidTest` and verify failures.**
- [ ] **Step 3: Implement routes, state machine, accessible Compose forms, submission receipt handling, and history paging.**
- [ ] **Step 4: Run unit and connected tests; verify rotation/process recreation does not resubmit a committed transaction.**
- [ ] **Step 5: Commit with message `feat: add transaction entry and history`.**

### Task 4: Implement Actual and Model Portfolio Management

**Files:**
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/repository/PortfolioRepository.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/repository/RoomPortfolioRepository.kt`
- Create: `feature/portfolios/src/main/kotlin/tw/saietf/feature/portfolios/PortfolioRoutes.kt`
- Create: `feature/portfolios/src/main/kotlin/tw/saietf/feature/portfolios/PortfolioListScreen.kt`
- Create: `feature/portfolios/src/main/kotlin/tw/saietf/feature/portfolios/ModelPortfolioEditorScreen.kt`
- Create: `feature/portfolios/src/test/kotlin/tw/saietf/feature/portfolios/PortfolioViewModelTest.kt`

**Interfaces:**
- `interface PortfolioRepository { fun observeAll(): Flow<List<PortfolioSummary>>; suspend fun create(command: CreatePortfolio): PortfolioId; suspend fun saveModel(command: SaveModelAllocation); suspend fun archive(id: PortfolioId) }`
- Model allocation weights must be non-negative and total exactly 10000 basis points before Apply.

- [ ] **Step 1: Write failing tests for actual/model separation, 100% allocation validation, archive behavior, and canonical summary labels.**
- [ ] **Step 2: Run `./gradlew :feature:portfolios:test` and verify failures.**
- [ ] **Step 3: Implement repository mapping, list/detail navigation, model allocation editor, and read-only actual summaries.**
- [ ] **Step 4: Run `./gradlew :core:database:test :feature:portfolios:test :feature:portfolios:connectedDebugAndroidTest` and verify success.**
- [ ] **Step 5: Commit with message `feat: add actual and model portfolios`.**

### Task 5: Add Idempotent Daily P&L Snapshots and End-to-End Gates

**Files:**
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/repository/DailySnapshotRepository.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/repository/RoomDailySnapshotRepository.kt`
- Create: `core/database/src/test/kotlin/tw/saietf/core/database/repository/DailySnapshotRepositoryTest.kt`
- Create: `app/src/androidTest/kotlin/tw/saietf/app/AccountingJourneyTest.kt`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- `suspend fun recordIfChanged(portfolioId: PortfolioId, taipeiDate: LocalDate, sourceRevision: String): SnapshotWriteResult` has a unique `(portfolioId, taipeiDate, sourceRevision)` receipt.
- Snapshot values come only from canonical `PortfolioCalculationResult`.

- [ ] **Step 1: Write failing tests for same-day retries, midnight Taipei rollover, source revision, offline restart, and full buy/sell/dividend journey.**
- [ ] **Step 2: Run `./gradlew :core:database:test :app:connectedDebugAndroidTest` and verify failures.**
- [ ] **Step 3: Implement snapshot transactions, retention/query APIs, test fixtures, and the accounting journey CI job.**
- [ ] **Step 4: Run `./gradlew :core:database:test :feature:transactions:test :feature:portfolios:test :app:connectedDebugAndroidTest` and verify success.**
- [ ] **Step 5: Commit with message `feat: add daily pnl snapshots and accounting gate`.**
