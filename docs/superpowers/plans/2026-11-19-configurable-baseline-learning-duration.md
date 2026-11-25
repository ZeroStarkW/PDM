# Configurable Baseline Learning Duration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Configure baseline learning to calculate over a time window while keeping a 300-valid-sample minimum.

**Architecture:** Inject an ISO-8601 `Duration` property into the baseline service, retain/query valid samples inside that window, and expose the active window through the existing baseline API. Update the baseline page and deployment configuration to explain and supply the setting.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, PostgreSQL, Vue 3, TypeScript, Vite.

**Spec:** `docs/superpowers/specs/2026-11-19-configurable-baseline-learning-duration-design.md`

## Global Constraints

- Default duration is `PT5M`.
- At least 300 valid samples are still required.
- Both feature coefficients of variation must remain below `0.25` to activate the baseline.
- Preserve existing user changes in `src/main/resources/application.yml`.
- Do not add or run tests; verify by compiling/building as authorized in the current session.

## Review Focus

- A short or empty learning window must remain `LEARNING`; inspect the time-span and sample-count guards.
- Samples outside the configured window must not affect the median or CV; inspect repository bounds and cleanup ordering.
- High CV after the window fills must continue learning on the rolling window; inspect persistence and warning behavior.
- Reset learning must use the same configured window; inspect the existing reset path.
- Frontend progress must not imply that 300 samples alone satisfy the duration condition; inspect displayed labels and API fields.

---

### Task 1: Time-window baseline calculation and configuration

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `compose.yaml`
- Modify: `.env.example`
- Modify: `src/main/java/com/pdm/mvp/analytics/BaselineService.java`
- Modify: `src/main/java/com/pdm/mvp/analytics/BaselineSampleRepository.java`

**Interfaces:**
- `BaselineService` consumes `pdm.baseline.learning-duration` as a Java `Duration`, default `PT5M`.
- Repository provides ordered samples inside the configured window and deletes samples before the window cutoff.
- Learning state remains `LEARNING` until current-window samples span the configured duration (allowing one average sample interval for boundary jitter), contain at least 300 valid samples, and both CV values are below `0.25`.

- [x] Add `PDM_BASELINE_LEARNING_DURATION=PT5M` to `.env.example`, pass it through the app service in Compose, and add the YAML default without changing existing datasource or MQTT settings.
- [x] Replace fixed latest-300 selection with samples in `[sampledAt - learningDuration, sampledAt]`; prune expired samples, verify the current window's sample timestamps span the configured duration within one average interval, update `learnedSamples` to the current window count, and retain the 300 minimum and existing CV/P50 rules.
- [x] Add `learningDurationSeconds` and `minimumSamples` to the baseline API state; ensure new learning and confirmed resets share the same configuration.

### Task 2: Explain the configured window in the UI and README

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/views/BaselineView.vue`
- Modify: `README.md`

**Interfaces:**
- `Baseline` contains `learningDurationSeconds` and `minimumSamples` from Task 1.
- Baseline page shows the configured window and the minimum sample requirement; the progress indicator labels samples as a minimum gate.

- [x] Update the baseline rule text and sample progress label to show that duration, 300-sample minimum, and CV conditions all apply.
- [x] Document `PDM_BASELINE_LEARNING_DURATION` and default `PT5M` for deployment configuration.

### Task 3: Build verification

**Files:**
- Verify: backend Maven package and frontend Vite build.

- [x] Run `mvn -q compiler:compile`; backend compilation passed. The full Maven package was also attempted but frontend `npm ci` could not unlink the locked `frontend/node_modules/@esbuild/win32-x64/esbuild.exe` (EPERM / -4048).
- [x] Run `npm run build` from a clean dependency copy; `vue-tsc` and Vite production build passed. Vite emitted the existing >500 kB chunk advisory.
- [x] Review the final diff to confirm the pre-existing `application.yml` connection changes are preserved.
