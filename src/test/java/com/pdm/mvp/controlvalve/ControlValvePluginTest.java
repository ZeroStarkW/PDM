package com.pdm.mvp.controlvalve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pdm.mvp.modality.ModalityPlugin.BaselineLearning;
import com.pdm.mvp.modality.ModalityPlugin.Cadence;
import com.pdm.mvp.modality.ModalityPlugin.ConditionGate;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.telemetry.OperatingCondition;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 调节阀插件单元验证：稳态门禁、特征域校验、死区分级/泄漏绝对阈值边界、
 * metadata_config 覆盖、确定性归因、回环特征列与插件声明元数据。
 */
class ControlValvePluginTest {
    private static final long NOW = 1_780_000_000_000L;
    /** 基线：死区 1.0%、超声泄漏 0.04。 */
    private static final Map<String, Double> BASE = Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04);

    private final ControlValvePlugin plugin = new ControlValvePlugin(new ControlValveProperties(
            true, 2.5, 5.0, 0.2, 0.5, Duration.ofHours(1)), Duration.ofHours(24));

    @Test
    void onlySteadySamplesAreAssessable() {
        assertTrue(plugin.gate(sample(Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04),
                new OperatingCondition(null, null, true))).assessable());
        ConditionGate unsteady = plugin.gate(sample(Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04),
                new OperatingCondition(null, null, false)));
        assertFalse(unsteady.assessable());
        assertEquals("CONDITION_REJECTED", unsteady.status());
        assertEquals(6, unsteady.statusCode());
        assertFalse(plugin.gate(sample(Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04), null))
                .assessable(), "缺工况视为非稳态");
        assertFalse(plugin.isOperatingSteady(Map.of()));
        assertTrue(plugin.isOperatingSteady(Map.of("steady_state", Boolean.TRUE)));
    }

    @Test
    void negativeValveFeaturesAreRejected() {
        assertBad(Map.of("hysteresis_deadband_pct", -0.1, "ultrasonic_leak_rms", 0.04));
        assertBad(Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", -0.1));
        assertBad(Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04, "travel_time_lag_ms", -1.0));
        assertBad(Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04, "friction_force_kn", -1.0));
    }

    @Test
    void percentFeaturesAboveHundredAreRejected() {
        assertBad(Map.of("hysteresis_deadband_pct", 120.0, "ultrasonic_leak_rms", 0.04));
        assertBad(Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04, "cv_pct", 150.0));
        assertBad(Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04, "fb_pct", 101.0));
    }

    /** 死区分级倍率边界：k 恰好 2.5 满分，2.6 关注档 40 分；k 恰好 5.0 仍在关注档，5.1 危险档 20 分。 */
    @Test
    void deadbandGradedRatioBoundaries() {
        assertEquals(100.0, diagnose(Map.of("hysteresis_deadband_pct", 2.5)).staticHealthScore(), 0.001,
                "k=2.5 恰好不越关注档");
        var attention = diagnose(Map.of("hysteresis_deadband_pct", 2.6));
        assertEquals(40.0, attention.staticHealthScore(), 0.001);
        assertFalse(attention.isHardLimitBreached());

        var atDanger = diagnose(Map.of("hysteresis_deadband_pct", 5.0));
        assertEquals(40.0, atDanger.staticHealthScore(), 0.001, "k=5.0 恰好不越危险档");

        var danger = diagnose(Map.of("hysteresis_deadband_pct", 5.1));
        assertEquals(20.0, danger.staticHealthScore(), 0.001);
    }

    /** 超声泄漏绝对阈值边界：0.2 满分、0.21 关注 65 分、0.5 仍在关注档、0.51 危险 30 分并构成硬限击穿。 */
    @Test
    void leakAbsoluteThresholdBoundaries() {
        assertEquals(100.0, diagnose(Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.2))
                .staticHealthScore(), 0.001);
        var attention = diagnose(Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.21));
        assertEquals(65.0, attention.staticHealthScore(), 0.001);
        assertFalse(attention.isHardLimitBreached());
        var atDanger = diagnose(Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.5));
        assertEquals(65.0, atDanger.staticHealthScore(), 0.001, "恰好 0.5 不越危险档");
        var danger = diagnose(Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.51));
        assertEquals(30.0, danger.staticHealthScore(), 0.001);
        assertTrue(danger.isHardLimitBreached(), "泄漏击穿即硬限");
    }

    @Test
    void optionalLagAndFrictionAreScoredRelativeToBaseline() {
        // 滞后 1600ms / 基线 400ms → 倍率 4 → 曲线分 ≈17.9（<70 触发确定性归因）
        var lag = plugin.processDiagnosis(
                Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04, "travel_time_lag_ms", 1600.0),
                Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04, "travel_time_lag_ms", 400.0),
                Map.of());
        assertEquals(100.0 / (1.0 + Math.pow((4.0 - 1.0) / 1.5, 2.2)), lag.staticHealthScore(), 0.1,
                "行程滞后按相对基线曲线评分");
        assertTrue(lag.defectAttribution().contains("阀门响应迟缓"));

        var friction = plugin.processDiagnosis(
                Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04, "friction_force_kn", 3.0),
                Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04, "friction_force_kn", 1.0),
                Map.of());
        assertTrue(friction.defectAttribution().contains("填料摩擦力增大"));
    }

    @Test
    void healthyOrRequiredOnlySampleScoresFullHealth() {
        var result = diagnose(Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04));
        assertEquals(100.0, result.staticHealthScore(), 0.001);
        assertEquals("调节阀回路工作平稳", result.defectAttribution());
        assertEquals(4, result.contributions().size(), "贡献明细固定覆盖四个机理特征");
    }

    @Test
    void metadataConfigOverridesThresholds() {
        var leakOverride = plugin.processDiagnosis(
                Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04), BASE,
                Map.of("leak_danger_rms", 0.03));
        assertEquals(30.0, leakOverride.staticHealthScore(), 0.001, "通道配置收紧泄漏阈值后 0.04 应判危险");
        assertTrue(leakOverride.isHardLimitBreached());

        var deadbandOverride = plugin.processDiagnosis(
                Map.of("hysteresis_deadband_pct", 2.0), BASE, Map.of("deadband_attention_ratio", 1.5));
        assertEquals(40.0, deadbandOverride.staticHealthScore(), 0.001, "通道配置收紧死区倍率后 k=2.0 应进关注档");
    }

    @Test
    void pluginDeclaresDocumentedMetadata() {
        assertEquals("controlValvePlugin", plugin.identifier());
        assertEquals(ModalityTypes.CONTROL_VALVE_PROCESS, plugin.modalityType());
        assertEquals("CONTROL_VALVE_PROCESS", plugin.getModalityType());
        assertEquals(Cadence.CONTINUOUS, plugin.cadence());
        assertEquals("hysteresis_deadband_pct", plugin.primaryFeatureCode());
        assertEquals(Set.of(), plugin.lowerIsWorseFeatures());

        BaselineLearning policy = plugin.baselineLearning();
        assertFalse(policy.pooled(), "连续型模态要求样本覆盖学习窗口");
        assertEquals(Duration.ofHours(1), policy.window(), "低频采样使用插件声明的学习窗口");
        assertEquals(Duration.ofMinutes(5), plugin.staleAfter(Duration.ofMinutes(5)), "连续型沿用全局新鲜度");

        Set<String> keys = Set.of("deadband_attention_ratio", "deadband_danger_ratio",
                "leak_attention_rms", "leak_danger_rms");
        assertEquals(keys, Set.copyOf(plugin.metadataFields().stream().map(f -> f.key()).toList()));
    }

    /** 回环特征列：cv_pct/fb_pct 追加在机理特征之后，仅存储与查询。 */
    @Test
    void seriesFeatureCodesAppendLoopFeaturesAfterMechanismFeatures() {
        List<String> series = new ArrayList<>(plugin.seriesFeatureCodes());
        assertEquals(6, series.size());
        assertEquals(Set.of("hysteresis_deadband_pct", "travel_time_lag_ms", "ultrasonic_leak_rms",
                "friction_force_kn", "cv_pct", "fb_pct"), Set.copyOf(series));
        assertTrue(series.indexOf("cv_pct") > series.indexOf("friction_force_kn"), "回环特征应排在机理特征之后");
        assertTrue(series.indexOf("fb_pct") > series.indexOf("cv_pct"));
        assertFalse(plugin.mechanismFeatureCodes().contains("cv_pct"), "回环特征不参与机理评分");
    }

    private com.pdm.mvp.modality.MechanismPluginStrategy.PluginDiagnosisResult diagnose(Map<String, Double> current) {
        return plugin.processDiagnosis(current, BASE, Map.of());
    }

    private void assertBad(Map<String, Double> features) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> plugin.validateFeatures(sample(features, new OperatingCondition(null, null, true))));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    private TelemetryEnvelope sample(Map<String, Double> features, OperatingCondition condition) {
        return new TelemetryEnvelope(2, ModalityTypes.CONTROL_VALVE_PROCESS, NOW, "FACT_01", "FCV101",
                "LOOP", 0, condition, features, null, null);
    }
}
