package com.pdm.mvp.dsp;

/**
 * 希尔伯特变换包络解调流水线：
 * 带通滤波 → 解析信号 z = x + j·H[x] → 瞬时包络 |z| → 均值消除 → 汉宁窗包络谱。
 */
public final class HilbertEnvelope {
    private HilbertEnvelope() {}

    /** 解析信号取模得到瞬时包络（支持任意正整数长度，2 的幂走 radix-2，非 2 的幂走 Bluestein）。 */
    public static double[] envelope(double[] bandpassed) {
        int n = bandpassed.length;
        if (n <= 0) throw new IllegalArgumentException("包络解调长度必须为正整数");
        double[] re = bandpassed.clone();
        double[] im = new double[n];
        Fft.transform(re, im);
        // 单边谱构造解析信号：正频率幅值翻倍，负频率置零，直流与奈奎斯特项保持
        for (int k = 1; k < (n + 1) / 2; k++) {
            re[k] *= 2.0; im[k] *= 2.0;
            re[n - k] = 0.0; im[n - k] = 0.0;
        }
        Fft.inverse(re, im);
        double[] envelope = new double[n];
        for (int i = 0; i < n; i++) envelope[i] = Math.hypot(re[i], im[i]);
        return envelope;
    }

    /**
     * 完整解调谱：带通 → 包络 → 均值消除 → 单边幅值谱。
     * deepGroove/圆柱滚子轴承的 BPFO/BPFI 特征能量据此在低频区提取。
     */
    public static Fft.Spectrum envelopeSpectrum(double[] signal, double sampleRate,
            double bandLowHz, double bandHighHz) {
        double[] band = new ButterworthBandpass(sampleRate, bandLowHz, bandHighHz).filter(signal);
        double[] envelope = envelope(band);
        double mean = 0.0;
        for (double value : envelope) mean += value;
        mean /= envelope.length;
        for (int i = 0; i < envelope.length; i++) envelope[i] -= mean;
        return Fft.amplitudeSpectrum(envelope, sampleRate);
    }
}
