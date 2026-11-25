package com.pdm.mvp.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 频带网格：1/6 倍频程覆盖、带边连续、带内能量聚合（含无覆盖带的 NaN 占位）。 */
class SpectrumBandGridTest {

    @Test
    void fftGridCoversTwoToTwoThousandHz() {
        var bands = SpectrumBandGrid.fftBands();
        assertEquals(2.0, bands.getFirst().centerHz(), 1e-9);
        assertTrue(bands.getLast().centerHz() <= SpectrumBandGrid.FFT_MAX_HZ);
        assertTrue(bands.getLast().centerHz() * Math.pow(2, 1.0 / SpectrumBandGrid.OCTAVE_FRACTION) > SpectrumBandGrid.FFT_MAX_HZ,
                "再往上的中心频率应超出上限，说明网格截断正确");
        // 2→2000 跨 log2(1000)≈9.97 个倍频程，1/6 倍频程 → 60 带
        assertEquals(60, bands.size());
        assertEquals(bands.size(), bands.getFirst().index() + bands.size());
    }

    @Test
    void envelopeGridTruncatedAtFiveHundredHz() {
        var bands = SpectrumBandGrid.envelopeBands();
        assertEquals(48, bands.size());
        assertTrue(bands.getLast().highHz() <= SpectrumBandGrid.ENVELOPE_MAX_HZ * Math.pow(2, 1.0 / 12) + 1e-9);
    }

    @Test
    void bandEdgesAreContiguous() {
        var bands = SpectrumBandGrid.fftBands();
        for (int i = 1; i < bands.size(); i++) {
            assertEquals(bands.get(i - 1).highHz(), bands.get(i).lowHz(), 1e-9, "相邻频带共享边界");
            assertTrue(bands.get(i).lowHz() < bands.get(i).centerHz());
            assertTrue(bands.get(i).centerHz() < bands.get(i).highHz());
        }
    }

    @Test
    void aggregateSumsBandEnergyAndMarksUncoveredAsNaN() {
        var bands = SpectrumBandGrid.fftBands();
        double toneHz = bands.get(20).centerHz(); // 带宽 = 中心 × 2^(±1/12)，取带内两根 bin
        double[] freqs = {toneHz * 0.97, toneHz * 1.03};
        double[] amps = {3, 4};
        double[] values = SpectrumBandGrid.aggregate(freqs, amps, bands);
        assertEquals(5.0, values[20], 1e-9, "带内幅值平方和开方 3²+4²=5");
        for (int i = 0; i < values.length; i++) {
            if (i != 20) assertTrue(Double.isNaN(values[i]), "无 bin 覆盖的带应为 NaN 占位");
        }
    }

    @Test
    void aggregateRejectsMismatchedArrays() {
        var bands = SpectrumBandGrid.fftBands();
        double[] values = SpectrumBandGrid.aggregate(new double[] {10}, new double[] {1, 2}, bands);
        for (double value : values) assertTrue(Double.isNaN(value));
    }
}
