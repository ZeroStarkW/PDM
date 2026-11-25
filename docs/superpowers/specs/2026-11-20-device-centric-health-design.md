# Device-Centric Health Design

## Intent

Make the equipment asset the primary unit for health assessment, alerting, and maintenance. Measurement points remain independent evidence channels with point-specific telemetry, baselines, and diagnostics.

## Goals

- Show current health and risk by device across the dashboard and asset list.
- Summarize the device's measurement-point evidence and data completeness.
- Use a transparent conservative MVP score: the lowest valid point health index represents current device health, so a severe point cannot be hidden by averaging.
- Create and deduplicate alerts and work orders at device scope while retaining the triggering point and a snapshot of all point evidence.
- Preserve point detail and baseline workflows.
- Start baseline reset from the device context, defaulting to all active baselines on that device while allowing a selected subset of affected points.

## Non-goals

- Do not claim remaining useful life or failure probability without a trained model and sufficient failure history.
- Do not merge point baselines or point diagnosis rules into a device-wide signal.
- Do not make sensor communication faults into equipment-failure alerts.

## Approaches

- Point-only workflow: rejected because it keeps maintenance triage centered on sensor locations.
- Weighted mean of point scores: rejected for the MVP because it can conceal one dangerous point and requires validated, type-specific weights.
- Device health using the worst valid point, with evidence and completeness shown: selected as the smallest explainable equipment-level analysis consistent with current point analytics. Device-type weighting can follow when validated data and rules exist.

## Behavior

- Point health remains calculated independently from its own baseline and samples.
- Device health uses the lowest available HI among valid running points. The device status follows the most severe valid point state. Missing, stale, stopped, sensor-check, and baseline-learning points are represented as evidence and affect completeness; they are not interpreted as equipment failure.
- Device baseline reset is a two-step request and confirmation; each affected point independently relearns from its own samples.
- A point in PENDING_RESET or LEARNING is excluded from the device HI until its own baseline is active again.
- A point in PENDING_RESET or LEARNING is excluded from the device HI until its own baseline is active again.
- Device alert state is debounced on a point's consecutive low-HI samples and deduplicated per device, severity, and diagnosis rule. The initial trigger point and its 1X/baseline evidence stay aligned, while the latest point evidence snapshot is refreshed with the event.
- The dashboard and work queues lead with devices; users drill into the device's point evidence and then point trends.

## Constraints and risks

- Existing alert/work-order history must remain readable after migration.
- The conservative minimum-score rollup can be more sensitive than a weighted model; show its method and data coverage in the UI.
- A device with only one monitored point has a valid device score but limited evidence coverage.
