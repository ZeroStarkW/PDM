package com.pdm.mvp.analytics;

import java.util.ArrayList;
import java.util.List;

/**
 * 频谱基线带的频带网格（文档 §4.2 谱域扩展）：
 * 1/6 倍频程对数频带，覆盖 {@link #LOW_HZ}~上限；FFT 全谱上限 2000 Hz（与工作站 FFT 视图一致），
 * 包络谱上限 500 Hz（与工作站包络视图一致）。逐带能量为带内 bin 幅值平方和开方，
 * 学习与对比共用同一聚合口径。
 */
public final class SpectrumBandGrid {
    /** 网格起始频率（Hz）：低于该值的低频段 bin 过少，统计噪声过大。 */
    public static final double LOW_HZ = 2.0;
    public static final double FFT_MAX_HZ = 2000.0;
    public static final double ENVELOPE_MAX_HZ = 500.0;
    /** 1/6 倍频程。 */
    static final double OCTAVE_FRACTION = 6.0;

    private SpectrumBandGrid() {}

    public record Band(int index, double lowHz, double centerHz, double highHz) {}

    public static List<Band> fftBands() { return bands(FFT_MAX_HZ); }

    public static List<Band> envelopeBands() { return bands(ENVELOPE_MAX_HZ); }

    /** 中心频率 c_k = LOW_HZ · 2^(k/6)，带边 c_k · 2^(±1/12)，直到中心超过 maxHz。 */
    static List<Band> bands(double maxHz) {
        List<Band> result = new ArrayList<>();
        double edgeRatio = Math.pow(2, 1.0 / (2 * OCTAVE_FRACTION));
        for (int k = 0; ; k++) {
            double center = LOW_HZ * Math.pow(2, k / OCTAVE_FRACTION);
            if (center > maxHz) break;
            double low = center / edgeRatio;
            double high = center * edgeRatio;
            result.add(new Band(result.size(), low, center, high));
        }
        return List.copyOf(result);
    }

    /**
     * 频谱逐带聚合：带内（low &lt; freq ≤ high）幅值平方和开方；无 bin 覆盖的带为 NaN
     * （调用方转为 null 存储，不参与该带的统计）。
     */
    public static double[] aggregate(double[] freqs, double[] amps, List<Band> grid) {
        double[] values = new double[grid.size()];
        java.util.Arrays.fill(values, Double.NaN);
        if (freqs.length != amps.length) return values;
        for (Band band : grid) {
            double sum = 0;
            boolean covered = false;
            for (int i = 0; i < freqs.length; i++) {
                if (freqs[i] <= band.lowHz() || freqs[i] > band.highHz()) continue;
                sum += amps[i] * amps[i];
                covered = true;
            }
            if (covered) values[band.index()] = Math.sqrt(sum);
        }
        return values;
    }
}
