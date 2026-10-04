# SaiETF Phase 6 Android Surfaces Backup and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete Widget, floating quote overlay, daily P&L notification, encrypted local backup/restore, device QA, and a reproducible GitHub QA release APK.

**Architecture:** Expose read-only cross-process snapshots from existing repositories, adapt them to Android-constrained surfaces, keep background status explicit, protect portable backups with password-derived authenticated encryption, and gate the GitHub Release on unit, instrumentation, migration, security, and emulator evidence.

**Tech Stack:** Kotlin, Glance 1.2.0, WorkManager, foreground services, Android Keystore APIs, Tink or platform AEAD, Room, Compose, JUnit, Android Emulator, GitHub Actions, CycloneDX SBOM.

**Spec:** `docs/superpowers/specs/2026-10-04-saietf-android-design.md`

## Global Constraints

- Widget, overlay, and notifications consume the same selected quote and canonical portfolio revisions as the app.
- Background surfaces show last-updated time and stale/unavailable state; never imply live delivery after the OS blocks work.
- Overlay starts only from an explicit user action after permission grant and always has a visible foreground-service notification and stop action.
- Backups never include provider credentials or backup passwords and must fail authentication before modifying the database.
- Development publishes to GitHub Releases only; do not submit to Google Play and do not create a PR.

## Review Focus

- Test Android background/notification/overlay behavior on API 29, 31, 34, 35, and 36.
- Verify notification, widget refresh, backup import, and restore retries are idempotent.
- Verify malformed, truncated, wrong-password, wrong-version, and tampered backups leave current data untouched.
- Verify release assets are generated from the tagged commit and their published SHA-256 values match downloaded bytes.

---

### Task 1: Implement Glance Widgets

**Files:**
- Create: `platform/surfaces/src/main/kotlin/tw/saietf/platform/surfaces/widget/SaiEtfWidget.kt`
- Create: `platform/surfaces/src/main/kotlin/tw/saietf/platform/surfaces/widget/WidgetSnapshotRepository.kt`
- Create: `platform/surfaces/src/main/kotlin/tw/saietf/platform/surfaces/widget/WidgetUpdateWorker.kt`
- Create: `platform/surfaces/src/main/res/xml/saietf_widget_info.xml`
- Create: `platform/surfaces/src/test/kotlin/tw/saietf/platform/surfaces/widget/WidgetSnapshotRepositoryTest.kt`
- Create: `platform/surfaces/src/androidTest/kotlin/tw/saietf/platform/surfaces/widget/SaiEtfWidgetTest.kt`

**Interfaces:**
- Widget layouts: compact `2x1`, standard `4x2`, and expanded `4x3` with portfolio/symbol configuration.
- Snapshot contains revision, primary values, quote status, last update, and deep-link destination.

- [ ] **Step 1: Write failing tests for all sizes, configuration, canonical P&L mapping, stale/unavailable states, revision deduplication, and deep links.**
- [ ] **Step 2: Run widget unit/instrumentation tests and verify failures.**
- [ ] **Step 3: Implement Glance layouts, configuration receiver, unique update work, and explicit freshness labels.**
- [ ] **Step 4: Run tests on API 29/31/34/36 and verify blocked background work retains an honest cached state.**
- [ ] **Step 5: Commit with message `feat: add SaiETF home screen widgets`.**

### Task 2: Implement the User-Controlled Floating Quote Overlay

**Files:**
- Create: `platform/surfaces/src/main/kotlin/tw/saietf/platform/surfaces/overlay/QuoteOverlayService.kt`
- Create: `platform/surfaces/src/main/kotlin/tw/saietf/platform/surfaces/overlay/OverlayController.kt`
- Create: `platform/surfaces/src/main/kotlin/tw/saietf/platform/surfaces/overlay/OverlayPreferences.kt`
- Create: `feature/settings/src/main/kotlin/tw/saietf/feature/settings/OverlaySettingsScreen.kt`
- Create: `platform/surfaces/src/test/kotlin/tw/saietf/platform/surfaces/overlay/OverlayControllerTest.kt`
- Create: `platform/surfaces/src/androidTest/kotlin/tw/saietf/platform/surfaces/overlay/QuoteOverlayServiceTest.kt`

**Interfaces:**
- Refresh choices are 1, 3, 5, or 10 seconds, subject to source availability and Android restrictions.
- Controller states: stopped, permission-required, starting, running, stale, blocked, and error; service notification exposes Stop.

- [ ] **Step 1: Write failing tests for permission denial/grant, explicit start, restart policy, interval selection, drag persistence, stale state, notification Stop, and process kill.**
- [ ] **Step 2: Run overlay tests and verify failures.**
- [ ] **Step 3: Implement permission flow, foreground service, overlay window, repository collection, throttling, persistence, and deterministic shutdown.**
- [ ] **Step 4: Run API 29/31/34/35/36 tests and verify the service never starts invisibly or claims freshness without updates.**
- [ ] **Step 5: Commit with message `feat: add floating quote overlay`.**

### Task 3: Implement Idempotent Daily P&L Notifications

**Files:**
- Create: `platform/surfaces/src/main/kotlin/tw/saietf/platform/surfaces/notification/DailyPnlWorker.kt`
- Create: `platform/surfaces/src/main/kotlin/tw/saietf/platform/surfaces/notification/DailyPnlPublisher.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/entity/NotificationReceiptEntity.kt`
- Create: `feature/settings/src/main/kotlin/tw/saietf/feature/settings/NotificationSettingsScreen.kt`
- Create: `platform/surfaces/src/test/kotlin/tw/saietf/platform/surfaces/notification/DailyPnlWorkerTest.kt`

**Interfaces:**
- Unique receipt key: `(notificationType, portfolioId, taipeiDate, sourceRevision)`.
- User configures enabled portfolios and local delivery time; notification shows canonical daily P&L, data time, and stale status.

- [ ] **Step 1: Write failing tests for permission state, same-day retry, revision handling, Taipei DST-independent scheduling, device reboot, offline data, and deep link.**
- [ ] **Step 2: Run notification tests and verify failures.**
- [ ] **Step 3: Implement unique WorkManager scheduling, receipt transaction, channels, settings, and boot/time-change rescheduling.**
- [ ] **Step 4: Run forced retry/process-restart tests and verify at most one notification per receipt key.**
- [ ] **Step 5: Commit with message `feat: add daily pnl notifications`.**

### Task 4: Implement Authenticated Encrypted Backup and Restore

**Files:**
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/backup/BackupManifest.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/backup/EncryptedBackupService.kt`
- Create: `core/database/src/main/kotlin/tw/saietf/core/database/backup/RestoreCoordinator.kt`
- Create: `feature/settings/src/main/kotlin/tw/saietf/feature/settings/BackupRestoreScreen.kt`
- Create: `core/database/src/test/kotlin/tw/saietf/core/database/backup/EncryptedBackupServiceTest.kt`
- Create: `app/src/androidTest/kotlin/tw/saietf/app/BackupRestoreJourneyTest.kt`

**Interfaces:**
- `.saietf` container includes clear format/version/KDF metadata plus AEAD-encrypted manifest and records; manifest stores counts and SHA-256 per logical table.
- Restore modes are Merge and Replace; both validate/decrypt/stage first, checkpoint current state, commit atomically, and roll back on any failure.

- [ ] **Step 1: Write failing tests for round trip, wrong password, tampering, truncation, unknown version, duplicate merge, replace rollback, migration, and provider-secret exclusion.**
- [ ] **Step 2: Run backup unit/journey tests and verify failures.**
- [ ] **Step 3: Implement password KDF parameters, AEAD streaming container, manifest validation, staging database, idempotent merge, atomic replace, checkpoint, and recovery.**
- [ ] **Step 4: Run tests with injected failure at every restore phase and verify the original database remains valid and recoverable.**
- [ ] **Step 5: Commit with message `feat: add encrypted local backup and restore`.**

### Task 5: Add Emulator QA Matrix and Release Automation

**Files:**
- Create: `.github/workflows/android-emulator.yml`
- Create: `.github/workflows/release-qa.yml`
- Create: `scripts/verify_release_artifacts.sh`
- Create: `scripts/run_release_gate.sh`
- Create: `app/src/androidTest/kotlin/tw/saietf/app/CriticalJourneySuite.kt`
- Modify: `.github/workflows/ci.yml`
- Create: `docs/qa/release-checklist-v1.0.1.md`

**Interfaces:**
- `release-qa.yml` runs only for tag `v1.0.1-qa` after green CI and produces `SaiETF-V1.0.1-QA.apk`, `SaiETF-V1.0.1-QA.apk.sha256`, test reports, and CycloneDX SBOM.
- Emulator evidence covers API 29, 31, 34, 35, and 36; critical journeys cover first launch, transaction, portfolio, market wall, dividend, editor, widget, overlay, notification, backup, and restore.

- [ ] **Step 1: Write a failing artifact verifier and critical journey suite that require exact version/package, expected filenames, checksums, SBOM, and all phase gates.**
- [ ] **Step 2: Run `./scripts/run_release_gate.sh` and verify it fails before workflows/scripts are complete.**
- [ ] **Step 3: Implement deterministic QA build/signing configuration, dependency review, emulator sharding, report collection, SBOM generation, hash verification, and GitHub Release upload.**
- [ ] **Step 4: Run `./scripts/run_release_gate.sh`, install the produced APK on an emulator, run smoke tests, and verify `sha256sum -c SaiETF-V1.0.1-QA.apk.sha256`.**
- [ ] **Step 5: Commit with message `ci: add SaiETF V1.0.1 QA release gate`.**

### Task 6: Publish the GitHub QA Release

**Files:**
- Read: `docs/qa/release-checklist-v1.0.1.md`
- Verify: GitHub Actions run artifacts and release assets
- Tag: `v1.0.1-qa`

**Interfaces:**
- Release notes list frozen finance source commit, editor reference commit, supported Android/API scope, data-source freshness behavior, known limitations, and APK SHA-256.
- This task publishes no Play Store artifact and creates no pull request.

- [ ] **Step 1: Add a release-readiness test/checklist assertion that fails when any required workflow result or artifact is absent.**
- [ ] **Step 2: Run the readiness check against the untagged commit and verify it reports the missing release evidence.**
- [ ] **Step 3: Push the final green commit to `main`, create signed/annotated tag `v1.0.1-qa`, and let `release-qa.yml` publish assets.**
- [ ] **Step 4: Download every release asset, verify hashes and APK install/launch, and record workflow URLs plus emulator results in the release checklist.**
- [ ] **Step 5: Commit any evidence-only checklist update with message `docs: record V1.0.1 QA release evidence` only if the release workflow is configured to attach the post-release evidence; otherwise retain evidence in release notes.**
