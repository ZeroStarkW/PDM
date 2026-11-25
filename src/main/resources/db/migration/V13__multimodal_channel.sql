-- 多模态异构数据：放宽测点模态约束（新模态经插件注册扩展，不再在数据库枚举中固化）+ 通道模型扩展（采样率/工程单位/机理参数）
ALTER TABLE pdm_measurement_point DROP CONSTRAINT ck_point_modality_type;
ALTER TABLE pdm_measurement_point
    ADD CONSTRAINT ck_point_modality_type_format CHECK (modality_type ~ '^[A-Z][A-Z0-9_]{0,31}$');
ALTER TABLE pdm_measurement_point
    ADD COLUMN sampling_rate INTEGER,
    ADD COLUMN engineering_unit VARCHAR(32),
    ADD COLUMN metadata_config JSONB;
