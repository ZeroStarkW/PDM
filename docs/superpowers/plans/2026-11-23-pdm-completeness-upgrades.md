# PdM Completeness Upgrades Implementation Plan

> **For agentic workers:** Implement the feature slices in parallel using the interfaces and file ownership below. Workers share a working tree; do not revert or overwrite another slice. The parent agent integrates configuration, documentation, and review.

**Goal:** Add transparent HI-trend failure estimates, recurring maintenance planning/tasks, and persistent webhook retry/escalation to the PdM MVP.

**Architecture:** Keep the current Spring Boot/PostgreSQL/InfluxDB/Vue stack. Implement three vertically complete slices with independent Flyway migrations; the parent owns shared config/docs and resolves integration conflicts.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, PostgreSQL/Flyway, InfluxDB 3, Vue 3, TypeScript.

**Spec:** `docs/superpowers/specs/2026-11-23-pdm-completeness-upgrades-design.md`

## Global Constraints

- Do not introduce a predictive model or state a failure probability; expose only a clearly labeled HI-trend estimate.
- Insufficient/stale HI history yields no numeric projection.
- Maintenance mutations require ADMIN or OPERATOR authority according to the spec; plan administration is ADMIN-only.
- Notification delivery state is durable and independent from alert acknowledgement.
- Keep migrations additive and use distinct versions: maintenance V9, notification V10, prediction V11.
- Do not edit `.env` or reveal credential contents.

## Review Focus

- Sparse or flat/rising HI history must never yield a fabricated RUL; prediction slice owns this condition.
- Concurrent schedule ticks must not create duplicate tasks; maintenance slice owns unique occurrence handling.
- Webhook failures/restarts must retain pending deliveries and escalation must be idempotent; notification slice owns this behavior.
- Completed maintenance history must remain visible and immutable enough for audit; maintenance slice owns this behavior.
- New controls must respect role checks and remain usable at narrow widths; parent/reviewer checks integration.

---

### Task 1: HI Trend Estimate

**Owner:** Prediction worker.

**Files:** Create prediction service/controller/DTO and Flyway `V11__hi_trend_prediction.sql` only if a schema field is truly required; modify `TelemetryStore` consumers only as needed; add a prediction panel to `DeviceDetailView.vue`; modify `frontend/src/api/pdm.ts` and `frontend/src/api/types.ts` only for prediction API types/calls.

**Interface:** `GET /api/v1/devices/{deviceId}/failure-prediction` returns device-level status, source point, current HI, slope/day, projected days to HI 50, observation count/window, fit score, and confidence. It uses hourly telemetry over the last 30 days and enforces the 24-point/24-hour minimum.

**Acceptance:** Device page distinguishes `INSUFFICIENT_DATA`, `NO_DECLINE`, `THRESHOLD_REACHED`, and `ESTIMATE_AVAILABLE`; it labels the result as a heuristic trend estimate and shows its evidence window.

### Task 2: Maintenance Plans and Tasks

**Owner:** Maintenance worker.

**Files:** Create maintenance entities/repositories/services/controllers/DTOs; create Flyway `V9__maintenance_plans_and_tasks.sql`; add `MaintenanceView.vue`; modify router, app layout, and API types/client for this module only.

**Interface:** Plan CRUD under `/api/v1/maintenance/plans`; task list and lifecycle under `/api/v1/maintenance/tasks`. Plans materialize one task immediately and recurring due tasks are generated idempotently by a scheduled service. Task completion captures findings and actions.

**Acceptance:** Admin creates/disables a plan; operators list, assign, start, complete, and cancel tasks; due/overdue state is visible; completed history remains in the list.

### Task 3: Persistent Notification Retry and Escalation

**Owner:** Notification worker.

**Files:** Create outbox entity/repository/service/DTO; create Flyway `V10__notification_outbox.sql`; modify `AlertService`, `WebhookNotifier`, `NotificationConfiguration`, alert API response and alert UI; do not edit shared `application.yml` (parent integrates properties).

**Interface:** Outbox events are unique by `(alert_id, event_kind)`. A scheduled worker sends due events, retries with bounded exponential backoff for at most 8 attempts, marks exhausted events `FAILED`, and creates a single escalation event after configurable acknowledgement SLA. ADMIN/OPERATOR can requeue a failed event. Generic webhook payload carries event kind and attempt metadata.

**Acceptance:** Failed delivery survives process restart and is retried; attempts exhausted is visible as `FAILED` and can be requeued; success is visible independently of `alert.status`; an unacknowledged alert has at most one escalation event.

### Task 4: Integration and Documentation

**Owner:** Parent.

**Files:** `src/main/resources/application.yml`, `README.md`, docs spec/plan, resolve only necessary shared UI files.

- [ ] Inspect the three slices for distinct migration versions, routes, and DTO compatibility.
- [ ] Add configuration defaults for outbox polling, max attempts/backoff, and escalation SLA without secrets.
- [ ] Update README capability and limitation sections to describe the heuristic and generic-webhook boundaries.
- [ ] Run Maven compile/package with tests skipped and frontend production build; report any pre-existing or environment failures.
- [ ] Dispatch Java and TypeScript review agents after integration and address actionable findings.
