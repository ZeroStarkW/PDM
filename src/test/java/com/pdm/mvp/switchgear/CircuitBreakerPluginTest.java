package com.pdm.mvp.switchgear;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pdm.mvp.modality.ModalityPlugin.BaselineLearning;
import com.pdm.mvp.modality.ModalityPlugin.Cadence;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.telemetry.OperatingCondition;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 高压断路器插件单元验证：事件型门禁、特征域校验、分级倍率边界、
 * metadata_config 阈值覆盖、确定性归因与插件声明元数据。
 */
class CircuitBreakerPluginTest {
    private static final long NOW = 1_780_000_000_000L;
    /** 基线：分闸 40ms、线圈峰值 5A、合闸速度 2.15m/s。 */
    private static final Map<String, Double> BASE = Map.of("trip_duration_ms", 40.0,
            "peak_coil_current_a", 5.0, "closing_velocity_mps", 2.15);

    private final CircuitBreakerPlugin plugin = new CircuitBreakerPlugin(new SwitchgearProperties(
            true, 1.10, 1.20, 0.80, Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)));

    @Test
    void eventSamplesAreAlwaysAssessableEvenWithoutOperatingCondition() {
        assertTrue(plugin.gate(sample(Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 5.0), null)).assessable());
        assertTrue(plugin.isOperatingSteady(Map.of()));
        assertTrue(plugin.isOperatingSteady(null));
    }

    @Test
    void tripDurationOutsidePhysicalRangeIsRejected() {
        assertBad(Map.of("trip_duration_ms", 0.0, "peak_coil_current_a", 5.0));
        assertBad(Map.of("trip_duration_ms", -1.0, "peak_coil_current_a", 5.0));
        assertBad(Map.of("trip_duration_ms", 5001.0, "peak_coil_current_a", 5.0));
    }

    @Test
    void negativeOptionalFeaturesAreRejected() {
        assertBad(Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", -0.1));
        assertBad(Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 5.0, "closing_velocity_mps", -0.1));
        assertBad(Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 5.0, "uhf_pd_count", -1.0));
    }

    @Test
    void contactWearRatioOutsideUnitIntervalIsRejected() {
        assertBad(Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 5.0, "contact_wear_ratio", 1.5));
        assertBad(Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 5.0, "contact_wear_ratio", -0.1));
    }

    /** 分级倍率边界：恰好 1.10 满分；1.20 恰好仍在关注档且不构成硬限击穿。 */
    @Test
    void durationRatioBoundariesStayOnAttentionSide() {
        assertEquals(100.0, diagnose(Map.of("trip_duration_ms", 44.0, "peak_coil_current_a", 5.0)).staticHealthScore(),
                0.001, "倍率恰好 1.10 不越关注档");
        var above = diagnose(Map.of("trip_duration_ms", 44.1, "peak_coil_current_a", 5.0));
        assertEquals(60.0, above.staticHealthScore(), 0.001);
        assertFalse(above.isHardLimitBreached());

        var atDanger = diagnose(Map.of("trip_duration_ms", 48.0, "peak_coil_current_a", 5.0));
        assertEquals(60.0, atDanger.staticHealthScore(), 0.001, "倍率恰好 1.20 不越危险档");
        assertFalse(atDanger.isHardLimitBreached());

        var overDanger = diagnose(Map.of("trip_duration_ms", 48.1, "peak_coil_current_a", 5.0));
        assertEquals(25.0, overDanger.staticHealthScore(), 0.001);
        assertTrue(overDanger.isHardLimitBreached());
    }

    /** 电流下限边界：比率恰好 0.80 满分，低于 0.80 判 40 分。 */
    @Test
    void coilCurrentRatioBoundary() {
        assertEquals(100.0, diagnose(Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 4.0)).staticHealthScore(),
                0.001, "比率恰好 0.80 不越下限");
        var below = diagnose(Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 3.9));
        assertEquals(40.0, below.staticHealthScore(), 0.001);
        assertTrue(below.defectAttribution().contains("脱扣线圈驱动力不足"));
    }

    @Test
    void lowerIsWorseClosingVelocityIsScoredAgainstBaseline() {
        var degraded = diagnose(Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 5.0, "closing_velocity_mps", 1.0));
        assertEquals(100.0 / (1.0 + Math.pow((2.15 / 1.0 - 1.0) / 1.5, 2.2)), degraded.staticHealthScore(), 0.1,
                "合闸速度下降按“越低越差”曲线评分");
        assertTrue(degraded.defectAttribution().contains("触头动作速度下降"));
    }

    @Test
    void optionalMechanismFeaturesAbsentStillScoresFullHealth() {
        var result = diagnose(Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 5.0));
        assertEquals(100.0, result.staticHealthScore(), 0.001);
        assertFalse(result.isHardLimitBreached());
        assertEquals("开关机构特性良好", result.defectAttribution());
        var velocity = result.contributions().stream()
                .filter(c -> "合闸速度".equals(c.feature())).findFirst().orElseThrow();
        assertEquals(100.0, velocity.score(), 0.001, "可选特征缺失按满分处理");
    }

    @Test
    void metadataConfigOverridesThresholds() {
        var tightened = plugin.processDiagnosis(
                Map.of("trip_duration_ms", 44.0, "peak_coil_current_a", 5.0), BASE,
                Map.of("duration_danger_ratio", 1.05));
        assertEquals(25.0, tightened.staticHealthScore(), 0.001, "通道配置收紧危险倍率后 1.10 应落危险档");
        assertTrue(tightened.isHardLimitBreached());

        var currentOverride = plugin.processDiagnosis(
                Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 4.4), BASE,
                Map.of("current_attention_ratio", 0.90));
        assertEquals(40.0, currentOverride.staticHealthScore(), 0.001, "通道配置收紧电流下限后 0.88 应判不足");
    }

    @Test
    void pluginDeclaresDocumentedMetadata() {
        assertEquals("switchgearEventPlugin", plugin.identifier());
        assertEquals(ModalityTypes.SWITCHGEAR_EVENT, plugin.modalityType());
        assertEquals("SWITCHGEAR_EVENT", plugin.getModalityType());
        assertEquals(Cadence.EVENT, plugin.cadence());
        assertEquals("trip_duration_ms", plugin.primaryFeatureCode());
        assertEquals(Set.of("peak_coil_current_a", "closing_velocity_mps"), plugin.lowerIsWorseFeatures());
        assertEquals(Set.of("trip_duration_ms", "peak_coil_current_a"), plugin.requiredFeatures());

        BaselineLearning policy = plugin.baselineLearning();
        assertTrue(policy.pooled(), "事件型模态按样本数门槛学习");
        assertEquals(Duration.ofDays(365), policy.window());
        assertEquals(Duration.ofDays(30), plugin.staleAfter(Duration.ofMinutes(5)));
        assertEquals(Duration.ofDays(30), plugin.degradationWindow(Duration.ofHours(24)));

        Set<String> keys = Set.of("duration_attention_ratio", "duration_danger_ratio", "current_attention_ratio");
        assertEquals(keys, Set.copyOf(plugin.metadataFields().stream().map(f -> f.key()).toList()));
    }

    private com.pdm.mvp.modality.MechanismPluginStrategy.PluginDiagnosisResult diagnose(Map<String, Double> current) {
        return plugin.processDiagnosis(current, BASE, Map.of());
    }

    private void assertBad(Map<String, Double> features) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> plugin.validateFeatures(sample(features, null)));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    private TelemetryEnvelope sample(Map<String, Double> features, OperatingCondition condition) {
        return new TelemetryEnvelope(2, ModalityTypes.SWITCHGEAR_EVENT, NOW, "FACT_01", "BKR_10KV_02",
                "TRIP_UNIT", 0, condition, features, null, null);
    }
}
