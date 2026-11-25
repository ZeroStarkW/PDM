package com.pdm.mvp.bearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 轴承特征频率几何解算（文档 §4.1）：种子库系数与公式互洽。 */
class BearingGeometryTest {

    @Test
    void skf6208FactorsMatchSeededLibraryValues() {
        var factors = BearingGeometryCalculator.factors(9, 12.700, 59.500, 0.0);
        assertEquals(3.5395, factors.bpfoFactor(), 1e-3);
        assertEquals(5.4605, factors.bpfiFactor(), 1e-3);
        assertEquals(2.2358, factors.bsfFactor(), 1e-3);
        assertEquals(0.3933, factors.ftfFactor(), 1e-3);
    }

    @Test
    void contactAngleShiftsEnergyToOuterRace() {
        var zero = BearingGeometryCalculator.factors(14, 10.0, 65.0, 0.0);
        var angled = BearingGeometryCalculator.factors(14, 10.0, 65.0, 10.0);
        assertEquals(5.9396, angled.bpfoFactor(), 1e-3);
        assertEquals(8.0604, angled.bpfiFactor(), 1e-3);
        // 接触角增大 → ν·cosα 减小 → BPFO 上移、BPFI 下移
        assertTrue(angled.bpfoFactor() > zero.bpfoFactor());
        assertTrue(angled.bpfiFactor() < zero.bpfiFactor());
    }

    @Test
    void frequenciesScaleWithRotationHz() {
        var factors = new BearingGeometryCalculator.BearingFactors(3.5395, 5.4605, 2.2358, 0.3933);
        var frequencies = BearingGeometryCalculator.frequencies(factors, 25.0);
        assertEquals(3.5395 * 25.0, frequencies.bpfoHz(), 1e-9);
        assertEquals(5.4605 * 25.0, frequencies.bpfiHz(), 1e-9);
    }

    @Test
    void rejectsInvalidGeometry() {
        try {
            BearingGeometryCalculator.factors(9, 60.0, 59.5, 0.0);
            throw new AssertionError("滚珠直径大于节圆直径应拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期路径
        }
    }
}
