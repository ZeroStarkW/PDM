ALTER TABLE pdm_alert_event
    ADD COLUMN diagnostic_device_type_id BIGINT,
    ADD COLUMN diagnostic_device_type_name VARCHAR(128),
    ADD COLUMN diagnostic_rule_id BIGINT,
    ADD COLUMN diagnostic_rule_version BIGINT,
    ADD COLUMN diagnostic_metric VARCHAR(32),
    ADD COLUMN diagnostic_operator VARCHAR(3),
    ADD COLUMN diagnostic_threshold DOUBLE PRECISION,
    ADD COLUMN diagnostic_observed_value DOUBLE PRECISION,
    ADD COLUMN diagnostic_frequency_order DOUBLE PRECISION,
    ADD COLUMN diagnostic_observed_frequency_hz DOUBLE PRECISION,
    ADD COLUMN diagnostic_target_frequency_hz DOUBLE PRECISION,
    ADD COLUMN diagnostic_evidence VARCHAR(512),
    ADD COLUMN diagnostic_advice VARCHAR(512),
    ADD COLUMN suppressed_at TIMESTAMPTZ;

UPDATE pdm_alert_event a SET suppressed_at = COALESCE(
    (SELECT w.closed_at FROM pdm_work_order w WHERE w.alert_id = a.id), a.last_seen_at
)
WHERE a.status = 3;

CREATE INDEX idx_alert_open_and_suppressed_diagnosis ON pdm_alert_event (
    point_id, alert_level, diagnostic_rule_id, diagnostic_rule_version, diagnostic_device_type_id, created_at DESC
) WHERE status IN (0, 1, 3);
