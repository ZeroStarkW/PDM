package com.pdm.mvp.modality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.controlvalve.ControlValvePlugin;
import com.pdm.mvp.controlvalve.ControlValveProperties;
import com.pdm.mvp.telemetry.OperatingCondition;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 连续型模态（调节阀，1 Hz）的 η_dyn 稳健性：白噪声窗口不得压分、观测按 5 分钟抽稀、
 * 拟合窗口取配置的 pdm.health.degradation-window，真实雪崩仍被抑制。
 */
class MechanismDegradationDynamicsTest {
    private static final long T0 = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
    private static final long SECOND = Duration.ofSeconds(1).toMillis();
    private static final long MINUTE = Duration.ofMinutes(1).toMillis();
    /** 连续型：minSamples = 24、minSpan = 窗口/2。 */
    private static final Map<String, Double> BASE = Map.of("hysteresis_deadband_pct", 1.0, "travel_time_lag_ms", 400.0,
            "friction_force_kn", 1.25, "ultrasonic_leak_rms", 0.04);
    private final BaselineSnapshot baseline = new BaselineSnapshot(12L, ModalityTypes.CONTROL_VALVE_PROCESS, "ACTIVE", 200,
            Instant.now(), null, BASE, Map.of());
    private final PointEntity point = point();

    /** 噪声窗口：1 Hz × 300 个样本，k = 1 + N(0, 0.02)。改造前 512 条上限只覆盖约 8.5 分钟的 1 Hz 数据，噪声即可拟合出虚假斜率并触发误告警。 */
    @Test
    void valveNoiseOnlyWindowKeepsStaticScoreWithoutAvalanchePrefix() {
        var plugin = valve(Duration.ofHours(24));
        Random random = new Random(7L);
        for (int i = 0; i < 300; i++)
            plugin.observeDegradation(point, sample(T0 + i * SECOND, 1.0 + random.nextGaussian() * 0.02), baseline);

        var assessment = plugin.score(point, sample(T0 + 300 * SECOND, 1.0), baseline, null);

        assertEquals(0.0, assessment.slopeDkDt(), 1e-9, "1 Hz 噪声不得拟合出劣化斜率");
        assertEquals(1.0, assessment.etaDyn(), 1e-9);
        assertEquals(assessment.hiRelative(), assessment.healthIndex(), 1e-9, "噪声窗口不得压分");
        assertEquals(100.0, assessment.healthIndex(), 1e-9);
        assertEquals("调节阀回路工作平稳", plugin.diagnose(point, sample(T0 + 300 * SECOND, 1.0), baseline).factor());
    }

    /** 连续型观测按 5 分钟抽稀：1 Hz 连续 90 分钟只有 19 条入窗（< 24 条），不拟合斜率；累计到 24 条后才开始拟合。 */
    @Test
    void continuousObservationsAreDecimatedToAtMostOnePerFiveMinutes() {
        var plugin = valve(Duration.ofHours(2));
        int ninetyMinutes = 90 * 60;
        for (int i = 0; i <= ninetyMinutes; i++)
            plugin.observeDegradation(point, sample(T0 + i * SECOND, 1.0 + 0.5 * i / 5399.0), baseline);

        var atNinety = plugin.score(point, sample(T0 + ninetyMinutes * SECOND, 1.5), baseline, null);
        assertEquals(0.0, atNinety.slopeDkDt(), 1e-9,
                "90 分钟 1 Hz 数据抽稀后仅 19 条（0,5,…,90 分钟），不足连续型最少 24 条");
        assertEquals(100.0, atNinety.healthIndex(), 1e-9, "未拟合斜率时不得压分");

        for (int i = ninetyMinutes + 1; i <= 119 * 60; i++)
            plugin.observeDegradation(point, sample(T0 + i * SECOND, 1.0 + 0.5 * i / 5399.0), baseline);
        var later = plugin.score(point, sample(T0 + 119 * 60 * SECOND, 1.0 + 0.5 * 119 * 60 / 5399.0), baseline, null);
        assertTrue(later.slopeDkDt() > 0.10, "满 24 条抽稀样本且跨度 ≥ 1 小时后应拟合出斜率: " + later.slopeDkDt());
        assertTrue(later.etaDyn() < 1.0);
        assertTrue(later.healthIndex() < later.hiRelative(), "真实劣化趋势仍应被 η_dyn 压制");
    }

    /** 调节阀劣化窗口取配置的 pdm.health.degradation-window（此前硬编码 24 小时）：窗口 3 小时，窗口外的旧样本不再影响斜率。 */
    @Test
    void valveUsesConfiguredDegradationWindow() {
        var plugin = valve(Duration.ofHours(3));
        // 0–120 分钟：k 1.0 → 1.5 线性劣化（跨度 ≥ 3h/2，样本 ≥ 24）
        for (int minute = 0; minute <= 120; minute += 5)
            plugin.observeDegradation(point, sample(T0 + minute * MINUTE, 1.0 + 0.5 * minute / 120.0), baseline);
        var whileInWindow = plugin.score(point, sample(T0 + 120 * MINUTE, 1.5), baseline, null);
        assertTrue(whileInWindow.slopeDkDt() > 0.10, "窗口内的上升趋势应拟合出斜率: " + whileInWindow.slopeDkDt());

        // 之后保持平稳；到 305 分钟时 3 小时窗口 [125, 305] 内只剩平稳样本
        for (int minute = 125; minute <= 305; minute += 5)
            plugin.observeDegradation(point, sample(T0 + minute * MINUTE, 1.5), baseline);
        var evicted = plugin.score(point, sample(T0 + 305 * MINUTE, 1.5), baseline, null);

        assertEquals(0.0, evicted.slopeDkDt(), 1e-9, "上升趋势已滑出 3 小时窗口，斜率应归零（硬编码 24h 窗口则仍会压分）");
        assertEquals(1.0, evicted.etaDyn(), 1e-9);
        assertEquals(evicted.hiRelative(), evicted.healthIndex(), 1e-9);
    }

    /** 真实雪崩：死区 12.5 小时内从 1.0% 扩到 2.0%（静态分仍 100），η_dyn 必须把综合分压到 DANGER 并给出雪崩归因。 */
    @Test
    void valveTrueAvalancheIsStillSuppressed() {
        var plugin = valve(Duration.ofHours(24));
        for (int minute = 0; minute <= 750; minute += 5)
            plugin.observeDegradation(point, sample(T0 + minute * MINUTE, 1.0 + minute / 750.0), baseline);

        var sample = sample(T0 + 750 * MINUTE, 2.0);
        var assessment = plugin.score(point, sample, baseline, null);

        assertEquals(100.0, assessment.hiRelative(), 1e-9, "k=2.0 未越关注档 2.5，静态分满分");
        assertTrue(assessment.slopeDkDt() > 0.10, "斜率应显著越界: " + assessment.slopeDkDt());
        assertTrue(assessment.etaDyn() < 0.7);
        assertTrue(assessment.healthIndex() < 30.0, "雪崩应把综合分压到 DANGER: " + assessment.healthIndex());
        assertEquals("DANGER", assessment.level());
        assertTrue(plugin.diagnose(point, sample, baseline).factor().startsWith("劣化突发加速"));
    }

    /** 超声泄漏只按绝对阈值评分：泄漏读数相对基线翻数倍（仍低于关注阈值）不得进入 worst-ratio 拉动 η_dyn。 */
    @Test
    void leakRmsDoesNotDriveDegradationRate() {
        var plugin = valve(Duration.ofHours(24));
        for (int minute = 0; minute <= 750; minute += 5)
            plugin.observeDegradation(point, sample(T0 + minute * MINUTE, 1.0, 0.01 + 0.19 * minute / 750.0), baseline);

        var assessment = plugin.score(point, sample(T0 + 750 * MINUTE, 1.0, 0.20), baseline, null);

        assertEquals(0.0, assessment.slopeDkDt(), 1e-9, "死区/滞后/摩擦平稳时斜率应为 0");
        assertEquals(100.0, assessment.healthIndex(), 1e-9);
    }

    private ControlValvePlugin valve(Duration degradationWindow) {
        return new ControlValvePlugin(new ControlValveProperties(true, 2.5, 5.0, 0.2, 0.5, Duration.ofHours(1)),
                degradationWindow);
    }

    private static TelemetryEnvelope sample(long timestamp, double deadbandRatio) {
        return sample(timestamp, deadbandRatio, 0.04);
    }

    private static TelemetryEnvelope sample(long timestamp, double deadbandRatio, double leakRms) {
        return new TelemetryEnvelope(2, ModalityTypes.CONTROL_VALVE_PROCESS, timestamp, "FACT_01", "FCV101", "LOOP", 0,
                new OperatingCondition(null, null, true),
                Map.of("hysteresis_deadband_pct", 1.0 * deadbandRatio, "travel_time_lag_ms", 400.0,
                        "friction_force_kn", 1.25, "ultrasonic_leak_rms", leakRms),
                null, null);
    }

    private static PointEntity point() {
        PointEntity point = mock(PointEntity.class);
        when(point.getId()).thenReturn(12L);
        var device = mock(com.pdm.mvp.asset.DeviceEntity.class);
        when(point.getDevice()).thenReturn(device);
        when(device.getDeviceType()).thenReturn(null);
        when(point.getMetadataConfig()).thenReturn(null);
        return point;
    }
}
