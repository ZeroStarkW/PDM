package com.pdm.mvp.switchgear;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 断路器插件的新增契约：收敛特征、时序特征列、触发/趋势特征、补传时延与通道机理参数校验。 */
class CircuitBreakerPluginContractTest {
    private final CircuitBreakerPlugin plugin = new CircuitBreakerPlugin(new SwitchgearProperties(true, 1.10, 1.20, 0.80,
            Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)));

    /** 触头磨损率近似单调累积、CV 无收敛意义：仍参与学习，但不参与锁定门槛与 dk/dt 拟合。 */
    @Test
    void convergenceFeatureCodesExcludeContactWearButKeepItInBaselineLearning() {
        assertEquals(Set.of("trip_duration_ms", "peak_coil_current_a", "closing_velocity_mps"), plugin.convergenceFeatureCodes());
        assertTrue(plugin.mechanismFeatureCodes().contains("contact_wear_ratio"), "磨损率仍参与基线学习");
        assertTrue(plugin.baselineFeatureCodes().contains("contact_wear_ratio"));
    }

    @Test
    void seriesFeatureCodesIncludeStoredPartialDischargeCount() {
        assertEquals(List.of("trip_duration_ms", "peak_coil_current_a", "closing_velocity_mps", "contact_wear_ratio",
                "uhf_pd_count"), List.copyOf(plugin.seriesFeatureCodes()));
    }

    @Test
    void triggerAndTrendFeatureCodesAreThePrimaryFeature() {
        assertEquals("trip_duration_ms", plugin.triggerFeatureCode());
        assertEquals("trip_duration_ms", plugin.trendFeatureCode());
    }

    @Test
    void pastDelayFollowsConfigurationAndZeroDisablesStoreAndForward() {
        assertEquals(Duration.ofDays(30), plugin.maxPastDelay());
        var strict = new CircuitBreakerPlugin(new SwitchgearProperties(true, 1.10, 1.20, 0.80, Duration.ofDays(30),
                Duration.ofDays(365), Duration.ofDays(30), Duration.ZERO));
        assertEquals(Duration.ZERO, strict.maxPastDelay(), "零表示不放宽，仍只容忍 5 分钟时钟偏差");
    }

    /** 通道机理参数约束与全局配置一致：按实际生效值（未填写的键沿用全局配置）校验。 */
    @Test
    void metadataThresholdsAreValidatedAgainstEffectiveValues() {
        assertDoesNotThrow(() -> plugin.validateMetadata(Map.of("duration_danger_ratio", 1.15)));
        assertDoesNotThrow(() -> plugin.validateMetadata(Map.of("duration_attention_ratio", 1.05, "duration_danger_ratio", 1.08)));
        assertDoesNotThrow(() -> plugin.validateMetadata(Map.of("current_attention_ratio", 0.9)));
        assertDoesNotThrow(() -> plugin.validateMetadata(Map.of()));
        assertDoesNotThrow(() -> plugin.validateMetadata(null));
        assertDoesNotThrow(() -> plugin.validateMetadata(Map.of("unrelated_key", "text")));

        assertBadMetadata(Map.of("duration_danger_ratio", 0.5), "duration_danger_ratio");
        assertBadMetadata(Map.of("duration_danger_ratio", 1.05), "duration_danger_ratio");
        assertBadMetadata(Map.of("duration_attention_ratio", 1.3), "duration_attention_ratio");
        assertBadMetadata(Map.of("duration_attention_ratio", 1.0), "duration_attention_ratio");
        assertBadMetadata(Map.of("current_attention_ratio", 1.5), "current_attention_ratio");
        assertBadMetadata(Map.of("current_attention_ratio", 0.0), "current_attention_ratio");
        assertBadMetadata(Map.of("duration_danger_ratio", Double.NaN), "有限数值");
        assertBadMetadata(Map.of("current_attention_ratio", Double.POSITIVE_INFINITY), "有限数值");
        assertBadMetadata(Map.of("duration_danger_ratio", "1.2"), "必须为数值");
    }

    @Test
    void globalPropertiesRejectInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new SwitchgearProperties(true, 1.10, 1.10, 0.80,
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)), "danger 必须大于 attention");
        assertThrows(IllegalArgumentException.class, () -> new SwitchgearProperties(true, 1.00, 1.20, 0.80,
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)), "attention 必须大于 1");
        assertThrows(IllegalArgumentException.class, () -> new SwitchgearProperties(true, 1.10, 1.20, 1.0,
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)), "电流比率必须在 (0,1)");
        assertThrows(IllegalArgumentException.class, () -> new SwitchgearProperties(true, Double.NaN, 1.20, 0.80,
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)), "非有限倍率");
        assertThrows(IllegalArgumentException.class, () -> new SwitchgearProperties(true, 1.10, 1.20, 0.80,
                Duration.ZERO, Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)), "时限必须为正");
        assertThrows(IllegalArgumentException.class, () -> new SwitchgearProperties(true, 1.10, 1.20, 0.80,
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(-1)), "max-past-delay 不能为负");
    }

    private void assertBadMetadata(Map<String, Object> metadata, String expectedText) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> plugin.validateMetadata(metadata));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertTrue(exception.getReason().contains(expectedText), exception.getReason());
    }
}
