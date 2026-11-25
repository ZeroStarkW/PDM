package com.pdm.mvp.analytics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 动态劣化动力学跟踪器（文档 §4.3）：维护每测点滑动窗口内的相对倍率 k(t)，
 * 最小二乘拟合线性恶化速率 β₁ = dk/dt（单位 Δk/24h），
 * 并给出时序动态衰减乘子 η_dyn = exp(-2.5·(β₁-0.10))（β₁ ≤ 0.10 时不抑制）。
 * 突发裂纹扩展时即便静态倍率处于安全带，η_dyn 也会把综合得分压制并触发报警。
 */
@Component
public class DegradationTracker {
    /** 最小拟合样本数与最小时间跨度：避免早期零星样本给出虚假斜率。 */
    static final int MIN_SLOPE_SAMPLES = 4;
    static final long MIN_SPAN_MILLIS = Duration.ofMinutes(30).toMillis();
    static final double SLOPE_DEAD_BAND = 0.10;
    static final double ETA_DECAY_RATE = 2.5;
    /** 默认窗口容量上限（振动模态沿用，行为不变）。 */
    public static final int DEFAULT_MAX_SAMPLES = 512;

    private final Map<Long, ArrayDeque<Sample>> windows = new HashMap<>();
    private final Duration window;
    private final int minSamples;
    private final long minSpanMillis;
    private final int maxSamples;

    /** 振动模态跟踪器（Spring Bean）：沿用历史常量 4 样本 / 30 分钟 / 512 条，行为与改造前完全一致。 */
    @Autowired
    public DegradationTracker(@Value("${pdm.health.degradation-window:PT24H}") Duration window) {
        this(window, MIN_SLOPE_SAMPLES, Duration.ofMillis(MIN_SPAN_MILLIS), DEFAULT_MAX_SAMPLES);
    }

    /** 按采样节律定制拟合门槛（供机理策略插件使用）：minSamples 为最少拟合样本数，minSpan 为最少时间跨度。 */
    public DegradationTracker(Duration window, int minSamples, Duration minSpan, int maxSamples) {
        if (window == null || window.isZero() || window.isNegative()) throw new IllegalArgumentException("劣化窗口必须为正时长");
        if (minSamples < 1 || minSpan == null || minSpan.isNegative() || maxSamples < minSamples)
            throw new IllegalArgumentException("劣化拟合参数必须满足 minSamples ≥ 1、minSpan ≥ 0、maxSamples ≥ minSamples");
        this.window = window;
        this.minSamples = minSamples;
        this.minSpanMillis = minSpan.toMillis();
        this.maxSamples = maxSamples;
    }

    public synchronized void observe(Long pointId, long timestamp, double ratio) {
        if (!Double.isFinite(ratio) || ratio <= 0) return;
        ArrayDeque<Sample> windowQueue = windows.computeIfAbsent(pointId, ignored -> new ArrayDeque<>(64));
        Sample latest = windowQueue.peekLast();
        if (latest != null && timestamp <= latest.timestamp()) return;
        long cutoff = timestamp - window.toMillis();
        while (!windowQueue.isEmpty() && windowQueue.peekFirst().timestamp() < cutoff) windowQueue.removeFirst();
        windowQueue.addLast(new Sample(timestamp, ratio));
        while (windowQueue.size() > maxSamples) windowQueue.removeFirst();
    }

    /** 窗口内 k(t) 最小二乘斜率（Δk/24h）；样本不足或跨度太短返回 0。 */
    public synchronized Slope slope(Long pointId) {
        ArrayDeque<Sample> windowQueue = windows.get(pointId);
        if (windowQueue == null || windowQueue.size() < minSamples) return new Slope(0.0, windowQueue == null ? 0 : windowQueue.size());
        double n = windowQueue.size();
        double first = windowQueue.peekFirst().timestamp(), last = windowQueue.peekLast().timestamp();
        if (last - first < minSpanMillis) return new Slope(0.0, windowQueue.size());
        double meanT = 0, meanK = 0;
        for (Sample sample : windowQueue) {
            meanT += (sample.timestamp() - first) / Duration.ofHours(1).toMillis();
            meanK += sample.ratio();
        }
        meanT /= n; meanK /= n;
        double covariance = 0, variance = 0;
        for (Sample sample : windowQueue) {
            double tHours = (sample.timestamp() - first) / Duration.ofHours(1).toMillis();
            covariance += (tHours - meanT) * (sample.ratio() - meanK);
            variance += (tHours - meanT) * (tHours - meanT);
        }
        if (variance < 1e-9) return new Slope(0.0, windowQueue.size());
        double slopePerHour = covariance / variance;
        return new Slope(slopePerHour * 24.0, windowQueue.size());
    }

    public synchronized void reset(Long pointId) {
        windows.remove(pointId);
    }

    /** 窗口内 k(t) 的变化量（max − min），供调用方判断斜率是否代表实质性劣化；无样本返回 0。 */
    public synchronized double ratioRange(Long pointId) {
        ArrayDeque<Sample> windowQueue = windows.get(pointId);
        if (windowQueue == null || windowQueue.isEmpty()) return 0.0;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Sample sample : windowQueue) {
            min = Math.min(min, sample.ratio());
            max = Math.max(max, sample.ratio());
        }
        return max - min;
    }

    /** 时序动态衰减乘子（文档 §4.3 雪崩抑制）。 */
    public static double etaDyn(double slopePerDay) {
        if (!Double.isFinite(slopePerDay) || slopePerDay <= SLOPE_DEAD_BAND) return 1.0;
        return Math.exp(-ETA_DECAY_RATE * (slopePerDay - SLOPE_DEAD_BAND));
    }

    public record Slope(double betaPerDay, int samples) {}

    private record Sample(long timestamp, double ratio) {
        Instant instant() { return Instant.ofEpochMilli(timestamp); }
    }
}
