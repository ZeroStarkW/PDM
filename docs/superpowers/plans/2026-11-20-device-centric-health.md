# Device-Centric Health Implementation Plan

> **For agentic workers:** Implement the approved direction in this plan task by task.

**Goal:** Make device-level health, alerting, and maintenance the primary PdM flow while preserving measurement-point evidence.

**Architecture:** Keep point-level analytics and baselines. Add a device health rollup over the latest point state, expose devices and evidence from the dashboard API, and scope new alerts/work orders to devices with point evidence snapshots. Reframe the Vue dashboard, asset list, alerts, and work orders around devices.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL/Flyway, Vue 3, TypeScript.

**Spec:** `docs/superpowers/specs/2026-11-20-device-centric-health-design.md`

## Global Constraints

- Existing telemetry ingestion and point baseline learning remain intact.
- Historical alerts and work orders remain readable.
- Sensor/data-quality states remain distinct from equipment-failure states.
- Device HI uses the minimum valid point HI until a validated type-specific aggregation model is available.

## Review Focus

- No valid point HI: device health must be pending/unknown, not healthy.
- One dangerous point among several healthy points: device HI/status must remain dangerous.
- Stale, stopped, or sensor-check points: show incomplete coverage without fabricating a device fault.
- Existing alerts/work orders: remain readable and retain their original point evidence.
- Multiple point observations on one device: deduplicate a single active device alert by severity/rule.

---

### Task 1: Device health aggregation API

**Files:** Add a device health assessment/service; modify `DashboardService`, point/device repositories, and dashboard API types.

- Aggregate latest point health by device using the minimum valid point HI.
- Return device status, score method, point counts by data state, and point evidence.
- Make overview status counts device-based while retaining point-specific metrics where needed.

### Task 2: Device-scoped alerts and work orders

**Files:** Modify `AlertEntity`, `AlertRepository`, `AlertService`, `PointHealthWindow`, work-order DTOs; add a Flyway migration.

- Dedupe/debounce alerts by device and preserve the triggering point.
- Snapshot contributing point health/status/freshness for alert traceability.
- Return device-first fields from alerts and work orders while preserving legacy records.

### Task 3: Device-first user experience

**Files:** Modify dashboard, device list, alerts, work-order views, API types/client, and route metadata; add a device detail view if needed.

- Lead with a device health table and device status distribution.
- Show point evidence beneath each device and on alert/work-order detail rows.
- Keep point trends and baseline management accessible as drill-down workflows.

### Task 4: Documentation and verification

**Files:** Update `README.md` and relevant product docs.

- Explain device score semantics, data completeness, and point evidence.
- Review migration compatibility and inspect the final diff. Do not run tests unless separately requested.

### Task 5: Device-scoped baseline reset

**Files:** Modify `BaselineService` and `DashboardService.PointHealth`; add device baseline endpoints; update API types/client and `BaselineView.vue`.

- Add device-level request/confirm operations that accept optional point IDs; omitted or empty IDs mean all points eligible for that action.
- Validate selected points belong to the device and apply the existing two-step reset only to active/pending baselines.
- Replace per-point reset controls with device groups and selectable affected points. Keep individual learning metrics and baseline values visible.
- Reset alert debounce for each affected point after request/confirmation and refresh related baseline cards.
