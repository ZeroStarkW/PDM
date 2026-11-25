# PdM Core Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Deliver a maintainable, device-type-scoped live diagnosis loop with alert evidence and repair feedback.

**Architecture:** Add device types and typed diagnostic rules in PostgreSQL, evaluate validated conditions in the analytics pipeline after baseline activation, and persist a diagnostic snapshot with alerts. Extend existing Vue asset, alert, dashboard, and work-order flows; store server ingest time and data quality separately from sensor measurement time.

**Tech Stack:** Spring Boot, JPA, PostgreSQL migrations, Vue 3, TypeScript, existing MQTT/REST telemetry and InfluxDB store.

**Spec:** `docs/superpowers/specs/2026-11-19-pdm-core-diagnostics-design.md`

## Global Constraints

- Preserve all pre-existing uncommitted changes in the shared workspace.
- Keep health-index and severity calculations unchanged.
- Never execute user-configured expressions or scripts; only allow enumerated metrics/operators.
- Keep historical alert diagnosis as a snapshot.
- Respect existing ADMIN write authorization and existing OPERATOR maintenance actions.
- Do not add or run tests in this task.

## Review Focus

- Missing optional telemetry values must not match rules or crash diagnosis.
- Missing/legacy device type must resolve to GENERAL.
- Equal priority rules must have a stable deterministic ordering.
- Editing or disabling a rule must not rewrite alert/work-order history.
- Low-quality and stopped telemetry must continue to bypass fault scoring.

---

### Task 1: Device types and diagnostic-rule backend

**Files:** `src/main/java/com/pdm/mvp/asset/DeviceEntity.java`, new `DeviceTypeEntity`/repository, `AssetService.java`, `AssetController.java`; new diagnostics rule entity/repository/service/controller; new migration.

**Interfaces:** Device views expose `deviceTypeId`, `deviceTypeCode`, and `deviceTypeName`; type resources expose CRUD/list; rule resources expose list/create/update/enable-disable for a type. Rule fields: metric enum, optional frequency order, comparator enum, threshold, priority, factor title, evidence text, advice, enabled.

- [x] Add migration for a GENERAL type, device type foreign key, and type-scoped diagnostic rule table.
- [x] Default old and new devices to GENERAL; reject deleting a referenced type.
- [x] Validate metric/units/order/threshold and deterministic priority ordering. Supported indicators include 1X/2X absolute amplitude and 1X baseline ratio, 1X-to-2X and 2X-to-1X ratios, velocity RMS, acceleration peak/kurtosis, envelope RMS, temperature, and optional top-peak amplitude/SNR near an estimated harmonic. Rule updates require expected version and return 409 for stale forms.
- [x] Restrict mutations to ADMIN.

### Task 2: Live diagnostic evaluation and alert snapshots

**Files:** `AnalyticsEngine.java`, `HealthIndexCalculator.java` or a dedicated evaluator, `HealthAssessment.java`, `AlertEntity.java`, `AlertRepository.java`, `AlertService.java`, migration for diagnostic snapshots.

**Interfaces:** Diagnostic evaluator accepts device type rules plus the sample, point baseline, and nominal RPM; returns the first matching rule snapshot or generic fallback. Assessment carries dominant factor, evidence, advice, optional rule identity, and observed value.

- [x] Evaluate only after quality filtering and baseline activation.
- [x] Preserve existing HI and severity behavior.
- [x] Persist diagnostic snapshot in alert and include it in alert API views, including observed and target Hz for order-peak rules.
- [x] Merge only active alerts with matching point, severity, and diagnostic rule identity; never overwrite closed/suppressed history.

### Task 3: Device-type rule management and alert UX

**Files:** `frontend/src/api/types.ts`, `frontend/src/api/pdm.ts`, new diagnostic-rules view, `frontend/src/router/index.ts`, `frontend/src/layouts/AppLayout.vue`, `frontend/src/views/DevicesView.vue`, `frontend/src/views/AlertsView.vue`.

- [x] Add an admin page for device-type CRUD and ordered rule management.
- [x] Allow admins to assign a device type when creating/editing devices.
- [x] Display diagnostic factor, evidence, and advice in alert rows/details; poll for new alerts and show frequency evidence.
- [x] Keep current UI dialog/theme changes intact.

### Task 4: Maintenance feedback and quality visibility

**Files:** `TelemetryService.java`, `PointEntity.java`, dashboard DTO/service, work-order migration/entity/service/controller and `frontend/src/views/WorkOrdersView.vue`, frontend dashboard/point status views.

- [x] Add optional diagnosis verdict (`CONFIRMED`, `DIFFERENT`, `UNVERIFIED`), actual finding, and action taken to work-order closure while preserving resolution note and old-client compatibility.
- [x] Expose these fields in the API and work-order history.
- [x] Record latest quality code and server ingest time separately from sensor event time; show freshness as `NO_DATA`/`ONLINE`/`STALE` using a configurable stale duration, with quality and stopped states distinct. Reset the per-point spike/debounce window after bad-quality or stopped data. Historical trend buckets preserve highest health risk, quality code, and a separate stopped flag.

### Task 5: Integration review

- [x] Inspect changed feature paths against the spec and existing dirty workspace changes.
- [x] Resolve identified API/type mismatches and migration ordering issues.
- [x] Report unverified behavior plainly; tests/build are intentionally not run in this task.
