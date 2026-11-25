-- 多模态异构数据（协议 2.1.0）：旋转振动慢周期样本可不带 a_peak（文档 §2.1 指标集无 a_peak），
-- 振动基线样本表 a_peak 放宽为可空；既有行全部非空，仅改列约束，不重写表数据。
ALTER TABLE pdm_baseline_sample ALTER COLUMN a_peak DROP NOT NULL;
