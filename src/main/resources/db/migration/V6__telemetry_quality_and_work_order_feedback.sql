ALTER TABLE pdm_measurement_point
    ADD COLUMN quality_code INTEGER CHECK (quality_code BETWEEN 0 AND 3),
    ADD COLUMN ingested_at TIMESTAMPTZ;

ALTER TABLE pdm_work_order
    ADD COLUMN diagnosis_verdict VARCHAR(16)
        CHECK (diagnosis_verdict IN ('CONFIRMED', 'DIFFERENT', 'UNVERIFIED')),
    ADD COLUMN actual_finding TEXT,
    ADD COLUMN action_taken TEXT;
