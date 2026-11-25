# PdM Completeness Upgrades Design

## Intent

Extend the existing Spring Boot, PostgreSQL, InfluxDB, and Vue PdM MVP with three usable operations slices: transparent HI-trend failure estimates, planned maintenance scheduling and records, and durable notification retry/escalation. The system serves maintenance operators who need actionable signals and traceable work without treating unvalidated analytics as certainty.

## Current State

- Telemetry is validated and stored in InfluxDB; baseline learning, rule diagnosis, HI scoring, alerts, and alert-linked work orders already exist.
- The analytics path does not train a failure model or calculate remaining useful life.
- Work orders are alert-linked; planned maintenance plans and maintenance history are not modeled.
- Webhooks are sent asynchronously once after alert commit. Failures are logged but not persisted for retry, and unacknowledged alerts are not escalated.

## Goals

1. Provide per-device HI trend estimates toward the existing HI 50 warning threshold, with evidence window, slope, projected days, and explicit insufficient-data/non-declining states. This is a heuristic estimate, not an RUL model or failure probability.
2. Let operators create recurring maintenance plans, see due/overdue maintenance tasks, assign them, and close tasks with findings and actions. Preserve completed records.
3. Persist notification events and retry failed webhook delivery with bounded exponential backoff. Create one escalation event for an alert that remains unacknowledged past a configurable duration.
4. Expose the features in the existing Vue app and enforce existing ADMIN/OPERATOR roles for mutations.

## Non-goals

- No machine-learning model, failure probability, component-level RUL, automatic shutdown, or maintenance recommendations based on unsupported failure labels.
- No CMMS/EAM integration, parts inventory, multi-channel vendor-specific cards, user routing policy editor, or high-availability deployment.
- No changes to telemetry formats or existing alert thresholds.

## Approaches

1. **Transparent HI trend estimate (selected):** Reuse hourly HI telemetry and compute a bounded linear trend with data sufficiency and confidence indicators. Small, explainable, and compatible with existing data; cannot predict failure modes or prove RUL.
2. Train a predictive model: deferred because the repository does not contain labeled historical failures or validated outcomes.
3. **Recurring plan + task records (selected):** Add plan and maintenance task tables with a scheduler that materializes due tasks idempotently. This gives a concrete operating workflow while preserving a maintenance history.
4. Extend alert-linked work orders only: smaller schema change, but it conflates corrective and preventive maintenance and cannot represent recurring schedules; not selected.
5. **Database-backed notification outbox (selected):** Store each alert/escalation delivery, retry from scheduled worker, and expose status through alert data. More durable than in-memory retries while fitting the existing PostgreSQL deployment.
6. Add queue infrastructure: deferred because it introduces broker operations before the MVP needs them.

## Behavior and Data

### Failure estimate

- Query valid hourly health-index points over a bounded recent window (up to 30 days).
- Require at least 24 hourly observations spanning at least 24 hours. Otherwise return `INSUFFICIENT_DATA`.
- Fit a least-squares line to timestamp versus HI. A non-negative slope returns `NO_DECLINE`; a negative slope projects days to HI 50. If already at/below 50, return `THRESHOLD_REACHED`.
- Include observation count, window start/end, slope per day, current HI, projected days when available, fit score, and a qualitative confidence (`LOW`, `MEDIUM`, `HIGH`). Label the output as a trend estimate and avoid a categorical predicted fault date.
- Device estimate uses the earliest valid threshold projection among scored points and identifies the contributing point.

### Maintenance management

- Add maintenance plans with device, title, interval days, next due time, instructions, assignee, and enabled state.
- Add maintenance tasks with optional plan link, device, due time, status (`OPEN`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`), assignee, findings, action, and timestamps.
- Creating a plan materializes its first task. A scheduled service materializes due tasks once per plan occurrence and advances the next due time transactionally. Unique `(plan_id, due_at)` prevents duplicates.
- ADMIN can create/update/disable plans. ADMIN and OPERATOR can assign, start, complete, or cancel tasks. Completed/cancelled records remain queryable.

### Notification reliability

- Add an outbox table with event kind (`ALERT`, `ESCALATION`), JSON payload, delivery state, attempt count, due time, last error, and sent time. Enforce unique alert/event kind.
- Insert the initial alert event in the same transaction as alert creation; a scheduled worker delivers pending events and retries transient failures with bounded exponential backoff, up to 8 attempts. Exhausted events remain visible as `FAILED`; ADMIN/OPERATOR can explicitly requeue them.
- After a configurable acknowledgement SLA, enqueue one escalation event for each still-unacknowledged, non-suppressed alert. Delivery uses the configured generic webhook and marks the event independently.
- Expose delivery status/attempts on alert detail/list views. Never mark an alert acknowledged because delivery succeeded.
- A failed event can be manually requeued, which resets its attempt counter and makes it immediately due; only one manual requeue is processed at a time under a row lock.

## Constraints and Calls

- Keep the current Java/Spring and Vue conventions; add no runtime dependency or external broker.
- Keep database changes additive Flyway migrations and allocate non-overlapping migration versions per implementation slice.
- All estimates must be labeled heuristic; insufficient or stale data must not produce a numeric RUL.
- Existing local demo configuration and alert/work-order behavior remain compatible.

## Acceptance

- Device detail clearly distinguishes trend estimate, threshold reached, no decline, and insufficient data, with window and confidence evidence.
- Operators can create a plan, see its generated due task, assign it, complete it with findings/actions, and review its history.
- A transient webhook failure remains pending and is retried; after 8 failed attempts it remains visible as failed and can be manually retried. An unacknowledged alert past the configured SLA gets exactly one escalation delivery event.
- Frontend production build and Java compile/package without running test suites; full test execution is outside this request.
