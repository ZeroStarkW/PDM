package com.pdm.mvp.snapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 断路器分/合闸行程曲线标注计算器（协议 2.1.0 §2.2，前端行程工作台展示用启发式标注，非计量结论）。
 * 输入：时间轴 time_axis_ms（缺失时按 t[i] = i*1000/samplingRate 推导）、线圈电流 coil_current_a（A）、
 * 触头行程 travel_stroke_mm（mm）。定义：
 * <ul>
 *   <li>tripStartMs —— 分闸起动点：电流首次达到峰值 10% 的时刻（永不达到则记 0）</li>
 *   <li>contactSeparationMs —— 触头分离点：起动后行程首次达到满行程 5% 的时刻</li>
 *   <li>maxVelocityMps —— 最大分闸速度：行程差分速度峰值（mm/ms 换算 m/s，即 Δs/Δt）</li>
 *   <li>brakingMs —— 缓冲制动终点：速度最后一次不低于峰值 20% 的时刻（速度始终为 0 时无值）</li>
 *   <li>totalTimeMs —— 动作总时长：行程首次达到满行程 95% 的时刻 − 起动时刻</li>
 * </ul>
 * 全部输出保留 1 位小数；数组为空/无法推导时间轴时返回全 null 标注（绝不抛异常；长度不齐按公共最短长度裁齐）。
 */
public final class StrokeAnnotationCalculator {
    public static final String TIME_AXIS_MS = "time_axis_ms";
    public static final String COIL_CURRENT_A = "coil_current_a";
    public static final String TRAVEL_STROKE_MM = "travel_stroke_mm";

    private StrokeAnnotationCalculator() {}

    /** 行程标注结果（Double 均可空；toMap 输出前端契约 camelCase 键并剔除 null 值）。 */
    public record StrokeAnnotations(Double tripStartMs, Double contactSeparationMs, Double brakingMs,
            Double totalTimeMs, Double peakCurrentA, Double fullStrokeMm, Double maxVelocityMps) {

        public static StrokeAnnotations empty() {
            return new StrokeAnnotations(null, null, null, null, null, null, null);
        }

        public boolean isEmpty() {
            return tripStartMs == null && contactSeparationMs == null && brakingMs == null
                    && totalTimeMs == null && peakCurrentA == null && fullStrokeMm == null && maxVelocityMps == null;
        }

        /** 前端契约 camelCase 键视图（null 值剔除，永不为 null）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (tripStartMs != null) map.put("tripStartMs", tripStartMs);
            if (contactSeparationMs != null) map.put("contactSeparationMs", contactSeparationMs);
            if (brakingMs != null) map.put("brakingMs", brakingMs);
            if (totalTimeMs != null) map.put("totalTimeMs", totalTimeMs);
            if (peakCurrentA != null) map.put("peakCurrentA", peakCurrentA);
            if (fullStrokeMm != null) map.put("fullStrokeMm", fullStrokeMm);
            if (maxVelocityMps != null) map.put("maxVelocityMps", maxVelocityMps);
            return map;
        }
    }

    /** 从曲线映射解析：时间轴缺失时按采样率推导 t[i] = i*1000/samplingRate；无法推导返回全 null。 */
    public static StrokeAnnotations fromCurves(Map<String, List<Double>> curves, Double samplingRate) {
        if (curves == null || curves.isEmpty()) return StrokeAnnotations.empty();
        List<Double> current = curves.get(COIL_CURRENT_A);
        List<Double> travel = curves.get(TRAVEL_STROKE_MM);
        List<Double> time = curves.get(TIME_AXIS_MS);
        if (time == null || time.isEmpty()) {
            if (samplingRate == null || samplingRate <= 0 || current == null || current.isEmpty()) {
                return StrokeAnnotations.empty();
            }
            time = new java.util.ArrayList<>(current.size());
            for (int i = 0; i < current.size(); i++) time.add(i * 1000.0 / samplingRate);
        }
        return analyze(time, current, travel);
    }

    /** 主计算：三数组按公共最短长度裁齐后分析（差一个样本不再整体作废）；任一序列为空返回全 null 标注。 */
    public static StrokeAnnotations analyze(List<Double> time, List<Double> current, List<Double> travel) {
        if (time == null || current == null || travel == null) return StrokeAnnotations.empty();
        if (time.isEmpty() || current.isEmpty() || travel.isEmpty()) return StrokeAnnotations.empty();
        int n = Math.min(time.size(), Math.min(current.size(), travel.size()));
        time = time.subList(0, n);
        current = current.subList(0, n);
        travel = travel.subList(0, n);

        Double peak = max(current);
        Double stroke = max(travel);
        Double tripStart = 0.0;
        if (peak != null) {
            double threshold = peak * 0.10;
            tripStart = firstCrossing(time, current, threshold, 0);
            if (tripStart == null) tripStart = 0.0;
        }
        Double contactSeparation = null;
        if (stroke != null) {
            double threshold = stroke * 0.05;
            for (int i = 0; i < n; i++) {
                Double t = finite(time.get(i));
                Double s = finite(travel.get(i));
                if (t == null || s == null) continue;
                if (t >= tripStart && s >= threshold) { contactSeparation = round1(t); break; }
            }
        }
        // 差分速度（mm→m、ms→s：1 mm/ms = 1 m/s），峰值与 20% 制动阈值
        Double maxVelocity = null;
        Double braking = null;
        List<Double> sampleTimes = new java.util.ArrayList<>();
        List<Double> velocities = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < n; i++) {
            Double dt = sub(time.get(i + 1), time.get(i));
            Double ds = sub(travel.get(i + 1), travel.get(i));
            if (dt == null || ds == null || dt <= 0) continue;
            double velocity = ds / (dt / 1000.0) / 1000.0;
            sampleTimes.add(time.get(i));
            velocities.add(velocity);
            if (maxVelocity == null || velocity > maxVelocity) maxVelocity = velocity;
        }
        if (maxVelocity != null && maxVelocity > 0) {
            double threshold = maxVelocity * 0.20;
            for (int i = velocities.size() - 1; i >= 0; i--) {
                if (velocities.get(i) >= threshold) { braking = round1(sampleTimes.get(i)); break; }
            }
        }
        Double totalTime = null;
        if (stroke != null) {
            double threshold = stroke * 0.95;
            for (int i = 0; i < n; i++) {
                Double t = finite(time.get(i));
                Double s = finite(travel.get(i));
                if (t == null || s == null) continue;
                if (s >= threshold) { totalTime = round1(t - tripStart); break; }
            }
        }
        return new StrokeAnnotations(round1(tripStart), contactSeparation, braking, totalTime,
                peak == null ? null : round1(peak), stroke == null ? null : round1(stroke),
                maxVelocity == null ? null : round1(maxVelocity));
    }

    private static Double max(List<Double> values) {
        Double best = null;
        for (Double value : values) {
            Double v = finite(value);
            if (v != null && (best == null || v > best)) best = v;
        }
        return best;
    }

    private static Double firstCrossing(List<Double> time, List<Double> values, double threshold, int from) {
        for (int i = from; i < values.size(); i++) {
            Double t = finite(time.get(i));
            Double v = finite(values.get(i));
            if (t == null || v == null) continue;
            if (v >= threshold) return round1(t);
        }
        return null;
    }

    private static Double sub(Double a, Double b) {
        if (a == null || b == null) return null;
        double value = a - b;
        return Double.isFinite(value) ? value : null;
    }

    private static Double finite(Double value) {
        return value != null && Double.isFinite(value) ? value : null;
    }

    private static double round1(double value) { return Math.round(value * 10.0) / 10.0; }
}
