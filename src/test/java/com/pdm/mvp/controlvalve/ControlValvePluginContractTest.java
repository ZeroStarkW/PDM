package com.pdm.mvp.controlvalve;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pdm.mvp.modality.MechanismPluginStrategy.PluginDiagnosisResult;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 调节阀插件的新增契约：收敛特征、触发/趋势特征、通道机理参数校验与零基线倍率。 */
class ControlValvePluginContractTest {
    private final ControlValvePlugin plugin = new ControlValvePlugin(new ControlValveProperties(true, 2.5, 5.0, 0.2, 0.5,
            Duration.ofHours(1)), Duration.ofHours(24));

    /** 超声泄漏只按绝对阈值评分：仍参与学习，但不参与锁定门槛与 dk/dt 拟合。 */
    @Test
    void convergenceFeatureCodesExcludeLeakRmsButKeepItInBaselineLearning() {
        assertEquals(Set.of("hysteresis_deadband_pct", "travel_time_lag_ms", "friction_force_kn"), plugin.convergenceFeatureCodes());
        assertTrue(plugin.mechanismFeatureCodes().contains("ultrasonic_leak_rms"), "泄漏 RMS 仍参与基线学习");
        assertEquals("hysteresis_deadband_pct", plugin.triggerFeatureCode());
        assertEquals("hysteresis_deadband_pct", plugin.trendFeatureCode());
    }

    /** 通道机理参数约束与全局配置一致：按实际生效值（未填写的键沿用全局配置）校验。 */
    @Test
    void metadataThresholdsAreValidatedAgainstEffectiveValues() {
        assertDoesNotThrow(() -> plugin.validateMetadata(Map.of("deadband_attention_ratio", 1.5)));
        assertDoesNotThrow(() -> plugin.validateMetadata(Map.of("deadband_danger_ratio", 3.0)));
        assertDoesNotThrow(() -> plugin.validateMetadata(Map.of("leak_attention_rms", 0.3)));
        assertDoesNotThrow(() -> plugin.validateMetadata(Map.of("leak_danger_rms", 0.4)));
        assertDoesNotThrow(() -> plugin.validateMetadata(Map.of("leak_attention_rms", 0.3, "leak_danger_rms", 0.31)));
        assertDoesNotThrow(() -> plugin.validateMetadata(Map.of()));
        assertDoesNotThrow(() -> plugin.validateMetadata(null));

        assertBadMetadata(Map.of("deadband_danger_ratio", 2.0), "deadband_danger_ratio");
        assertBadMetadata(Map.of("deadband_attention_ratio", 0.9), "deadband_attention_ratio");
        assertBadMetadata(Map.of("deadband_attention_ratio", 6.0), "deadband_attention_ratio");
        assertBadMetadata(Map.of("leak_danger_rms", 0.1), "leak_danger_rms");
        assertBadMetadata(Map.of("leak_attention_rms", 0.0), "leak_attention_rms");
        assertBadMetadata(Map.of("leak_attention_rms", 0.6), "leak_attention_rms");
        assertBadMetadata(Map.of("leak_danger_rms", Double.NaN), "有限数值");
        assertBadMetadata(Map.of("leak_danger_rms", "0.4"), "必须为数值");
    }

    @Test
    void globalPropertiesRejectInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new ControlValveProperties(true, 5.0, 5.0, 0.2, 0.5,
                Duration.ofHours(1)), "deadband danger 必须大于 attention");
        assertThrows(IllegalArgumentException.class, () -> new ControlValveProperties(true, 1.0, 5.0, 0.2, 0.5,
                Duration.ofHours(1)), "deadband attention 必须大于 1");
        assertThrows(IllegalArgumentException.class, () -> new ControlValveProperties(true, 2.5, 5.0, 0.0, 0.5,
                Duration.ofHours(1)), "泄漏关注阈值必须大于 0");
        assertThrows(IllegalArgumentException.class, () -> new ControlValveProperties(true, 2.5, 5.0, 0.6, 0.5,
                Duration.ofHours(1)), "泄漏危险阈值必须大于关注阈值");
        assertThrows(IllegalArgumentException.class, () -> new ControlValveProperties(true, Double.NaN, 5.0, 0.2, 0.5,
                Duration.ofHours(1)), "非有限阈值");
        assertThrows(IllegalArgumentException.class, () -> new ControlValveProperties(true, 2.5, 5.0, 0.2, 0.5,
                Duration.ZERO), "学习窗口必须为正");
    }

    /** 泄漏基线为 0 时倍率无定义：贡献明细给 null，而不是 Infinity/NaN（后者会进证据文本与告警 JSON）。 */
    @Test
    void zeroLeakBaselineYieldsNullRatioInsteadOfInfinity() {
        PluginDiagnosisResult result = plugin.processDiagnosis(
                Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.01),
                Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.0), Map.of());
        var leak = result.contributions().stream().filter(c -> "超声泄漏".equals(c.feature())).findFirst().orElseThrow();
        assertNull(leak.ratio());
        assertEquals(0.0, leak.base(), 0.0);
        assertEquals(100.0, leak.score(), 1e-9);
        assertEquals(100.0, result.staticHealthScore(), 1e-9);
    }

    @Test
    void nonPositiveDegradationWindowIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new ControlValvePlugin(new ControlValveProperties(true, 2.5, 5.0,
                0.2, 0.5, Duration.ofHours(1)), Duration.ZERO));
    }

    private void assertBadMetadata(Map<String, Object> metadata, String expectedText) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> plugin.validateMetadata(metadata));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertTrue(exception.getReason().contains(expectedText), exception.getReason());
    }
}
