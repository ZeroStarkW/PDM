package com.pdm.mvp.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * TC-04 劣化雪崩突变拦截（文档 §9）：静态倍率 k=1.3 看似安全，
 * 但 4 小时内从 1.05 暴涨至 1.3 —— 拟合斜率 dk/dt ≈ 1.5/24h，
 * 动力学乘子启动，综合得分被强制压至 <45 分并触发告警链路。
 */
class DegradationTrackerTest {

    @Test
    void tc04_fitSteepSlopeWhenRatioJumpsWithinFourHours() {
        DegradationTracker tracker = new DegradationTracker(Duration.ofHours(24));
        long start = System.currentTimeMillis();
        long fourHours = Duration.ofHours(4).toMillis();
        // 8 个样本在 4h 内从 1.05 线性涨到 1.3
        int points = 8;
        for (int i = 0; i < points; i++) {
            double ratio = 1.05 + (1.3 - 1.05) * i / (points - 1.0);
            tracker.observe(1L, start + fourHours * i / (points - 1), ratio);
        }

        DegradationTracker.Slope slope = tracker.slope(1L);
        assertEquals(1.5, slope.betaPerDay(), 0.1, "拟合斜率应约 1.5/24h");
        assertTrue(DegradationTracker.etaDyn(slope.betaPerDay()) < 0.05,
                "动力学乘子应被压至 <0.05: " + DegradationTracker.etaDyn(slope.betaPerDay()));
    }

    @Test
    void slowStableDegradationKeepsMultiplierNeutral() {
        DegradationTracker tracker = new DegradationTracker(Duration.ofHours(24));
        long start = System.currentTimeMillis();
        // 12h 内 1.05 → 1.10（慢劣化，斜率 ≈ 0.1/24h，处于死区边缘）
        for (int i = 0; i < 10; i++) {
            tracker.observe(2L, start + Duration.ofHours(12).toMillis() * i / 9, 1.05 + 0.05 * i / 9.0);
        }
        DegradationTracker.Slope slope = tracker.slope(2L);
        assertTrue(slope.betaPerDay() <= 0.12, "慢劣化斜率不应显著越界: " + slope.betaPerDay());
        assertEquals(1.0, DegradationTracker.etaDyn(slope.betaPerDay()), 1e-6, "死区内乘子恒为 1");
    }

    @Test
    void outOfOrderSamplesAreIgnoredAndResetClearsWindow() {
        DegradationTracker tracker = new DegradationTracker(Duration.ofHours(24));
        long now = System.currentTimeMillis();
        tracker.observe(3L, now, 1.0);
        tracker.observe(3L, now - 1000, 2.0); // 迟到样本应被丢弃
        assertEquals(1, tracker.slope(3L).samples());
        tracker.reset(3L);
        assertEquals(0, tracker.slope(3L).samples());
    }

    @Test
    void tooFewSamplesReturnZeroSlope() {
        DegradationTracker tracker = new DegradationTracker(Duration.ofHours(24));
        long now = System.currentTimeMillis();
        tracker.observe(4L, now, 1.0);
        tracker.observe(4L, now + 1, 1.5);
        tracker.observe(4L, now + 2, 2.0);
        assertEquals(0.0, tracker.slope(4L).betaPerDay());
    }
}
