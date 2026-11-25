-- 频谱基线带：对历史波形快照按 1/6 倍频程频带聚合，逐带 MAD 学习健康参考带（文档 §4.2 的谱域扩展）。
-- 生命周期独立于标量特征基线：波形快照稀疏（阈值/巡检/召唤触发），按自身样本计数冻结；
-- 基线重置路径（confirmReset）一并清空，保持“一条重置路径”。

CREATE TABLE pdm_spectrum_band_sample (
    id BIGSERIAL PRIMARY KEY,
    point_id BIGINT NOT NULL REFERENCES pdm_measurement_point(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('FFT', 'ENVELOPE')),
    sampled_at TIMESTAMPTZ NOT NULL,
    band_values JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (point_id, kind, sampled_at)
);

CREATE INDEX idx_spectrum_band_sample_point_time
    ON pdm_spectrum_band_sample(point_id, kind, sampled_at);

CREATE TABLE pdm_spectrum_band_state (
    id BIGSERIAL PRIMARY KEY,
    point_id BIGINT NOT NULL REFERENCES pdm_measurement_point(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('FFT', 'ENVELOPE')),
    band_index INTEGER NOT NULL,
    band_low_hz DOUBLE PRECISION NOT NULL,
    band_center_hz DOUBLE PRECISION NOT NULL,
    band_high_hz DOUBLE PRECISION NOT NULL,
    p50 DOUBLE PRECISION NOT NULL,
    mad DOUBLE PRECISION NOT NULL,
    sample_count INTEGER NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (point_id, kind, band_index)
);

CREATE INDEX idx_spectrum_band_state_point
    ON pdm_spectrum_band_state(point_id, kind);
