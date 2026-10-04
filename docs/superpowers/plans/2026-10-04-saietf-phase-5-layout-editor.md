# SaiETF Phase 5 Page Layout Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reproduce the proven TF Asset page-settings editing workflow in native Compose while ensuring preview and runtime pages render the same persisted layout model.

**Architecture:** Define a versioned capability-based layout schema, render it through one shared runtime renderer, edit an isolated draft session, and atomically apply valid configurations. The reference behavior is frozen from ETF-Asset-V1.0, but the implementation is native Android rather than a source translation.

**Tech Stack:** Kotlin, kotlinx.serialization, DataStore or Room configuration storage, Jetpack Compose, Compose UI Test, property tests.

**Spec:** `docs/superpowers/specs/2026-10-04-saietf-android-design.md`

## Global Constraints

- Reference editor behavior at `locopy0121-hub/ETF-Asset-V1.0@8fc58cd00f0e1c7df24fc0ae437c098bfdaf2f80`.
- Preview and runtime must use the same `RuntimePageRenderer`; do not maintain a second preview-only renderer.
- Draft changes never affect runtime until Apply commits a validated configuration atomically.
- Invalid or newer schema payloads fail closed to the last valid layout or a known default without crashing.
- Persist positions and sizes as non-negative values; never use `-1` sentinels.

## Review Focus

- Compare selection, accordion, Draft/Apply/Cancel/Reset, and capability behavior with the pinned reference.
- Verify color/profit-color editing and opacity are separate properties.
- Verify rotation and process death preserve the draft or restore the last applied layout according to session state.
- Verify every component shown in preview renders identically on the actual page for the same snapshot.

---

### Task 1: Define the Versioned Layout Model and Capability Registry

**Files:**
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/model/PageLayout.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/model/ComponentConfig.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/model/ComponentCapability.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/model/ComponentRegistry.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/model/DefaultLayouts.kt`
- Create: `feature/editor/src/test/kotlin/tw/saietf/feature/editor/model/PageLayoutSerializationTest.kt`

**Interfaces:**
- `PageLayout(schemaVersion, pageId, revision, components)` is serializable and immutable.
- Registry types: frame, title, data, image, icon, chart, reminder, marquee, and calendar; each advertises editable capabilities.

- [ ] **Step 1: Write failing round-trip/property tests for every component, unknown fields/types, duplicate IDs, bounds, opacity, profit colors, and no-negative-dimension invariants.**
- [ ] **Step 2: Run `./gradlew :feature:editor:test --tests '*PageLayoutSerializationTest'` and verify failures.**
- [ ] **Step 3: Implement versioned models, validators, registry metadata, and stable default dashboard/page layouts.**
- [ ] **Step 4: Run serialization tests and verify malformed payloads produce typed validation failures, never exceptions escaping to UI.**
- [ ] **Step 5: Commit with message `feat: define page layout model and capabilities`.**

### Task 2: Implement the Shared Runtime Renderer

**Files:**
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/render/RuntimePageRenderer.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/render/PageDataSnapshot.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/render/ComponentRenderers.kt`
- Create: `feature/editor/src/test/kotlin/tw/saietf/feature/editor/render/RuntimePageRendererTest.kt`
- Create: `feature/editor/src/androidTest/kotlin/tw/saietf/feature/editor/render/RendererScreenshotTest.kt`

**Interfaces:**
- `@Composable fun RuntimePageRenderer(layout: PageLayout, snapshot: PageDataSnapshot, modifier: Modifier = Modifier, selection: EditorSelection? = null)`.
- `PageDataSnapshot` contains read-only canonical portfolio, selected quote, dividend, reminder, and chart values with explicit freshness metadata.

- [ ] **Step 1: Write failing renderer tests for z-order, frame/title/data/image/icon/chart/reminder/marquee/calendar, profit colors, opacity, clipping, and invalid-config fallback.**
- [ ] **Step 2: Run renderer unit and screenshot tests and verify failures.**
- [ ] **Step 3: Implement the shared renderer and component implementations without querying repositories inside composables.**
- [ ] **Step 4: Run tests across phone widths, font scales, light/dark themes, and stale data snapshots.**
- [ ] **Step 5: Commit with message `feat: add shared runtime page renderer`.**

### Task 3: Add Atomic Layout Storage and Draft Sessions

**Files:**
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/data/PageLayoutRepository.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/data/PersistentPageLayoutRepository.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/domain/EditorSession.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/data/LayoutMigrations.kt`
- Create: `feature/editor/src/test/kotlin/tw/saietf/feature/editor/data/PageLayoutRepositoryTest.kt`
- Create: `feature/editor/src/test/kotlin/tw/saietf/feature/editor/domain/EditorSessionTest.kt`

**Interfaces:**
- `interface PageLayoutRepository { fun observeApplied(pageId: String): Flow<PageLayout>; suspend fun apply(pageId: String, expectedRevision: Long, draft: PageLayout): ApplyResult; suspend fun reset(pageId: String): ApplyResult }`
- `EditorSession` owns draft, selection, dirty state, undoable edits for the current session, Apply, Cancel, and Reset intents.

- [ ] **Step 1: Write failing tests for draft isolation, optimistic revision conflict, Apply atomicity, Cancel, Reset, migration, malformed payload, and process-death restoration.**
- [ ] **Step 2: Run repository/session tests and verify failures.**
- [ ] **Step 3: Implement versioned persistence, last-known-good fallback, atomic replace, saved-state draft checkpoint, and session state reducer.**
- [ ] **Step 4: Run tests with injected write failure between validation and commit; verify runtime remains on the previous valid revision.**
- [ ] **Step 5: Commit with message `feat: add atomic page layout editing sessions`.**

### Task 4: Build Real Preview, Selection, and Accordion Editing Shell

**Files:**
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/ui/PageEditorRoute.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/ui/PageEditorScreen.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/ui/PreviewSelectionOverlay.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/ui/EditorAccordion.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/ui/PageEditorViewModel.kt`
- Create: `feature/editor/src/androidTest/kotlin/tw/saietf/feature/editor/ui/PageEditorShellTest.kt`

**Interfaces:**
- Route: `settings/pages/{pageId}/edit`.
- Preview is the shared renderer with live draft plus dashed selected-component outline; only one tool accordion section is open at once.

- [ ] **Step 1: Write failing UI tests for tap selection, dashed outline, single-open accordion, dirty prompt, Apply/Cancel/Reset, rotation, and back navigation.**
- [ ] **Step 2: Run `./gradlew :feature:editor:connectedDebugAndroidTest --tests '*PageEditorShellTest'` and verify failures.**
- [ ] **Step 3: Implement responsive preview/tools layout, selection overlay, top actions, accessibility focus, and ViewModel intents.**
- [ ] **Step 4: Run UI tests at compact/expanded widths and verify the applied runtime does not change before Apply.**
- [ ] **Step 5: Commit with message `feat: add page editor preview and controls`.**

### Task 5: Implement Component Tools and Runtime Integration

**Files:**
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/ui/tools/FrameTools.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/ui/tools/TypographyTools.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/ui/tools/DataColorTools.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/ui/tools/PositionSizeTools.kt`
- Create: `feature/editor/src/main/kotlin/tw/saietf/feature/editor/ui/tools/ContentTools.kt`
- Create: `feature/settings/src/main/kotlin/tw/saietf/feature/settings/PageSettingsScreen.kt`
- Create: `app/src/androidTest/kotlin/tw/saietf/app/PageEditorParityJourneyTest.kt`

**Interfaces:**
- Tools expose only registry-supported properties: position/size, frame, title, data, image, icon, chart, reminder, marquee, calendar, color/profit color, and independent opacity.
- Settings entry opens the editor; dashboard/runtime pages observe only applied revisions.

- [ ] **Step 1: Write failing tests for every capability editor, numeric clamping, color/opacity independence, preview/runtime parity, malformed config, and process restart.**
- [ ] **Step 2: Run `./gradlew :feature:editor:test :feature:editor:connectedDebugAndroidTest :app:connectedDebugAndroidTest` and verify failures.**
- [ ] **Step 3: Implement capability-driven tools, settings navigation, applied-layout observation, and migration/fallback diagnostics.**
- [ ] **Step 4: Run the full editor suite and compare identical layout/snapshot semantics between preview and runtime for all component types.**
- [ ] **Step 5: Commit with message `feat: complete capability-based page editor`.**
