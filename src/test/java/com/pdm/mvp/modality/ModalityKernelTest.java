package com.pdm.mvp.modality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pdm.mvp.alert.DeviceHealthWindow;
import com.pdm.mvp.analytics.HealthAssessment;
import com.pdm.mvp.analytics.HealthScale;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.diagnostics.DiagnosticMatch;
import com.pdm.mvp.switchgear.CircuitBreakerPlugin;
import com.pdm.mvp.switchgear.SwitchgearProperties;
import com.pdm.mvp.telemetry.OperatingCondition;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 内核节奏感知改造单元验证：共享评分曲线扩展、模态归一化、工况键推导、
 * 事件型尖峰直通、防抖窗口断档策略与按模态的数据新鲜度。
 */
class ModalityKernelTest {
    private static final long T0 = 1_000_000L;
    private static final long DAY = Duration.ofDays(1).toMillis();

    @Test
    void lowerIsWorseRelativeScoreInvertsTheRatio() {
        double inverted = HealthScale.relativeScore(1.0, 2.15, true);
        assertEquals(HealthScale.relativeScore(2.15, 1.0), inverted, 1e-9, "越低越差按 基线/当前 反转");
        assertEquals(100.0, HealthScale.relativeScore(0.5, 2.0, false), 1e-9, "越高越差保持原语义");
        assertEquals(100.0, HealthScale.relativeScore(1.0, null, true), 1e-9, "无基线满分");
        assertEquals(100.0, HealthScale.relativeScore(1.0, 0.0, true), 1e-9, "零基线守卫满分");
        assertEquals(0.0, HealthScale.relativeScore(0.0, 2.15, true), 1e-9, "线圈电流消失即 0 分");
        assertEquals(HealthScale.relativeScore(1.2, 1.0), HealthScale.relativeScore(1.2, 1.0, false), 1e-9);
    }

    @Test
    void hardLimitBreachedForcesDangerLevel() {
        assertEquals("DANGER", HealthScale.levelOf(100.0, true));
        assertEquals("HEALTHY", HealthScale.levelOf(100.0, false));
        assertEquals("WARNING", HealthScale.levelOf(40.0, false));
    }

    @Test
    void modalityTypeNormalizationMapsDocumentAlias() {
        assertEquals("MCSA", ModalityTypes.normalize("electrical_mcsa"));
        assertEquals("MCSA", ModalityTypes.normalize(" ELECTRICAL_MCSA "));
        assertEquals("MCSA", ModalityTypes.normalize("mcsa"));
        assertEquals("SWITCHGEAR_EVENT", ModalityTypes.normalize("switchgear_event"));
        assertNull(ModalityTypes.normalize(null));
        assertNull(ModalityTypes.normalize("   "));
    }

    @Test
    void operatingConditionDerivesNormalizedKeysWithRawPrecedence() {
        var condition = new OperatingCondition(1440.0, 75.0, true,
                Map.of("speed_rpm", 1498.5, "load_ratio", 0.85, "is_steady", Boolean.TRUE,
                        "inlet_pressure_bar", 16.5));
        assertEquals(1498.5, (Double) condition.get("speed_rpm"), 1e-9, "原始键优先");
        assertEquals(1440.0, (Double) condition.get("rpm"), 1e-9, "原始键缺失时推导");
        assertEquals(0.85, (Double) condition.get("load_ratio"), 1e-9, "原始键优先");
        assertEquals(75.0, (Double) condition.get("load_pct"), 1e-9);
        assertEquals(Boolean.TRUE, condition.get("is_steady"));
        assertEquals(Boolean.TRUE, condition.get("steady_state"));
        assertEquals(16.5, (Double) condition.get("inlet_pressure_bar"), 1e-9, "任意原始键透传");

        var minimal = new OperatingCondition(1000.0, 60.0, null);
        assertEquals(0.6, (Double) minimal.get("load_ratio"), 1e-9, "load_ratio 按 load_pct/100 推导");
        assertNull(minimal.get("steady_state"));
        assertNull(minimal.get("unknown"));
        assertNull(minimal.get(null));
        var noRaw = new OperatingCondition(1000.0, null, null, null);
        assertNull(noRaw.get("load_pct"));
    }

    /** 事件型模态：尖峰抑制直通（首个样本即输出、无 3 点窗口、无尖峰标记），乱序仍拒绝。 */
    @Test
    void eventCadenceSamplesBypassSpikeWindow() {
        var filter = new com.pdm.mvp.analytics.SpikeFilter();
        var eventPlugin = new CadenceStubPlugin(ModalityPlugin.Cadence.EVENT);
        var first = filter.accept(1L, envelope(T0, 100.0), eventPlugin);
        assertTrue(first.isPresent(), "事件样本不缓冲，首个即输出");
        assertFalse(first.get().spike());
        var spike = filter.accept(1L, envelope(T0 + 1000, 99999.0), eventPlugin);
        assertTrue(spike.isPresent());
        assertFalse(spike.get().spike(), "事件型不做尖峰标记");
        assertTrue(filter.accept(1L, envelope(T0 + 500, 1.0), eventPlugin).isEmpty(), "乱序/重复仍拒绝");

        var continuous = new CadenceStubPlugin(ModalityPlugin.Cadence.CONTINUOUS);
        assertTrue(filter.accept(2L, envelope(T0, 1.0), continuous).isEmpty(), "连续型保持 3 点缓冲");
        assertTrue(filter.accept(2L, envelope(T0 + 1000, 1.0), continuous).isEmpty());
        assertTrue(filter.accept(2L, envelope(T0 + 2000, 1.0), continuous).isPresent());
    }

    /** 事件型防抖窗口跨天断档不清窗；连续型断档清窗（第 5 次永远凑不满）。 */
    @Test
    void debounceWindowGapPolicyFollowsCadence() {
        DeviceHealthWindow window = new DeviceHealthWindow();
        assertFalse(window.observe(1L, 7L, T0, 40, false));
        assertFalse(window.observe(1L, 7L, T0 + DAY, 40, false));
        assertFalse(window.observe(1L, 7L, T0 + 2 * DAY, 40, false));
        assertFalse(window.observe(1L, 7L, T0 + 3 * DAY, 40, false));
        assertTrue(window.observe(1L, 7L, T0 + 4 * DAY, 40, false), "事件型跨天窗口保留，第 5 次触发");

        assertFalse(window.observe(2L, 8L, T0, 40, true));
        assertFalse(window.observe(2L, 8L, T0 + DAY, 40, true));
        assertFalse(window.observe(2L, 8L, T0 + 2 * DAY, 40, true));
        assertFalse(window.observe(2L, 8L, T0 + 3 * DAY, 40, true));
        assertFalse(window.observe(2L, 8L, T0 + 4 * DAY, 40, true), "连续型每次断档清窗，凑不满 5 点");
    }

    /** 按模态的数据新鲜度：事件型取插件时限，连续型与未知模态取全局默认。 */
    @Test
    void freshnessPolicyResolvesPerModalityHorizon() {
        var breaker = new CircuitBreakerPlugin(new SwitchgearProperties(true, 1.10, 1.20, 0.80,
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)));
        var registry = new ModalityPluginRegistry(List.of(breaker));
        var policy = new TelemetryFreshnessPolicy(registry, Duration.ofMinutes(5));
        assertEquals(Duration.ofDays(30), policy.staleAfter("SWITCHGEAR_EVENT"));
        assertEquals(Duration.ofMinutes(5), policy.staleAfter("CONTROL_VALVE_PROCESS"), "连续型沿用默认");
        assertEquals(Duration.ofMinutes(5), policy.staleAfter("UNKNOWN_MODALITY"));
        assertEquals(Duration.ofMinutes(5), policy.staleAfter((String) null));

        PointEntity point = mock(PointEntity.class);
        when(point.getModalityType()).thenReturn("SWITCHGEAR_EVENT");
        assertEquals(Duration.ofDays(30), policy.staleAfter(point));
        assertEquals(Duration.ofDays(30), policy.byModality().apply("SWITCHGEAR_EVENT"));
    }

    private TelemetryEnvelope envelope(long timestamp, double value) {
        return new TelemetryEnvelope(2, "STUB", timestamp, "F", "D", "P", 0, null,
                Map.of("f1", value), null, null);
    }

    /** 最小模态桩：仅用于验证内核按 cadence 分流，不依赖具体插件。 */
    private static final class CadenceStubPlugin implements ModalityPlugin {
        private final ModalityPlugin.Cadence cadence;
        CadenceStubPlugin(ModalityPlugin.Cadence cadence) { this.cadence = cadence; }
        @Override public String modalityType() { return "STUB"; }
        @Override public Set<String> requiredFeatures() { return Set.of("f1"); }
        @Override public ConditionGate gate(TelemetryEnvelope envelope) { return ConditionGate.accept(); }
        @Override public Set<String> spikeFeatureCodes() { return Set.of("f1"); }
        @Override public Set<String> baselineFeatureCodes() { return Set.of("f1"); }
        @Override public Set<String> scoringBaselineFeatures() { return Set.of("f1"); }
        @Override public Map<String, Double> baselineFeatureValues(TelemetryEnvelope envelope) { return Map.of(); }
        @Override public DiagnosticMatch diagnose(PointEntity point, TelemetryEnvelope sample,
                BaselineSnapshot baseline) { return null; }
        @Override public HealthAssessment score(PointEntity point, TelemetryEnvelope sample,
                BaselineSnapshot baseline, DiagnosticMatch diagnosis) { return null; }
        @Override public AlertTrigger alertTrigger(PointEntity point, TelemetryEnvelope sample,
                BaselineSnapshot baseline) { return null; }
        @Override public boolean alertsEnabled() { return false; }
        @Override public ModalityPlugin.Cadence cadence() { return cadence; }
    }
}
