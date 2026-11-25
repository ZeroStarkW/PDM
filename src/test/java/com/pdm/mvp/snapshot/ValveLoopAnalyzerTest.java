package com.pdm.mvp.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pdm.mvp.snapshot.ValveLoopAnalyzer.ValveLoopAnalysis;
import com.pdm.mvp.snapshot.ValveLoopAnalyzer.ValveSample;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 回环分析：三角指令回环的方向翻转/迟滞/偏差/死区与空输入、全静止指令退化。 */
class ValveLoopAnalyzerTest {

    /** 回环构造：cv 0→50→0→50（两次换向）；上行 fb = cv + offset，下行 fb = cv（经典迟滞回线）。 */
    private static List<ValveSample> loop(double offset) {
        List<ValveSample> samples = new ArrayList<>();
        for (int cv = 0; cv <= 50; cv += 5) samples.add(new ValveSample(cv, cv + offset));
        for (int cv = 45; cv >= 0; cv -= 5) samples.add(new ValveSample(cv, cv));
        for (int cv = 5; cv <= 50; cv += 5) samples.add(new ValveSample(cv, cv + offset));
        return samples;
    }

    @Test
    void analyzesSyntheticLoopWithKnownOffset() {
        ValveLoopAnalysis a = ValveLoopAnalyzer.analyze(loop(2.0));
        assertEquals(2, a.reversalCount(), "0→50→0→50 含两次方向翻转");
        assertEquals(2.0, a.maxDeviationPct(), 1e-9, "上行偏差恒为 offset，下行恒为 0");
        assertEquals(2.0, a.hysteresisPct(), 1e-6, "同 cv 箱内上/下行 fb 均值差 = offset");
        assertEquals(2.0, a.deadbandEstimatePct(), 1e-6, "近静止子集全部落在上行偏差段");
        assertTrue(a.loopAreaPct2() > 0, "迟滞回线围出面积");
    }

    @Test
    void emptyInputYieldsZeroedResult() {
        assertEquals(ValveLoopAnalysis.empty(), ValveLoopAnalyzer.analyze(List.of()));
        assertEquals(ValveLoopAnalysis.empty(), ValveLoopAnalyzer.analyze(null));
        assertEquals(ValveLoopAnalysis.empty(), ValveLoopAnalyzer.fromCurves(null));
        assertEquals(ValveLoopAnalysis.empty(), ValveLoopAnalyzer.fromCurves(java.util.Map.of()));
    }

    @Test
    void singleSampleIsDeviationWithoutLoop() {
        ValveLoopAnalysis a = ValveLoopAnalyzer.analyze(List.of(new ValveSample(10, 12)));
        assertEquals(2.0, a.maxDeviationPct(), 1e-9);
        assertEquals(0.0, a.loopAreaPct2(), 1e-9);
        assertEquals(0.0, a.hysteresisPct(), 1e-9);
        assertEquals(0, a.reversalCount());
        assertEquals(2.0, a.deadbandEstimatePct(), 1e-9);
    }

    @Test
    void stationaryCommandCountsAllSamplesAsDeadband() {
        // 全部 Δcv ≈ 0：无翻转、无迟滞分箱；等偏差构造下 deadband = maxDeviation
        List<ValveSample> samples = List.of(new ValveSample(20, 18), new ValveSample(20, 18),
                new ValveSample(20, 18), new ValveSample(20, 18));
        ValveLoopAnalysis a = ValveLoopAnalyzer.analyze(samples);
        assertEquals(0, a.reversalCount());
        assertEquals(0.0, a.hysteresisPct(), 1e-9);
        assertEquals(2.0, a.maxDeviationPct(), 1e-9);
        assertEquals(2.0, a.deadbandEstimatePct(), 1e-9);
        assertEquals(0.0, a.loopAreaPct2(), 1e-9);
    }

    @Test
    void fromCurvesPairsCvAndFbSeries() {
        ValveLoopAnalysis a = ValveLoopAnalyzer.fromCurves(java.util.Map.of(
                "cv_pct", List.of(0.0, 10.0, 20.0, 10.0, 0.0),
                "fb_pct", List.of(0.0, 12.0, 22.0, 10.0, 0.0),
                "other", List.of(1.0, 2.0)));
        assertEquals(1, a.reversalCount());
        assertEquals(2.0, a.maxDeviationPct(), 1e-9);
        assertTrue(a.loopAreaPct2() > 0);
    }

    @Test
    void toMapUsesCamelCaseContractKeys() {
        assertEquals(List.of("deadbandEstimatePct", "loopAreaPct2", "hysteresisPct",
                "maxDeviationPct", "reversalCount"),
                List.copyOf(ValveLoopAnalyzer.analyze(loop(2.0)).toMap().keySet()));
    }

    @Test
    void shoelaceClosesThePathBeforeComputingArea() {
        // F22：末点回连首点 —— 部分回环（起点 cv 25，终点未回到起点）也围出真实闭合正方形面积 625
        // （旧实现少加闭合项会算出 937.5）
        ValveLoopAnalysis a = ValveLoopAnalyzer.analyze(List.of(
                new ValveSample(25.0, 25.0), new ValveSample(50.0, 25.0),
                new ValveSample(50.0, 50.0), new ValveSample(25.0, 50.0)));
        assertEquals(625.0, a.loopAreaPct2(), 1e-9);
    }

    @Test
    void reversalCountingIgnoresJitterBelowSpanDeadband() {
        // F22：Δcv 在指令全量程 0.1%（span 40 → 0.04）以内的抖动不计换向（旧实现伪计 2 次）
        ValveLoopAnalysis jitter = ValveLoopAnalyzer.analyze(List.of(
                new ValveSample(0.0, 0.0), new ValveSample(20.0, 19.0),
                new ValveSample(20.03, 19.0), new ValveSample(20.01, 19.0),
                new ValveSample(40.0, 38.0)));
        assertEquals(0, jitter.reversalCount(), "0.03/0.02 的量化抖动不构成换向");
        // 真实换向（Δcv 10）仍然计入
        ValveLoopAnalysis real = ValveLoopAnalyzer.analyze(List.of(
                new ValveSample(0.0, 0.0), new ValveSample(20.0, 19.0),
                new ValveSample(10.0, 9.0), new ValveSample(40.0, 38.0)));
        assertEquals(2, real.reversalCount());
    }
}
