package com.pdm.mvp.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 按采样节律定制的劣化跟踪器：新构造器、窗口内 k 变化量访问器与参数校验；单参构造器保持振动模态历史常量。 */
class DegradationTrackerTuningTest {
    private static final long START = 1_000_000_000_000L;

    /** 单参构造器 = 4 样本 / 30 分钟 / 512 条：4 小时内 1.05 → 1.3 的 8 个样本仍拟合出 ≈1.5/24h（振动行为不变）。 */
    @Test
    void singleArgumentConstructorKeepsLegacyConstants() {
        var legacy = new DegradationTracker(Duration.ofHours(24));
        var tuned = new DegradationTracker(Duration.ofHours(24), DegradationTracker.MIN_SLOPE_SAMPLES,
                Duration.ofMillis(DegradationTracker.MIN_SPAN_MILLIS), DegradationTracker.DEFAULT_MAX_SAMPLES);
        double[] ratios = rise(8, 1.05, 1.30);
        for (int i = 0; i < ratios.length; i++) {
            legacy.observe(1L, START + i * Duration.ofMinutes(30).toMillis(), ratios[i]);
            tuned.observe(2L, START + i * Duration.ofMinutes(30).toMillis(), ratios[i]);
        }

        assertEquals(legacy.slope(1L).betaPerDay(), tuned.slope(2L).betaPerDay(), 1e-12);
        assertEquals(legacy.slope(1L).samples(), tuned.slope(2L).samples());
        assertEquals(1.714, legacy.slope(1L).betaPerDay(), 0.01, "8 个样本 × 30 分钟 = 3.5 小时，斜率 ≈1.714/24h");
    }

    /** 事件型参数（8 条、窗口/12 跨度）：同样 8 个样本只跨 3.5 小时，不满足 2.5 天跨度 → 斜率 0。 */
    @Test
    void tunedMinimumSpanAndSampleCountGateTheFit() {
        var event = new DegradationTracker(Duration.ofDays(30), 8, Duration.ofDays(30).dividedBy(12), 512);
        double[] ratios = rise(8, 1.05, 1.30);
        for (int i = 0; i < ratios.length; i++)
            event.observe(1L, START + i * Duration.ofMinutes(30).toMillis(), ratios[i]);
        assertEquals(0.0, event.slope(1L).betaPerDay(), 1e-12, "跨度不足不拟合");
        assertEquals(0.25, event.ratioRange(1L), 1e-12, "变化量访问器与拟合门槛无关，如实反映窗口内 k 的极差");

        // 7 条样本跨 3 天：跨度足够但样本数不足
        var fewer = new DegradationTracker(Duration.ofDays(30), 8, Duration.ofDays(30).dividedBy(12), 512);
        double[] seven = rise(7, 1.05, 1.30);
        for (int i = 0; i < seven.length; i++)
            fewer.observe(3L, START + i * Duration.ofHours(12).toMillis(), seven[i]);
        assertEquals(0.0, fewer.slope(3L).betaPerDay(), 1e-12, "样本数不足不拟合");

        // 8 条样本跨 63 小时（≥ 2.5 天）：开始拟合，斜率 ≈ 0.25/2.625 天 ≈ 0.095/24h
        for (int i = 0; i < ratios.length; i++)
            event.observe(4L, START + i * Duration.ofHours(9).toMillis(), ratios[i]);
        assertEquals(0.0952, event.slope(4L).betaPerDay(), 0.001, "样本数与跨度都满足后应拟合出斜率");
    }

    @Test
    void ratioRangeTracksWindowExtremesAndClearsWithReset() {
        var tracker = new DegradationTracker(Duration.ofHours(24), 4, Duration.ofMinutes(30), 512);
        assertEquals(0.0, tracker.ratioRange(5L), 1e-12, "无样本时变化量为 0");

        tracker.observe(5L, START, 1.10);
        tracker.observe(5L, START + 60_000, 1.04);
        tracker.observe(5L, START + 120_000, 1.22);
        assertEquals(0.18, tracker.ratioRange(5L), 1e-12);

        // 超出 24 小时窗口的旧样本被淘汰后，变化量随之收缩
        tracker.observe(5L, START + Duration.ofHours(25).toMillis(), 1.20);
        assertEquals(0.0, tracker.ratioRange(5L), 1e-12, "窗口内只剩 1 条样本");

        tracker.reset(5L);
        assertEquals(0.0, tracker.ratioRange(5L), 1e-12);
    }

    @Test
    void windowCapacityCapFollowsConfiguredMaximum() {
        var tracker = new DegradationTracker(Duration.ofDays(365), 2, Duration.ZERO, 3);
        for (int i = 0; i < 10; i++) tracker.observe(6L, START + i * 60_000L, 1.0 + i * 0.01);
        assertEquals(3, tracker.slope(6L).samples(), "窗口最多保留 maxSamples 条");
        assertEquals(0.02, tracker.ratioRange(6L), 1e-12);
    }

    @Test
    void invalidTuningParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DegradationTracker(null, 4, Duration.ZERO, 512));
        assertThrows(IllegalArgumentException.class, () -> new DegradationTracker(Duration.ZERO, 4, Duration.ZERO, 512));
        assertThrows(IllegalArgumentException.class,
                () -> new DegradationTracker(Duration.ofHours(24), 0, Duration.ZERO, 512));
        assertThrows(IllegalArgumentException.class,
                () -> new DegradationTracker(Duration.ofHours(24), 4, null, 512));
        assertThrows(IllegalArgumentException.class,
                () -> new DegradationTracker(Duration.ofHours(24), 4, Duration.ofMinutes(-1), 512));
        assertThrows(IllegalArgumentException.class,
                () -> new DegradationTracker(Duration.ofHours(24), 8, Duration.ZERO, 4));
        assertThrows(IllegalArgumentException.class, () -> new DegradationTracker(Duration.ZERO));
    }

    /** 从 from 线性涨到 to 的 count 个倍率。 */
    private static double[] rise(int count, double from, double to) {
        double[] ratios = new double[count];
        for (int i = 0; i < count; i++) ratios[i] = from + (to - from) * i / (count - 1.0);
        return ratios;
    }
}
