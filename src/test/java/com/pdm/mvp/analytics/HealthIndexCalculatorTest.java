package com.pdm.mvp.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import com.pdm.mvp.telemetry.VibrationContractAdapter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 综合健康评分：15% 死区、文档版 ISO 10816 曲线、温升补偿评分与 η_dyn 动力学抑制。
 */
class HealthIndexCalculatorTest {
    private final HealthIndexCalculator calculator = new HealthIndexCalculator();

    @Test
    void keepsFifteenPercentDeadbandAtWorstRelativeFeature() {
        BaselineSnapshot baseline = baseline(Map.of(
                VibrationContractAdapter.FREQ_1X_AMP, 1.0,
                VibrationContractAdapter.FREQ_2X_AMP, 0.5,
                VibrationContractAdapter.TEMPERATURE, 50.0));
        TelemetryEnvelope sample = envelope(1.15, 0.575, 1.0, 50.0, null, 0.0);

        HealthAssessment assessment = calculator.calculate(sample, baseline, 0.0, null);

        assertEquals(100.0, assessment.hiRelative(), 0.001);
        assertEquals(100.0, assessment.healthIndex(), 0.001);
        assertEquals("HEALTHY", assessment.level());
    }

    /** 文档 §6.2 ISO 曲线：2.8~4.5 线性降至 45；>4.5 以 30-(v-4.5)*10 衰减、下限 10。 */
    @Test
    void appliesDocumentedIsoCurve() {
        assertEquals(100.0, HealthIndexCalculator.absoluteIsoScore(2.8), 0.001);
        assertEquals(70.0 - (0.5 / 1.7) * 25.0, HealthIndexCalculator.absoluteIsoScore(3.3), 0.001);
        assertEquals(45.0, HealthIndexCalculator.absoluteIsoScore(4.5), 0.001);
        assertEquals(30.0 - 0.5 * 10.0, HealthIndexCalculator.absoluteIsoScore(5.0), 0.001);
        assertEquals(10.0, HealthIndexCalculator.absoluteIsoScore(9.0), 0.001);
    }

    /** 温升补偿：温升 30℃ / 基线 20℃ → 比率 1.5 → 70-(0.2)*80 = 54。 */
    @Test
    void scoresTemperatureRiseAgainstBaselineRise() {
        BaselineSnapshot baseline = baseline(Map.of(
                VibrationContractAdapter.FREQ_1X_AMP, 1.0,
                VibrationContractAdapter.TEMP_RISE, 20.0));
        TelemetryEnvelope sample = envelope(0.5, 0.0, 1.0, 52.0, 22.0, 0.0);

        HealthAssessment assessment = calculator.calculate(sample, baseline, 0.0, null);

        double ratioContribution = assessment.contributions().stream()
                .filter(contribution -> "温升".equals(contribution.feature())).findFirst().orElseThrow().score();
        assertEquals(54.0, ratioContribution, 0.001);
        assertEquals(54.0, assessment.healthIndex(), 0.001);
    }

    /** η_dyn 乘子：斜率 1.5/24h → exp(-2.5*1.4)≈0.030，静态 100 分被强制压至 3 分量级。 */
    @Test
    void suppressesHealthIndexWithAvalancheMultiplier() {
        BaselineSnapshot baseline = baseline(Map.of(VibrationContractAdapter.FREQ_1X_AMP, 1.0));
        TelemetryEnvelope sample = envelope(0.5, 0.0, 1.0, null, null, 0.0);

        HealthAssessment assessment = calculator.calculate(sample, baseline, 1.5, null);

        assertEquals(0.0301973833, assessment.etaDyn(), 1e-6);
        assertTrue(assessment.healthIndex() < 45.0, "雪崩乘子必须把得分强制压至 <45: " + assessment.healthIndex());
    }

    private BaselineSnapshot baseline(Map<String, Double> bases) {
        return new BaselineSnapshot(1L, com.pdm.mvp.modality.ModalityTypes.ROTATING_VIB, "ACTIVE", 200,
                Instant.now(), null, new LinkedHashMap<>(bases), Map.of());
    }

    private TelemetryEnvelope envelope(double oneX, double twoX, double vRms, Double temperature,
            Double ambient, double bpfo) {
        Map<String, Double> features = new LinkedHashMap<>();
        features.put(VibrationContractAdapter.V_RMS, vRms);
        features.put(VibrationContractAdapter.A_PEAK, 1.0);
        features.put(VibrationContractAdapter.A_KURTOSIS, 3.0);
        features.put(VibrationContractAdapter.FREQ_1X_AMP, oneX);
        features.put(VibrationContractAdapter.FREQ_2X_AMP, twoX);
        if (bpfo > 0) features.put(VibrationContractAdapter.ENVELOPE_BPFO_AMP, bpfo);
        if (temperature != null) features.put(VibrationContractAdapter.TEMPERATURE, temperature);
        if (ambient != null) features.put(VibrationContractAdapter.TEMP_AMBIENT, ambient);
        return new TelemetryEnvelope(2, com.pdm.mvp.modality.ModalityTypes.ROTATING_VIB,
                System.currentTimeMillis(), "FACT01", "PUMP01", "DE", 0, null, features, null, null);
    }
}
