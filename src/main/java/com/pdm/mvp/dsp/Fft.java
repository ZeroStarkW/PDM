package com.pdm.mvp.dsp;

/**
 * 复数 FFT：2 的幂长度走迭代 radix-2（Cooley-Tukey），任意长度走 Bluestein Chirp-Z 精确变换（O(N log N)）；
 * 另附汉宁窗实信号单边幅值谱助手，归一化与等效噪声带宽同边缘端 numpy.fft.rfft 一致。
 */
public final class Fft {
    private Fft() {}

    public static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    /** 就地正变换（支持任意正整数长度）。 */
    public static void transform(double[] re, double[] im) {
        transform(re, im, false);
    }

    /** 就地逆变换（含 1/N 归一，支持任意正整数长度）。 */
    public static void inverse(double[] re, double[] im) {
        transform(re, im, true);
    }

    private static void transform(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        if (n <= 0 || im.length != n) throw new IllegalArgumentException("FFT 长度必须为正且实虚部等长");
        if (n == 1) return;
        if (isPowerOfTwo(n)) {
            transformRadix2(re, im, inverse);
        } else {
            transformBluestein(re, im, inverse);
        }
    }

    private static void transformRadix2(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tmp = re[i]; re[i] = re[j]; re[j] = tmp;
                tmp = im[i]; im[i] = im[j]; im[j] = tmp;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = 2.0 * Math.PI / len * (inverse ? 1.0 : -1.0);
            double stepRe = Math.cos(angle), stepIm = Math.sin(angle);
            int half = len >> 1;
            for (int base = 0; base < n; base += len) {
                double curRe = 1.0, curIm = 0.0;
                for (int k = 0; k < half; k++) {
                    int even = base + k, odd = even + half;
                    double uRe = re[even], uIm = im[even];
                    double vRe = re[odd] * curRe - im[odd] * curIm;
                    double vIm = re[odd] * curIm + im[odd] * curRe;
                    re[even] = uRe + vRe; im[even] = uIm + vIm;
                    re[odd] = uRe - vRe; im[odd] = uIm - vIm;
                    double nextRe = curRe * stepRe - curIm * stepIm;
                    double nextIm = curRe * stepIm + curIm * stepRe;
                    curRe = nextRe;
                    curIm = nextIm;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
        }
    }

    /** Bluestein 算法：将任意长度 N 的 DFT 化归为 M >= 2N-1 的 2 的幂循环卷积，无补零扇贝失真。 */
    private static void transformBluestein(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        int m = Integer.highestOneBit(2 * n - 2) << 1;
        double sign = inverse ? 1.0 : -1.0;
        long twoN = 2L * n;
        double[] cosTable = new double[n], sinTable = new double[n];
        for (int i = 0; i < n; i++) {
            long mod = ((long) i * i) % twoN;
            double angle = sign * Math.PI * mod / n;
            cosTable[i] = Math.cos(angle);
            sinTable[i] = Math.sin(angle);
        }
        double[] aRe = new double[m], aIm = new double[m];
        for (int i = 0; i < n; i++) {
            aRe[i] = re[i] * cosTable[i] - im[i] * sinTable[i];
            aIm[i] = re[i] * sinTable[i] + im[i] * cosTable[i];
        }
        double[] bRe = new double[m], bIm = new double[m];
        bRe[0] = cosTable[0];
        bIm[0] = -sinTable[0];
        for (int i = 1; i < n; i++) {
            bRe[i] = bRe[m - i] = cosTable[i];
            bIm[i] = bIm[m - i] = -sinTable[i];
        }
        transformRadix2(aRe, aIm, false);
        transformRadix2(bRe, bIm, false);
        for (int i = 0; i < m; i++) {
            double prodRe = aRe[i] * bRe[i] - aIm[i] * bIm[i];
            double prodIm = aRe[i] * bIm[i] + aIm[i] * bRe[i];
            aRe[i] = prodRe;
            aIm[i] = prodIm;
        }
        transformRadix2(aRe, aIm, true);
        for (int i = 0; i < n; i++) {
            double outRe = aRe[i] * cosTable[i] - aIm[i] * sinTable[i];
            double outIm = aRe[i] * sinTable[i] + aIm[i] * cosTable[i];
            if (inverse) {
                outRe /= n;
                outIm /= n;
            }
            re[i] = outRe;
            im[i] = outIm;
        }
    }

    /** 汉宁窗。 */
    public static double[] hanning(int n) {
        double[] window = new double[n];
        for (int i = 0; i < n; i++) window[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / n);
        return window;
    }

    /** 实信号单边幅值谱：汉宁窗 + 相干增益校正（除以均值窗），支持任意正整数采样点数。 */
    public static Spectrum amplitudeSpectrum(double[] signal, double sampleRate) {
        int n = signal.length;
        if (n <= 0) throw new IllegalArgumentException("谱线长度必须为正整数");
        double[] window = hanning(n);
        double windowCorrection = 0.0;
        for (double w : window) windowCorrection += w;
        windowCorrection /= n;
        if (windowCorrection < 1e-9) windowCorrection = 1e-9;
        double[] re = new double[n], im = new double[n];
        for (int i = 0; i < n; i++) re[i] = signal[i] * window[i];
        transform(re, im);
        int bins = n / 2 + 1;
        double[] frequencies = new double[bins], amplitudes = new double[bins];
        for (int k = 0; k < bins; k++) {
            frequencies[k] = k * sampleRate / n;
            amplitudes[k] = Math.hypot(re[k], im[k]) / (n / 2.0) / windowCorrection;
        }
        return new Spectrum(frequencies, amplitudes);
    }

    /** 频谱：频率轴（Hz）与对应幅值。 */
    public record Spectrum(double[] frequencies, double[] amplitudes) {}
}
