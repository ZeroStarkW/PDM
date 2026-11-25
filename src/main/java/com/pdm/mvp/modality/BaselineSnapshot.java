package com.pdm.mvp.modality;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 基线快照：共享内核与插件之间的基线读模型，按测点 + 模态 + 特征编码组织。
 * 振动模态的快照由旧基线列投影而来，保证迁移期旧数据读取能力。
 */
public record BaselineSnapshot(Long pointId, String modalityType, String status, int learnedSamples,
        Instant lockedAt, String warning, Map<String, Double> featureBase, Map<String, Double> featureCv,
        short binIndex) {

    /** 兼容构造器：单工况（存量调用方）默认 binIndex = 0。 */
    public BaselineSnapshot(Long pointId, String modalityType, String status, int learnedSamples,
            Instant lockedAt, String warning, Map<String, Double> featureBase, Map<String, Double> featureCv) {
        this(pointId, modalityType, status, learnedSamples, lockedAt, warning, featureBase, featureCv, (short) 0);
    }

    /** 指定特征的基线中位数（P50），未学习时为 null。 */
    public Double base(String featureCode) { return featureBase.get(featureCode); }

    /** 指定特征的基线变异系数，未评估时为 null。 */
    public Double cv(String featureCode) { return featureCv.get(featureCode); }

    /** 评分就绪检查：所有指定特征均已有基线值。 */
    public boolean hasAll(Set<String> featureCodes) {
        for (String code : featureCodes) if (featureBase.get(code) == null) return false;
        return true;
    }
}
