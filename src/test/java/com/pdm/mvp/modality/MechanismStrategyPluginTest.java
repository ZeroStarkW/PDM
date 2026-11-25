package com.pdm.mvp.modality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pdm.mvp.analytics.HealthAssessment;
import com.pdm.mvp.analytics.HealthScale;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.controlvalve.ControlValvePlugin;
import com.pdm.mvp.controlvalve.ControlValveProperties;
import com.pdm.mvp.switchgear.CircuitBreakerPlugin;
import com.pdm.mvp.switchgear.SwitchgearProperties;
import com.pdm.mvp.telemetry.OperatingCondition;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 机理策略插件基类验证：η_dyn 突增抑制与归因前缀、噪声/非实质变化不触发抑制、
 * 静态分健康时的雪崩归因、运行期状态重置、合并工况视图与告警触发幅值。
 */
class MechanismStrategyPluginTest {
    private static final long T0 = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
    /** 事件型节律：劣化窗口 30 天 → 至少 8 条且跨度 ≥ 30d/12 = 2.5 天才拟合斜率；3 天跨度满足门槛。 */
    private static final long SPAN = Duration.ofDays(3).toMillis();

    private final CircuitBreakerPlugin plugin = new CircuitBreakerPlugin(new SwitchgearProperties(
            true, 1.10, 1.20, 0.80, Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)));
    private final BaselineSnapshot baseline = new BaselineSnapshot(1L, "SWITCHGEAR_EVENT", "ACTIVE", 200,
            Instant.now(), null, Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 5.0), Map.of());
    private final PointEntity point = point();

    /** 3 天内分闸耗时 40ms → 72ms（k 1.00 → 1.80，变化量 ≥ 0.15）线性劣化：拟合斜率 ≈0.27/24h，
     *  η_dyn 把综合分压到静态分以下，诊断因子加“警告: 劣化速率突增”前缀。 */
    @Test
    void avalancheSlopeSuppressesFinalScoreAndPrefixesAttribution() {
        feedRise(40.0, 72.0);
        var sample = envelope(T0 + SPAN, 72.0);
        var assessment = plugin.score(point, sample, baseline, null);
        assertTrue(assessment.slopeDkDt() > 0.10, "劣化斜率应显著越界: " + assessment.slopeDkDt());
        assertTrue(assessment.etaDyn() < 0.7);
        assertTrue(assessment.healthIndex() < assessment.hiRelative(),
                "η_dyn 必须把综合分压到静态分以下: " + assessment.healthIndex() + " vs " + assessment.hiRelative());

        var match = plugin.diagnose(point, sample, baseline);
        assertTrue(match.factor().startsWith("警告: 劣化速率突增"), "归因应加突增前缀: " + match.factor());
        assertTrue(match.factor().contains("机械传动卡涩"));
    }

    /** 噪声窗口：1 小时内 4 次动作、k 仅 1.00 → 1.06 —— 样本数与变化量都不足，评分与归因保持静态结论。 */
    @Test
    void noiseOnlyEventWindowKeepsStaticScoreAndAttribution() {
        long span = Duration.ofHours(1).toMillis();
        for (int i = 0; i < 4; i++)
            plugin.observeDegradation(point, envelope(T0 + span * i / 3, 40.0 + 0.8 * i), baseline);
        var sample = envelope(T0 + span, 42.4);
        var assessment = plugin.score(point, sample, baseline, null);
        assertEquals(0.0, assessment.slopeDkDt(), 1e-9, "4 条样本不满足事件型最少 8 条的拟合门槛");
        assertEquals(1.0, assessment.etaDyn(), 1e-9);
        assertEquals(assessment.hiRelative(), assessment.healthIndex(), 1e-9, "噪声窗口不得压分");
        assertEquals("开关机构特性良好", plugin.diagnose(point, sample, baseline).factor());
    }

    /** 跨度与样本数都满足门槛、但 k 只从 1.00 涨到 1.06（变化量 0.06 < 0.15）：斜率视为 0，噪声不得触发 η_dyn。 */
    @Test
    void immaterialChangeWithinMinimumWindowIsTreatedAsNoTrend() {
        feedRise(40.0, 42.4);
        var sample = envelope(T0 + SPAN, 42.4);
        var assessment = plugin.score(point, sample, baseline, null);
        assertEquals(0.0, assessment.slopeDkDt(), 1e-9, "窗口内 k 变化量不足 0.15 应视为无趋势");
        assertEquals(1.0, assessment.etaDyn(), 1e-9);
        assertEquals(assessment.hiRelative(), assessment.healthIndex(), 1e-9);
    }

    /** 静态分仍健康（合闸速度 3 天内 2.15 → 1.13m/s，静态分 ≈75）但 η_dyn 突增：
     *  不得输出“警告 … | 开关机构特性良好”的自相矛盾归因，改用雪崩归因与建议。 */
    @Test
    void avalancheWithHealthyStaticScoreUsesAvalancheAttributionInsteadOfHealthyText() {
        var withVelocity = new BaselineSnapshot(1L, "SWITCHGEAR_EVENT", "ACTIVE", 200, Instant.now(), null,
                Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 5.0, "closing_velocity_mps", 2.15), Map.of());
        for (int i = 0; i < 9; i++)
            plugin.observeDegradation(point, envelope(T0 + SPAN * i / 8, 40.0, 2.15 - 1.02 * i / 8.0), withVelocity);
        var sample = envelope(T0 + SPAN, 40.0, 1.13);
        var assessment = plugin.score(point, sample, withVelocity, null);
        assertTrue(assessment.hiRelative() >= 70.0, "合闸速度下降的静态分仍在健康线以上: " + assessment.hiRelative());
        assertTrue(assessment.etaDyn() < 0.7, "劣化速率应触发雪崩抑制: η=" + assessment.etaDyn());
        assertTrue(assessment.healthIndex() < 70.0, "综合分应被压到健康线以下: " + assessment.healthIndex());

        var match = plugin.diagnose(point, sample, withVelocity);
        assertTrue(match.factor().startsWith("劣化突发加速 (dk/dt="), "应改用雪崩归因: " + match.factor());
        assertFalse(match.factor().contains("特性良好"), "η_dyn 突增时不得再拼接“特性良好”: " + match.factor());
        assertFalse(match.factor().contains("警告: 劣化速率突增"), "静态分健康时不再用警告前缀拼接");
        assertTrue(match.advice().contains("缩短监测周期"), "建议应与雪崩归因匹配: " + match.advice());
        assertTrue(match.evidence().contains("合闸速度 当前 1.13"), "证据摘要应带当前值: " + match.evidence());
    }

    /** 证据摘要：缺失的可选特征与非有限倍率（零基线）显示“—”，不得输出 0.0 / Infinity / NaN。 */
    @Test
    void evidencePrintsAbsentFeaturesAndNonFiniteRatiosAsDashes() {
        var match = plugin.diagnose(point, envelope(T0, 40.0), baseline);
        assertTrue(match.evidence().contains("分闸时间 当前 40/基线 40 (倍率 1, 得分 100)"), match.evidence());
        assertTrue(match.evidence().contains("合闸速度 当前 —/基线 — (倍率 —, 得分 100)"), "缺失的可选特征应显示“—”: " + match.evidence());
        assertFalse(match.evidence().contains("0.0/基线 0.0"), "缺失特征不得再显示为 0.0: " + match.evidence());

        var valve = new ControlValvePlugin(new ControlValveProperties(true, 2.5, 5.0, 0.2, 0.5, Duration.ofHours(1)),
                Duration.ofHours(24));
        var zeroLeakBaseline = new BaselineSnapshot(2L, "CONTROL_VALVE_PROCESS", "ACTIVE", 200, Instant.now(), null,
                Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.0), Map.of());
        var valvePoint = mock(PointEntity.class);
        var valveDevice = mock(com.pdm.mvp.asset.DeviceEntity.class);
        when(valvePoint.getDevice()).thenReturn(valveDevice);
        when(valvePoint.getMetadataConfig()).thenReturn(Map.of());
        var valveMatch = valve.diagnose(valvePoint, new TelemetryEnvelope(2, "CONTROL_VALVE_PROCESS", T0, "FACT_01",
                "FCV101", "LOOP", 0, new OperatingCondition(null, null, true),
                Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.01), null, null), zeroLeakBaseline);
        assertTrue(valveMatch.evidence().contains("超声泄漏 当前 0.01/基线 0 (倍率 —, 得分 100)"),
                "零基线倍率应显示“—”而不是 Infinity: " + valveMatch.evidence());
        assertFalse(valveMatch.evidence().contains("Infinity") || valveMatch.evidence().contains("NaN"));

        assertEquals("—", MechanismStrategyPlugin.display(Double.POSITIVE_INFINITY));
        assertEquals("—", MechanismStrategyPlugin.display(Double.NaN));
        assertEquals("—", MechanismStrategyPlugin.display(null));
        assertEquals("40", MechanismStrategyPlugin.display(40.0));
        assertEquals("1", MechanismStrategyPlugin.display(1.0));
        assertEquals("1.15", MechanismStrategyPlugin.display(1.15));
        assertEquals("52.5", MechanismStrategyPlugin.display(52.5));
        assertEquals("0.0004", MechanismStrategyPlugin.display(0.0004));
    }

    /** 归因与建议都按列宽截断：超长文本不得把 VARCHAR(128/512) 列撑爆，也不得切断代理对。 */
    @Test
    void attributionAndAdviceAreTruncatedToColumnWidth() {
        String longText = "劣".repeat(600);
        assertEquals(128, MechanismStrategyPlugin.truncate(longText, 128).length());
        assertEquals(512, MechanismStrategyPlugin.truncate(longText, 512).length());
        assertEquals("short", MechanismStrategyPlugin.truncate("short", 512));
        assertNull(MechanismStrategyPlugin.truncate(null, 512));
        String surrogatePairs = "😀".repeat(300);
        String truncated = MechanismStrategyPlugin.truncate(surrogatePairs, 513);
        assertEquals(512, truncated.length(), "不得在代理对中间截断");
        assertFalse(Character.isHighSurrogate(truncated.charAt(511)), "末字符不得是落单的高代理");
    }

    @Test
    void resetRuntimeStateClearsDegradationWindow() {
        feedRise(40.0, 72.0);
        var sample = envelope(T0 + SPAN, 72.0);
        assertTrue(plugin.score(point, sample, baseline, null).slopeDkDt() > 0.10, "重置前应有显著斜率");
        plugin.resetRuntimeState(1L);
        var assessment = plugin.score(point, sample, baseline, null);
        assertEquals(0.0, assessment.slopeDkDt(), 1e-9);
        assertEquals(1.0, assessment.etaDyn(), 1e-9);
        assertEquals(assessment.hiRelative(), assessment.healthIndex(), 1e-9, "重置后无动力学抑制");
    }

    @Test
    void alertTriggerUsesPrimaryFeatureWithBaselineOrZero() {
        var trigger = plugin.alertTrigger(point, envelope(T0, 52.0), baseline);
        assertEquals(52.0, trigger.value(), 1e-9);
        assertEquals(40.0, trigger.base(), 1e-9, "主特征 = 分闸时间，基线取其 P50");

        var emptyBaseline = new BaselineSnapshot(1L, "SWITCHGEAR_EVENT", "ACTIVE", 0, null, null, Map.of(), Map.of());
        var fallback = plugin.alertTrigger(point, envelope(T0, 52.0), emptyBaseline);
        assertEquals(0.0, fallback.base(), 1e-9);
    }

    /** 合并工况视图：原始键优先且不被覆盖，规范化键仅在缺失且分量非空时补入。 */
    @Test
    void mergedConditionViewPrefersRawKeysAndAddsNormalizedOnes() {
        var condition = new OperatingCondition(1440.0, null, null,
                Map.of("speed_rpm", 1498.5, "is_steady", Boolean.TRUE));
        var merged = MechanismPluginStrategy.merged(
                new TelemetryEnvelope(2, "X", T0, "F", "D", "P", 0, condition, Map.of(), null, null));
        assertEquals(1498.5, (Double) merged.get("speed_rpm"), 1e-9);
        assertEquals(1440.0, (Double) merged.get("rpm"), 1e-9, "原始键缺失时补规范化键");
        assertEquals(Boolean.TRUE, merged.get("is_steady"));
        assertFalse(merged.containsKey("steady_state"), "分量缺失时不造规范化键");
        assertFalse(merged.containsKey("load_pct"));

        var withNullCondition = MechanismPluginStrategy.merged(
                new TelemetryEnvelope(2, "X", T0, "F", "D", "P", 0, null, Map.of(), null, null));
        assertTrue(withNullCondition.isEmpty());
    }

    /** 原始键取空值（如 operating_condition 写 "rpm": null）不得遮蔽由别名推导出的规范化键。 */
    @Test
    void nullRawConditionValuesDoNotShadowNormalizedKeys() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("rpm", null);
        raw.put("speed_rpm", 1498.5);
        raw.put("steady_state", null);
        raw.put("is_steady", Boolean.TRUE);
        raw.put("load_ratio", null);
        var merged = MechanismPluginStrategy.merged(new TelemetryEnvelope(2, "X", T0, "F", "D", "P", 0,
                new OperatingCondition(1498.5, null, Boolean.TRUE, raw), Map.of(), null, null));
        assertEquals(1498.5, (Double) merged.get("rpm"), 1e-9, "空原始键应被跳过，规范化键补入");
        assertEquals(Boolean.TRUE, merged.get("steady_state"), "空 steady_state 不得遮蔽 is_steady=true");
        assertFalse(merged.containsKey("load_pct"), "分量为空时不造规范化键");
        var valve = new ControlValvePlugin(new ControlValveProperties(true, 2.5, 5.0, 0.2, 0.5, Duration.ofHours(1)),
                Duration.ofHours(24));
        assertTrue(valve.isOperatingSteady(merged), "调节阀门禁应读到稳态，而不是被空值遮蔽后判非稳态");
    }

    /** 9 次动作（≥ 事件型最少 8 条）在 3 天内从 fromTrip 线性涨到 toTrip。 */
    private void feedRise(double fromTrip, double toTrip) {
        for (int i = 0; i < 9; i++)
            plugin.observeDegradation(point, envelope(T0 + SPAN * i / 8, fromTrip + (toTrip - fromTrip) * i / 8.0), baseline);
    }

    private PointEntity point() {
        PointEntity point = mock(PointEntity.class);
        when(point.getId()).thenReturn(1L);
        var device = mock(com.pdm.mvp.asset.DeviceEntity.class);
        when(point.getDevice()).thenReturn(device);
        when(device.getDeviceType()).thenReturn(null);
        when(point.getMetadataConfig()).thenReturn(null);
        return point;
    }

    private TelemetryEnvelope envelope(long timestamp, double tripMs) {
        return envelope(timestamp, tripMs, null);
    }

    private static TelemetryEnvelope envelope(long timestamp, double tripMs, Double velocity) {
        Map<String, Double> features = new HashMap<>();
        features.put("trip_duration_ms", tripMs);
        features.put("peak_coil_current_a", 5.0);
        if (velocity != null) features.put("closing_velocity_mps", velocity);
        return new TelemetryEnvelope(2, "SWITCHGEAR_EVENT", timestamp, "FACT_01", "BKR_10KV_02", "TRIP_UNIT",
                0, new OperatingCondition(null, null, true), features, null, null);
    }
}
