-- 多模态底座：测点模态 + 按测点/模态/特征编码管理的基线状态 + 新模态基线样本
-- 迁移期保留旧读取能力：振动基线仍以 pdm_measurement_point 旧列与 pdm_baseline_sample 为准，
-- 新表为增量写（双写），旧列与旧表不改名、不删列。

ALTER TABLE pdm_measurement_point
    ADD COLUMN modality_type VARCHAR(32) NOT NULL DEFAULT 'ROTATING_VIB';

ALTER TABLE pdm_measurement_point
    ADD CONSTRAINT ck_point_modality_type CHECK (modality_type IN ('ROTATING_VIB', 'MCSA'));

CREATE TABLE pdm_baseline_feature_state (
    id BIGSERIAL PRIMARY KEY,
    point_id BIGINT NOT NULL REFERENCES pdm_measurement_point(id) ON DELETE CASCADE,
    modality_type VARCHAR(32) NOT NULL,
    feature_code VARCHAR(64) NOT NULL,
    p50 DOUBLE PRECISION,
    cv DOUBLE PRECISION,
    sample_count INTEGER NOT NULL DEFAULT 0,
    status SMALLINT NOT NULL DEFAULT 0 CHECK (status BETWEEN 0 AND 3),
    locked_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (point_id, modality_type, feature_code)
);

CREATE INDEX idx_baseline_feature_state_point ON pdm_baseline_feature_state(point_id);

-- 回填既有振动基线（旧列 → 特征状态行；旧数据未按特征留存 CV，置空由下次学习覆盖）
INSERT INTO pdm_baseline_feature_state
    (point_id, modality_type, feature_code, p50, cv, sample_count, status, locked_at, updated_at)
SELECT id, 'ROTATING_VIB', 'freq_1x_amp', freq_1x_base, NULL::double precision, learned_samples, baseline_status, baseline_locked_at, now()
FROM pdm_measurement_point WHERE freq_1x_base IS NOT NULL
UNION ALL
SELECT id, 'ROTATING_VIB', 'freq_2x_amp', freq_2x_base, NULL::double precision, learned_samples, baseline_status, baseline_locked_at, now()
FROM pdm_measurement_point WHERE freq_2x_base IS NOT NULL
UNION ALL
SELECT id, 'ROTATING_VIB', 'v_rms', v_base_rms, NULL::double precision, learned_samples, baseline_status, baseline_locked_at, now()
FROM pdm_measurement_point WHERE v_base_rms IS NOT NULL
UNION ALL
SELECT id, 'ROTATING_VIB', 'a_peak', a_base_peak, NULL::double precision, learned_samples, baseline_status, baseline_locked_at, now()
FROM pdm_measurement_point WHERE a_base_peak IS NOT NULL
UNION ALL
SELECT id, 'ROTATING_VIB', 'envelope_bpfo_amp', envelope_bpfo_base, NULL::double precision, learned_samples, baseline_status, baseline_locked_at, now()
FROM pdm_measurement_point WHERE envelope_bpfo_base IS NOT NULL
UNION ALL
SELECT id, 'ROTATING_VIB', 'temperature', temp_base, NULL::double precision, learned_samples, baseline_status, baseline_locked_at, now()
FROM pdm_measurement_point WHERE temp_base IS NOT NULL;

CREATE TABLE pdm_baseline_sample_v2 (
    id BIGSERIAL PRIMARY KEY,
    point_id BIGINT NOT NULL REFERENCES pdm_measurement_point(id) ON DELETE CASCADE,
    modality_type VARCHAR(32) NOT NULL,
    sampled_at TIMESTAMPTZ NOT NULL,
    feature_values JSONB NOT NULL,
    quality_code SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (point_id, modality_type, sampled_at)
);

CREATE INDEX idx_baseline_sample_v2_point_time
    ON pdm_baseline_sample_v2(point_id, modality_type, sampled_at);
