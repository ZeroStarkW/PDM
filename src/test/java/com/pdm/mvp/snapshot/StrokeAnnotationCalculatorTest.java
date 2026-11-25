package com.pdm.mvp.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pdm.mvp.snapshot.StrokeAnnotationCalculator.StrokeAnnotations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 行程标注计算：合成 50ms 分闸曲线的起动/分离/速度/制动/总时长标注与退化输入安全。 */
class StrokeAnnotationCalculatorTest {

    /** 合成 50ms 分闸动作：线圈电流 12ms 升至峰值 5.42A 后 13ms 衰减；行程 18.5ms 起动、
     *  12ms 速度平台（14mm）后 12ms 衰减，42.5ms 到达满行程 20mm。 */
    private static List<Double> timeAxis() {
        List<Double> time = new ArrayList<>();
        for (int i = 0; i <= 100; i++) time.add(i * 0.5);
        return time;
    }

    private static double currentAt(double t) {
        if (t <= 12.0) return 5.42 * t / 12.0;
        return Math.max(0.0, 5.42 * (25.0 - t) / 13.0);
    }

    private static double travelAt(double t) {
        if (t <= 18.5) return 0.0;
        if (t <= 30.5) return 14.0 * (t - 18.5) / 12.0;
        if (t <= 42.5) return 14.0 + 6.0 * (t - 30.5) / 12.0;
        return 20.0;
    }

    private static List<Double> sampled(java.util.function.DoubleUnaryOperator fn, List<Double> time) {
        List<Double> values = new ArrayList<>(time.size());
        for (double t : time) values.add(fn.applyAsDouble(t));
        return values;
    }

    @Test
    void annotatesSyntheticTripWaveform() {
        List<Double> time = timeAxis();
        StrokeAnnotations a = StrokeAnnotationCalculator.analyze(time,
                sampled(StrokeAnnotationCalculatorTest::currentAt, time),
                sampled(StrokeAnnotationCalculatorTest::travelAt, time));
        assertEquals(5.42, a.peakCurrentA(), 0.05, "线圈电流峰值");
        assertEquals(20.0, a.fullStrokeMm(), 0.05, "满行程");
        assertEquals(1.5, a.tripStartMs(), 0.1, "电流达峰值 10% 的起动点");
        assertEquals(19.5, a.contactSeparationMs(), 0.1, "行程达满行程 5% 的分离点");
        assertEquals(1.2, a.maxVelocityMps(), 0.05, "平台段速度 14/12 mm/ms");
        assertEquals(42.0, a.brakingMs(), 0.1, "速度最后一次不低于峰值 20% 的时刻");
        assertEquals(39.0, a.totalTimeMs(), 0.6, "行程达 95% 时刻 − 起动时刻");
    }

    @Test
    void emptyOrMismatchedArraysYieldAllNullAnnotations() {
        assertEquals(StrokeAnnotations.empty(), StrokeAnnotationCalculator.analyze(List.of(), List.of(), List.of()));
        // F22：长度不齐按公共最短长度裁齐分析（差一个样本不再整体作废，仍保留逐点安全检查）
        assertEquals(0.0, StrokeAnnotationCalculator.analyze(List.of(0.0, 1.0), List.of(0.0), List.of(0.0, 1.0))
                .peakCurrentA(), 1e-9);
        assertEquals(StrokeAnnotations.empty(),
                StrokeAnnotationCalculator.analyze(null, List.of(0.0, 1.0), List.of(0.0, 1.0)));
        // 空序列仍返回全 null
        assertNull(StrokeAnnotationCalculator.analyze(List.of(0.0, 1.0), List.of(), List.of(0.0, 1.0)).peakCurrentA());
    }

    @Test
    void mismatchedLengthsAreTrimmedToCommonPrefixInsteadOfNulled() {
        // F22：三输入裁齐到公共最短长度后照常计算（time/travel 各 3 点、current 仅 2 点 → 前 2 点参与分析）
        StrokeAnnotations a = StrokeAnnotationCalculator.analyze(
                List.of(0.0, 1.0, 2.0), List.of(1.0, 2.5), List.of(0.0, 0.5, 1.0));
        assertEquals(2.5, a.peakCurrentA(), 1e-9, "峰值只统计裁齐后的前 2 点");
        assertEquals(0.5, a.fullStrokeMm(), 1e-9);
    }

    @Test
    void fromCurvesWithoutCurvesIsEmptyAndNeverThrows() {
        assertTrue(StrokeAnnotationCalculator.fromCurves(null, 10000.0).isEmpty());
        assertTrue(StrokeAnnotationCalculator.fromCurves(Map.of(), 10000.0).isEmpty());
        // preview-only：无曲线时标注为空（服务层落 null）
        assertTrue(StrokeAnnotationCalculator
                .fromCurves(Map.of("other_curve", List.of(1.0, 2.0)), 10000.0).isEmpty());
        // 缺时间轴且无采样率时无法推导，安全返回空
        assertTrue(StrokeAnnotationCalculator.fromCurves(curves(null, List.of(0.0, 1.0), List.of(0.0, 1.0)),
                null).isEmpty());
    }

    @Test
    void fromCurvesDerivesTimeAxisFromSamplingRate() {
        // dt = 1000/2000 = 0.5ms：电流 0→5.42 线性 12 点（t=6ms 达峰），行程 i=18 起动 6 点升至 10mm
        List<Double> current = new ArrayList<>();
        List<Double> travel = new ArrayList<>();
        for (int i = 0; i <= 30; i++) {
            current.add(Math.min(5.42, i * 5.42 / 12.0));
            travel.add(Math.min(10.0, Math.max(0.0, (i - 18) * 10.0 / 6.0)));
        }
        StrokeAnnotations a = StrokeAnnotationCalculator.fromCurves(
                curves(null, current, travel), 2000.0);
        assertEquals(5.42, a.peakCurrentA(), 0.05);
        assertEquals(10.0, a.fullStrokeMm(), 0.05);
        // 10% 峰值阈值（0.542A）在 t = 1.0ms 处首次跨过（斜率 5.42A/6ms）
        assertEquals(1.0, a.tripStartMs(), 0.1);
        // 平台段速度 (10/6) mm / 0.5ms ≈ 3.33 m/s
        assertEquals(3.3, a.maxVelocityMps(), 0.05);
        // 95% 满行程（9.5mm）在 t = 12ms 达到：总时长 = 12 − 1
        assertEquals(11.0, a.totalTimeMs(), 0.6);
    }

    @Test
    void toMapUsesCamelCaseContractKeysAndDropsNulls() {
        Map<String, Object> map = StrokeAnnotations.empty().toMap();
        assertTrue(map.isEmpty());
        StrokeAnnotations a = new StrokeAnnotations(1.5, 19.5, 42.0, 39.0, 5.42, 20.0, 1.2);
        map = a.toMap();
        assertEquals(List.of("tripStartMs", "contactSeparationMs", "brakingMs", "totalTimeMs",
                "peakCurrentA", "fullStrokeMm", "maxVelocityMps"), List.copyOf(map.keySet()));
    }

    private static Map<String, List<Double>> curves(List<Double> time, List<Double> current, List<Double> travel) {
        Map<String, List<Double>> curves = new LinkedHashMap<>();
        if (time != null) curves.put(StrokeAnnotationCalculator.TIME_AXIS_MS, time);
        curves.put(StrokeAnnotationCalculator.COIL_CURRENT_A, current);
        curves.put(StrokeAnnotationCalculator.TRAVEL_STROKE_MM, travel);
        return curves;
    }
}
