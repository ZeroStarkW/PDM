package com.pdm.mvp.dsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 文档 §9 验收矩阵 DSP 侧用例：
 * TC-01 转速微漂抗噪验证（Band RMS）、TC-02 轴承微弱点蚀捕获（Hilbert Envelope），
 * 外加 FFT/带通基础正确性佐证。
 */
class DspAcceptanceTest {

    /** 相对基线 Sigmoid 曲线上恰好等于 60 分的恶化倍率 k（100/(1+((k-1)/1.5)^2.2)=60）。 */
    private static final double K_AT_SCORE_60 = 2.2476;

    @Test
    void fftAmplitudeReadsTrueToneAmplitude() {
        int n = 4096;
        double fs = 4096.0;
        double[] signal = new double[n];
        for (int i = 0; i < n; i++) signal[i] = 0.5 * Math.sin(2 * Math.PI * 64.0 * i / fs);
        Fft.Spectrum spectrum = Fft.amplitudeSpectrum(signal, fs);
        assertEquals(0.5, BandRms.of(spectrum, 64.0), 0.01);
    }

    @Test
    void bandpassRejectsOutOfBandTone() {
        int n = 8192;
        double fs = 8192.0;
        double[] signal = new double[n];
        for (int i = 0; i < n; i++) signal[i] = 1.0 * Math.sin(2 * Math.PI * 25.0 * i / fs);
        double[] bandpassed = new ButterworthBandpass(fs, 1000.0, 2500.0).filter(signal);
        // 25 Hz 转频分量应被通带外深度衰减（>=30 倍）
        double outOfBand = rms(bandpassed);
        assertTrue(outOfBand < 0.03, "阻带残余过大: " + outOfBand);
    }

    /** TC-01：转频 24.8~25.2 Hz 随机漂移，振幅恒为 0.5 —— Band RMS 输出波动 ≤1.5%，
     *  而单点峰值搜索（未加窗）出现 >15% 的扇贝损失跌落，被 Band RMS 成功纠正。 */
    @Test
    void tc01_bandRmsSurvivesRotationDrift() {
        int n = 8192;
        double fs = 1024.0; // Δf = 0.125 Hz，漂移 ±0.2Hz 相当于 ±1.6 个 bin
        double amplitude = 0.5;
        Random random = new Random(20261123L);
        List<Double> bandValues = new ArrayList<>();
        List<Double> naivePeakValues = new ArrayList<>();
        for (int frame = 0; frame < 40; frame++) {
            double f1 = 24.8 + random.nextDouble() * 0.4;
            double[] signal = new double[n];
            for (int i = 0; i < n; i++)
                signal[i] = amplitude * Math.sin(2 * Math.PI * f1 * i / fs) + 0.002 * random.nextGaussian();
            Fft.Spectrum spectrum = Fft.amplitudeSpectrum(signal, fs);
            bandValues.add(BandRms.of(spectrum, 25.0));
            naivePeakValues.add(peakBinAmplitude(signal, fs, 20.0, 30.0));
        }
        double bandMean = bandValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double bandMax = bandValues.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double bandMin = bandValues.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double bandSpread = (bandMax - bandMin) / bandMean;
        assertTrue(Math.abs(bandMean - amplitude) / amplitude < 0.03,
                "Band RMS 幅值失真: mean=" + bandMean);
        assertTrue(bandSpread <= 0.015, "Band RMS 波动超差: " + bandSpread);

        double peakMax = naivePeakValues.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double peakMin = naivePeakValues.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double peakDrop = (peakMax - peakMin) / peakMax;
        assertTrue(peakDrop > 0.15, "单点峰值搜索未复现 >15% 跌落: " + peakDrop);
    }

    /** TC-02：1X 仅 0.40（极低），2 kHz 载波注入 140 Hz 调制微脉冲 —— 包络谱 140Hz 检出尖峰，
     *  且恶化倍率 k≈2.25 时 HI 评分准确扣至 60 分。 */
    @Test
    void tc02_hilbertEnvelopeCapturesBearingPitting() {
        int n = 8192;
        double fs = 8192.0;
        double f1 = 25.0, bpfo = 140.0, carrier = 2000.0;
        double[] signal = new double[n];
        Random random = new Random(42L);
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            double value = 0.40 * Math.sin(2 * Math.PI * f1 * t)
                    + 0.08 * Math.sin(2 * Math.PI * 2 * f1 * t)
                    + 0.02 * random.nextGaussian();
            signal[i] = value;
        }
        // 每个 BPFO 周期注入一次指数衰减共振冲击（经典外圈点蚀模型）
        double shockInterval = fs / bpfo;
        for (int k = 0; shockInterval * k < n - 64; k++) {
            int start = (int) Math.round(shockInterval * k);
            for (int j = 0; j < 64 && start + j < n; j++) {
                double tau = j / fs;
                signal[start + j] += 0.30 * Math.exp(-160.0 * tau) * Math.sin(2 * Math.PI * carrier * tau);
            }
        }
        Fft.Spectrum envelope = HilbertEnvelope.envelopeSpectrum(signal, fs, 1000.0, 2500.0);
        double bpfoBand = BandRms.of(envelope, bpfo);
        double floor = noiseFloor(envelope, 30.0, 500.0, bpfo - 15.0, bpfo + 15.0);
        double snr = bpfoBand / Math.max(1e-9, floor);
        assertTrue(snr > 5.0, "包络谱 140Hz 特征尖峰不显著: SNR=" + snr);

        double baseline = bpfoBand / K_AT_SCORE_60;
        double healthScore = com.pdm.mvp.analytics.HealthScale.relativeScore(bpfoBand, baseline);
        assertEquals(60.0, healthScore, 0.5, "k=2.25 时 HI 应扣至 60 分");
    }

    /** 未加窗单 bin 峰值搜索（常见朴素实现）：返回 [low, high] 带内最大 bin 幅值。 */
    private static double peakBinAmplitude(double[] signal, double fs, double lowHz, double highHz) {
        double[] re = signal.clone();
        double[] im = new double[signal.length];
        Fft.transform(re, im);
        double max = 0.0;
        for (int k = 0; k <= signal.length / 2; k++) {
            double freq = k * fs / signal.length;
            if (freq < lowHz || freq > highHz) continue;
            max = Math.max(max, Math.hypot(re[k], im[k]) * 2.0 / signal.length);
        }
        return max;
    }

    /** 包络谱噪底：频带内幅值中位数（排除目标特征 ±15Hz 邻域）。 */
    private static double noiseFloor(Fft.Spectrum spectrum, double lowHz, double highHz,
            double excludeLowHz, double excludeHighHz) {
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < spectrum.frequencies().length; i++) {
            double freq = spectrum.frequencies()[i];
            if (freq < lowHz || freq > highHz) continue;
            if (freq >= excludeLowHz && freq <= excludeHighHz) continue;
            values.add(spectrum.amplitudes()[i]);
        }
        values.sort(Double::compare);
        return values.isEmpty() ? 0.0 : values.get(values.size() / 2);
    }

    private static double rms(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value * value;
        return Math.sqrt(sum / values.length);
    }
}
