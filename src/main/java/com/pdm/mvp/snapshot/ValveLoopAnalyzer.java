package com.pdm.mvp.snapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调节阀回环分析器（协议 2.1.0 §2.2，前端迟滞回环工作台展示用，非计量结论）。
 * 输入：时间有序的开度指令/反馈 (cv, fb) 百分比对。定义：
 * <ul>
 *   <li>reversalCount —— cv 差分符号翻转次数（|Δcv| 超过换向死区才计入方向：指令全量程的 0.1%，下限 1e-9，
 *       抖动/量化噪声不再伪计换向）</li>
 *   <li>maxDeviationPct —— |fb − cv| 最大值</li>
 *   <li>loopAreaPct2 —— (cv, fb) 折线闭合多边形鞋带面积绝对值（%²，末点回连首点；不足 3 点为 0）</li>
 *   <li>hysteresisPct —— 按 cv 全量程 20 等分分箱，双向均访问过的箱内
 *       |上行均值 fb − 下行均值 fb| 的最大值（缺任一方向为 0）</li>
 *   <li>deadbandEstimatePct —— |Δcv| 最低 10% 样本（近静止指令段）的 |fb − cv| 均值；
 *       全部 Δcv ≈ 0 时任取子集均值即全样本均值（等偏差构造下等于 maxDeviation）</li>
 * </ul>
 * 全部输出保留 2 位小数；空输入返回全零结果（绝不抛异常）。
 */
public final class ValveLoopAnalyzer {
    /** 死区判定阈值：|Δcv| 低于该值视为指令静止。 */
    static final double DELTA_EPS = 1e-9;
    /** 换向死区：|Δcv| 低于指令全量程该比例时不算方向翻转（0.1% of span）。 */
    static final double REVERSAL_DEADBAND_RATIO = 0.001;
    /** 迟滞分箱数（全量程 20 等分）。 */
    static final int BINS = 20;

    private ValveLoopAnalyzer() {}

    /** 回环样本：开度指令 cv（%）、反馈 fb（%）。 */
    public record ValveSample(double cv, double fb) {}

    /** 回环分析结果（数值保留 2 位小数；reversalCount 为整数计数）。 */
    public record ValveLoopAnalysis(double deadbandEstimatePct, double loopAreaPct2, double hysteresisPct,
            double maxDeviationPct, int reversalCount) {

        public static ValveLoopAnalysis empty() { return new ValveLoopAnalysis(0.0, 0.0, 0.0, 0.0, 0); }

        /** 前端契约 camelCase 键视图。 */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("deadbandEstimatePct", deadbandEstimatePct);
            map.put("loopAreaPct2", loopAreaPct2);
            map.put("hysteresisPct", hysteresisPct);
            map.put("maxDeviationPct", maxDeviationPct);
            map.put("reversalCount", reversalCount);
            return map;
        }
    }

    /** 从曲线映射解析：按曲线编码 cv_pct/fb_pct 就近配对（时间序即存储序）；缺失视为空输入。 */
    public static ValveLoopAnalysis fromCurves(Map<String, List<Double>> curves) {
        if (curves == null || curves.isEmpty()) return ValveLoopAnalysis.empty();
        List<Double> cv = curves.get("cv_pct");
        List<Double> fb = curves.get("fb_pct");
        if (cv == null || fb == null) return ValveLoopAnalysis.empty();
        int n = Math.min(cv.size(), fb.size());
        List<ValveSample> samples = new ArrayList<>(n);
        for (int i = 0; i < n; i++) samples.add(new ValveSample(cv.get(i), fb.get(i)));
        return analyze(samples);
    }

    /** 主计算：空输入返回全零结果。 */
    public static ValveLoopAnalysis analyze(List<ValveSample> samples) {
        if (samples == null || samples.isEmpty()) return ValveLoopAnalysis.empty();
        int n = samples.size();

        // 0. 指令全量程：换向死区与迟滞分箱都以它为基准
        double minCv = Double.POSITIVE_INFINITY;
        double maxCv = Double.NEGATIVE_INFINITY;
        for (ValveSample sample : samples) {
            minCv = Math.min(minCv, sample.cv());
            maxCv = Math.max(maxCv, sample.cv());
        }
        double span = maxCv - minCv;
        double reversalDeadband = Math.max(DELTA_EPS, span * REVERSAL_DEADBAND_RATIO);

        // 1. 指令方向翻转计数（抖动/量化噪声低于换向死区不计方向）
        int reversals = 0;
        int direction = 0;
        List<Double> deltas = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double delta = i == 0 ? 0.0 : samples.get(i).cv() - samples.get(i - 1).cv();
            deltas.add(delta);
            if (Math.abs(delta) <= reversalDeadband) continue;
            int sign = delta > 0 ? 1 : -1;
            if (direction != 0 && sign != direction) reversals++;
            direction = sign;
        }

        // 2. 偏差与闭合鞋带面积（末点回连首点，开折线也围出真实回线面积）
        double maxDeviation = 0.0;
        double shoelace = 0.0;
        for (int i = 0; i < n; i++) {
            ValveSample sample = samples.get(i);
            double deviation = Math.abs(sample.fb() - sample.cv());
            if (deviation > maxDeviation) maxDeviation = deviation;
            if (i > 0) {
                ValveSample previous = samples.get(i - 1);
                shoelace += previous.cv() * sample.fb() - sample.cv() * previous.fb();
            }
        }
        if (n >= 3) {
            ValveSample first = samples.getFirst();
            ValveSample last = samples.getLast();
            shoelace += last.cv() * first.fb() - first.cv() * last.fb();
        }
        double loopArea = n < 3 ? 0.0 : Math.abs(shoelace) / 2.0;

        // 3. 双向迟滞分箱
        double hysteresis = 0.0;
        if (span > DELTA_EPS) {
            Map<Integer, List<Double>> upward = new HashMap<>();
            Map<Integer, List<Double>> downward = new HashMap<>();
            double binWidth = span / BINS;
            for (int i = 0; i < n; i++) {
                int bin = Math.max(0, Math.min(BINS - 1, (int) ((samples.get(i).cv() - minCv) / binWidth)));
                if (deltas.get(i) > DELTA_EPS) upward.computeIfAbsent(bin, k -> new ArrayList<>()).add(samples.get(i).fb());
                else if (deltas.get(i) < -DELTA_EPS) downward.computeIfAbsent(bin, k -> new ArrayList<>()).add(samples.get(i).fb());
            }
            for (Integer bin : upward.keySet()) {
                List<Double> up = upward.get(bin);
                List<Double> down = downward.get(bin);
                if (down == null || down.isEmpty()) continue;
                double gap = Math.abs(mean(up) - mean(down));
                if (gap > hysteresis) hysteresis = gap;
            }
        }

        // 4. 近静止死区：|Δcv| 最低 10% 样本（数量取底分位，至少 1 个）的 |fb − cv| 均值
        List<Double> absDeltas = new ArrayList<>(deltas);
        for (int i = 0; i < absDeltas.size(); i++) absDeltas.set(i, Math.abs(absDeltas.get(i)));
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(absDeltas.get(a), absDeltas.get(b)));
        int stationary = Math.max(1, (int) Math.ceil(n * 0.10));
        double deadbandSum = 0.0;
        for (int i = 0; i < stationary; i++) {
            int index = order[i];
            deadbandSum += Math.abs(samples.get(index).fb() - samples.get(index).cv());
        }
        double deadband = deadbandSum / stationary;

        return new ValveLoopAnalysis(round2(deadband), round2(loopArea), round2(hysteresis),
                round2(maxDeviation), reversals);
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (Double value : values) sum += value;
        return values.isEmpty() ? 0.0 : sum / values.size();
    }

    private static double round2(double value) { return Math.round(value * 100.0) / 100.0; }
}
