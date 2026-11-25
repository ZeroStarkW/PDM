ALTER TABLE pdm_measurement_point
    ADD COLUMN freq_2x_base DOUBLE PRECISION,
    ADD COLUMN envelope_bpfo_base DOUBLE PRECISION,
    ADD COLUMN baseline_cv DOUBLE PRECISION;

ALTER TABLE pdm_baseline_sample
    ADD COLUMN rpm DOUBLE PRECISION,
    ADD COLUMN freq_2x_amp DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN envelope_bpfo_amp DOUBLE PRECISION;

ALTER TABLE pdm_measurement_point
    ADD CONSTRAINT ck_measurement_point_baseline_status CHECK (baseline_status BETWEEN 0 AND 3);
