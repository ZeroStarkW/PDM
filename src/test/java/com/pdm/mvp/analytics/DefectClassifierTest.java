package com.pdm.mvp.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 确定性根因阶梯（文档 §6.2）：雪崩优先，其次 1X/2X/BPFO/BPFI/ISO/温升短板归因。 */
class DefectClassifierTest {

    @Test
    void avalancheSuppressionOutranksEverything() {
        HealthAssessment assessment = new HealthAssessment(96.8, 100.0, 2.9, "DANGER", 3, null,
                1.5, 0.0302, List.of(new HealthAssessment.FeatureContribution("1X谐波", 1.3, 1.0, 1.3, 96.8)));
        var verdict = DefectClassifier.classify(assessment);
        assertTrue(verdict.isPresent());
        assertTrue(verdict.get().defect().contains("雪崩"), "η_dyn<0.7 必须判雪崩: " + verdict.get().defect());
    }

    @Test
    void dominantShortboardDeterminesDefectName() {
        HealthAssessment assessment = new HealthAssessment(40.0, 100.0, 40.0, "WARNING", 2, null,
                0.0, 1.0, List.of(
                new HealthAssessment.FeatureContribution("1X谐波", 2.2, 1.0, 2.2, 40.0),
                new HealthAssessment.FeatureContribution("2X谐波", 0.6, 0.5, 1.2, 95.0)));
        var verdict = DefectClassifier.classify(assessment);
        assertTrue(verdict.isPresent());
        assertTrue(verdict.get().defect().contains("不平衡"), "1X 短板应判定不平衡: " + verdict.get().defect());

        HealthAssessment misalignment = new HealthAssessment(30.0, 100.0, 30.0, "DANGER", 3, null,
                0.0, 1.0, List.of(
                new HealthAssessment.FeatureContribution("1X谐波", 0.8, 1.0, 0.8, 100.0),
                new HealthAssessment.FeatureContribution("2X谐波", 2.5, 0.5, 5.0, 18.0)));
        assertTrue(DefectClassifier.classify(misalignment).orElseThrow().defect().contains("不对中"));
    }

    @Test
    void healthyScoreProducesNoVerdict() {
        HealthAssessment assessment = new HealthAssessment(100.0, 100.0, 100.0, "HEALTHY", 0, null,
                0.0, 1.0, List.of(new HealthAssessment.FeatureContribution("1X谐波", 1.0, 1.0, 1.0, 100.0)));
        assertTrue(DefectClassifier.classify(assessment).isEmpty());
    }
}
