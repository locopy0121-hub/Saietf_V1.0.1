# SaiETF Phase 1 Foundation and Frozen Finance Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the Android project, reproducible CI, and a Kotlin finance core whose integer outputs exactly match TF Asset V3.7.8.

**Architecture:** Establish the multi-module dependency graph first, then export immutable V3.7.8 fixtures from the pinned source, implement pure Kotlin calculations and ledger projection, and lock both fixtures and public behavior behind CI.

**Tech Stack:** AGP 9.4.0, Gradle 9.6.0, Kotlin 2.4.20, JDK 17, Android SDK 36, Compose BOM 2026.09.00, Kotlin coroutines, kotlinx.serialization, JUnit, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-04-saietf-android-design.md`

## Global Constraints

- Pin finance source to `locopy0121-hub/ETF-Finance-Manager-V3.7@a872644c572d24fc5ffe597a97eb6d7d8bd7283f`; never export from moving `main`.
- All money, fee, tax, share, and dividend results use integer units and the same floor points as V3.7.8.
- Preserve supplied `actualFee` and `actualTax` as historical truth.
- Keep `:core:finance` pure Kotlin with no Android, Room, network, or UI dependency.
- Push only green commits directly to `main`; never place secrets or user data in GitHub.

## Review Focus

- Confirm the golden 0050 buy case returns trade amount 1,648, fee 2, and cash 1,650.
- Confirm moving-average partial sells release proportional cost without subtracting sale proceeds from remaining cost.
- Confirm ETF and stock sale tax paths cannot be interchanged.
- Confirm a fixture hash change fails CI and requires an explicit lock update.

---

### Task 1: Scaffold the Android Multi-Module Project and CI

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/tw/saietf/app/SaiEtfApplication.kt`
- Create: `app/src/test/kotlin/tw/saietf/app/BuildContractTest.kt`
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Define modules `:app`, `:core:model`, `:core:finance`, `:core:database`, `:core:market`, `:core:designsystem`, `:feature:dashboard`, `:feature:portfolios`, `:feature:transactions`, `:feature:dividends`, `:feature:editor`, `:feature:settings`, and `:platform:surfaces`.
- `:app` owns package `tw.saietf.app`, `versionCode=10001`, `versionName=1.0.1`, min SDK 29, compile/target SDK 36.

- [ ] **Step 1: Write the failing `BuildContractTest` for application ID, version, module inventory, and Traditional Chinese default locale.**
- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest` and verify it fails because the scaffold is absent.**
- [ ] **Step 3: Add the Gradle catalog, modules, application class, manifest, and CI jobs for unit tests, lint, and debug assembly.**
- [ ] **Step 4: Run `./gradlew clean test lintDebug :app:assembleDebug` and verify it passes on JDK 17.**
- [ ] **Step 5: Commit with message `build: scaffold SaiETF Android project`.**

### Task 2: Export and Freeze TF Asset V3.7.8 Fixtures

**Files:**
- Create: `tools/tf-v378/export-fixtures.mjs`
- Create: `tools/tf-v378/package.json`
- Create: `core/model/src/main/kotlin/tw/saietf/core/model/FinanceFixture.kt`
- Create: `core/finance/src/test/resources/v378/manifest.json`
- Create: `core/finance/src/test/resources/v378/trades.json`
- Create: `core/finance/src/test/resources/v378/portfolios.json`
- Create: `core/finance/src/test/resources/v378/dividends.json`
- Create: `core/finance/src/test/kotlin/tw/saietf/core/finance/FixtureIntegrityTest.kt`

**Interfaces:**
- `FinanceFixture(schemaVersion, sourceRepository, sourceCommit, input, expected)` records provenance and expected integer outputs.
- `manifest.json` stores SHA-256 for every fixture file and the pinned commit.

- [ ] **Step 1: Write `FixtureIntegrityTest` to reject a missing source commit, schema mismatch, or incorrect SHA-256.**
- [ ] **Step 2: Run `./gradlew :core:finance:test --tests '*FixtureIntegrityTest'` and verify it fails because fixtures do not exist.**
- [ ] **Step 3: Export golden 0050, 00878, round-lot, odd-lot, recurring, stock-tax, actual-fee/tax, partial-sell, and dividend cases from the pinned V3.7.8 code.**
- [ ] **Step 4: Run the exporter twice, verify byte-identical output, then run `./gradlew :core:finance:test --tests '*FixtureIntegrityTest'`.**
- [ ] **Step 5: Commit with message `test: freeze TF Asset V3.7.8 finance fixtures`.**

### Task 3: Implement Finance Primitives and Instrument Calculations

**Files:**
- Create: `core/model/src/main/kotlin/tw/saietf/core/model/Money.kt`
- Create: `core/model/src/main/kotlin/tw/saietf/core/model/TradeMode.kt`
- Create: `core/model/src/main/kotlin/tw/saietf/core/model/BrokerProfile.kt`
- Create: `core/finance/src/main/kotlin/tw/saietf/core/finance/FinanceModels.kt`
- Create: `core/finance/src/main/kotlin/tw/saietf/core/finance/FinanceEngine.kt`
- Create: `core/finance/src/test/kotlin/tw/saietf/core/finance/FinanceEngineGoldenTest.kt`

**Interfaces:**
- `interface FinanceEngine { fun calculateInstrument(input: InstrumentCalculationInput): InstrumentCalculationResult; fun calculatePortfolio(input: PortfolioCalculationInput): PortfolioCalculationResult; fun calculateNetDividend(input: DividendCalculationInput): DividendCalculationResult }`
- `BrokerProfile` defaults: commission `0.001425`, discount `0.65`, odd/recurring minimum fee `1`, round-lot minimum fee `20`, ETF sell tax `0.001`, stock sell tax `0.003`, transfer fee `10`.

- [ ] **Step 1: Write parameterized golden tests for fee minimums, tax categories, floor order, supplied actuals, and the 0050 case.**
- [ ] **Step 2: Run `./gradlew :core:finance:test --tests '*FinanceEngineGoldenTest'` and verify the unimplemented calculations fail.**
- [ ] **Step 3: Implement immutable inputs/results and `FinanceEngine` using integer-safe arithmetic and explicit floor points.**
- [ ] **Step 4: Run `./gradlew :core:finance:test --tests '*FinanceEngineGoldenTest'` and verify every expected integer is exact.**
- [ ] **Step 5: Commit with message `feat: port frozen instrument finance calculations`.**

### Task 4: Implement Ledger Projection and Portfolio Aggregation

**Files:**
- Create: `core/finance/src/main/kotlin/tw/saietf/core/finance/LedgerProjector.kt`
- Create: `core/finance/src/main/kotlin/tw/saietf/core/finance/PortfolioAggregator.kt`
- Create: `core/finance/src/test/kotlin/tw/saietf/core/finance/LedgerProjectorGoldenTest.kt`
- Create: `core/finance/src/test/kotlin/tw/saietf/core/finance/PortfolioAggregatorTest.kt`

**Interfaces:**
- `interface LedgerProjector { fun project(entries: List<LedgerEntry>): LedgerProjection }`
- `interface PortfolioAggregator { fun aggregate(items: List<InstrumentCalculationResult>): PortfolioCalculationResult }`
- Canonical output fields include `unrealizedProfit` and `comprehensivePnL`; UI layers may label but never recalculate them.

- [ ] **Step 1: Write failing tests for moving-average buys, proportional partial sells, oversell rejection, supplied actual fees/taxes, 00878, and canonical totals.**
- [ ] **Step 2: Run `./gradlew :core:finance:test --tests '*LedgerProjectorGoldenTest' --tests '*PortfolioAggregatorTest'` and verify failures.**
- [ ] **Step 3: Implement deterministic chronological projection with stable tie-breaking and aggregation from canonical instrument summaries only.**
- [ ] **Step 4: Run `./gradlew :core:finance:test` and verify fixture parity and invariant/property tests pass.**
- [ ] **Step 5: Commit with message `feat: port frozen ledger and portfolio projection`.**

### Task 5: Lock the Finance Core Against Drift

**Files:**
- Create: `core/finance/CORE_LOCK.md`
- Create: `scripts/verify_finance_lock.sh`
- Modify: `.github/workflows/ci.yml`
- Create: `core/finance/src/test/kotlin/tw/saietf/core/finance/FinanceCoreLockTest.kt`

**Interfaces:**
- `verify_finance_lock.sh` validates source commit, fixture hashes, public result-field inventory, and exact fixture output.
- Any intentional rule change requires a new reviewed lock version and regenerated fixtures; ordinary feature work cannot update the lock.

- [ ] **Step 1: Write a failing lock test that detects edited fixtures and an altered public result schema.**
- [ ] **Step 2: Run `./gradlew :core:finance:test --tests '*FinanceCoreLockTest'` and verify it fails before the lock manifest exists.**
- [ ] **Step 3: Document immutable rules, implement the verifier, and add it as a required CI step before Android assembly.**
- [ ] **Step 4: Run `./scripts/verify_finance_lock.sh && ./gradlew clean :core:finance:test :app:assembleDebug` and verify success.**
- [ ] **Step 5: Commit with message `ci: lock TF Asset V3.7.8 finance parity`.**
