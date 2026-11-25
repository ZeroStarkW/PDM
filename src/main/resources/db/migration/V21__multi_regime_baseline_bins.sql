-- 多工况分箱基线：为振动样本表、多模态样本表与特征状态表引入工况分箱键 bin_index。
-- 兼容红线：存量行全部落 bin_index = 0（默认/额定工况箱），语义与迁移前完全一致；
-- 唯一约束升级为包含 bin_index，使同一测点同一时刻在不同转速/负载工况箱下可各自积累样本。
-- 注意：V11 中 pdm_baseline_feature_state / pdm_baseline_sample_v2 的 UNIQUE 是未命名内联约束
-- （PostgreSQL 自动生成的约束名会被截断到 63 字节），因此用 pg_constraint 动态查找并删除后再建新约束。

ALTER TABLE pdm_baseline_sample
    ADD COLUMN bin_index SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE pdm_baseline_sample
    DROP CONSTRAINT IF EXISTS uk_point_sample_time,
    ADD CONSTRAINT uk_point_bin_sample_time UNIQUE (point_id, bin_index, sampled_at);

DROP INDEX IF EXISTS idx_baseline_sample_point_time;
CREATE INDEX idx_baseline_sample_point_bin_time
    ON pdm_baseline_sample (point_id, bin_index, sampled_at);

ALTER TABLE pdm_baseline_sample_v2
    ADD COLUMN bin_index SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE pdm_baseline_feature_state
    ADD COLUMN bin_index SMALLINT NOT NULL DEFAULT 0;

DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'pdm_baseline_sample_v2'::regclass
          AND contype = 'u'
    LOOP
        EXECUTE format('ALTER TABLE pdm_baseline_sample_v2 DROP CONSTRAINT %I', rec.conname);
    END LOOP;

    FOR rec IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'pdm_baseline_feature_state'::regclass
          AND contype = 'u'
    LOOP
        EXECUTE format('ALTER TABLE pdm_baseline_feature_state DROP CONSTRAINT %I', rec.conname);
    END LOOP;
END $$;

ALTER TABLE pdm_baseline_sample_v2
    ADD CONSTRAINT uk_baseline_sample_v2 UNIQUE (point_id, modality_type, bin_index, sampled_at);

DROP INDEX IF EXISTS idx_baseline_sample_v2_point_time;
CREATE INDEX idx_baseline_sample_v2_point_bin_time
    ON pdm_baseline_sample_v2 (point_id, modality_type, bin_index, sampled_at);

ALTER TABLE pdm_baseline_feature_state
    ADD CONSTRAINT uk_baseline_feature_state UNIQUE (point_id, modality_type, bin_index, feature_code);

DROP INDEX IF EXISTS idx_baseline_feature_state_point;
CREATE INDEX idx_baseline_feature_state_point_bin
    ON pdm_baseline_feature_state (point_id, modality_type, bin_index);
