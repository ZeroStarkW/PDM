package com.pdm.mvp.dsp;

/**
 * 频带能量积分（Band RMS）：在目标特征频率 f0 两侧开辟带宽容差
 * （默认 ±3%，且不小于 1.5 个 bin 以覆盖汉宁窗主瓣），区间内幅值平方和开方，
 * 并以 2/3 因子补偿汉宁窗能量扩散。禁止对转频类特征使用单点峰值搜索。
 */
public final class BandRms {
    private static final double DEFAULT_TOLERANCE = 0.03;

    private BandRms() {}

    public static double of(Fft.Spectrum spectrum, double targetHz) {
        return of(spectrum.frequencies(), spectrum.amplitudes(), targetHz, DEFAULT_TOLERANCE);
    }

    public static double of(double[] frequencies, double[] amplitudes, double targetHz) {
        return of(frequencies, amplitudes, targetHz, DEFAULT_TOLERANCE);
    }

    public static double of(double[] frequencies, double[] amplitudes, double targetHz, double tolerance) {
        if (frequencies == null || amplitudes == null || frequencies.length != amplitudes.length
                || frequencies.length == 0) return 0.0;
        if (!Double.isFinite(targetHz) || targetHz <= 0 || tolerance < 0 || tolerance >= 1) return 0.0;
        double binWidth = frequencies.length > 1
                ? Math.abs(frequencies[1] - frequencies[0])
                : 0.0;
        double halfWidth = Math.max(targetHz * tolerance, 1.5 * binWidth);
        double sumSquares = 0.0;
        boolean matched = false;
        for (int i = 0; i < frequencies.length; i++) {
            if (frequencies[i] >= targetHz - halfWidth && frequencies[i] <= targetHz + halfWidth) {
                sumSquares += amplitudes[i] * amplitudes[i];
                matched = true;
            }
        }
        return matched ? Math.sqrt(sumSquares * (2.0 / 3.0)) : 0.0;
    }
}
