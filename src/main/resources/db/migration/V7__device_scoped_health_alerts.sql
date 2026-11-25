ALTER TABLE pdm_alert_event
    ADD COLUMN evidence_snapshot JSONB;

CREATE INDEX idx_alert_device_level_time
    ON pdm_alert_event(device_id, alert_level, created_at DESC)
    WHERE status IN (0, 1, 3);
