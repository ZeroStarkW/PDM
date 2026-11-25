package com.pdm.mvp.dsp;

/**
 * 4 阶巴特沃斯带通：2 阶巴特沃斯高通 × 2 阶巴特沃斯低通各两级级联
 * （RBJ 双二阶配方，Q = 1/√2），用于轴承共振带（默认 1000~2500 Hz）解调预滤波。
 */
public final class ButterworthBandpass {
    private static final double BUTTERWORTH_Q = 1.0 / Math.sqrt(2.0);

    private final Biquad[] stages;

    public ButterworthBandpass(double sampleRate, double lowHz, double highHz) {
        if (!(sampleRate > 2 * highHz) || !(lowHz > 0) || !(highHz > lowHz))
            throw new IllegalArgumentException("带通参数无效：需满足 0 < 低频 < 高频 < 奈奎斯特频率");
        this.stages = new Biquad[]{
                Biquad.highpass(lowHz, sampleRate), Biquad.highpass(lowHz, sampleRate),
                Biquad.lowpass(highHz, sampleRate), Biquad.lowpass(highHz, sampleRate)};
    }

    /** 零相位风格处理：正向级联一次（4 阶巴特沃斯幅频特性）。 */
    public double[] filter(double[] signal) {
        double[] output = signal.clone();
        for (Biquad stage : stages) stage.reset();
        for (Biquad stage : stages) {
            for (int i = 0; i < output.length; i++) output[i] = stage.process(output[i]);
        }
        return output;
    }

    /** RBJ 音频配方双二阶节（Transposed Direct Form II）。 */
    private static final class Biquad {
        private final double b0, b1, b2, a1, a2;
        private double z1, z2;

        private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0;
            this.a1 = a1 / a0; this.a2 = a2 / a0;
        }

        static Biquad lowpass(double cutoffHz, double sampleRate) {
            double omega = 2.0 * Math.PI * cutoffHz / sampleRate;
            double cos = Math.cos(omega), alpha = Math.sin(omega) / (2.0 * BUTTERWORTH_Q);
            return new Biquad((1 - cos) / 2, 1 - cos, (1 - cos) / 2,
                    1 + alpha, -2 * cos, 1 - alpha);
        }

        static Biquad highpass(double cutoffHz, double sampleRate) {
            double omega = 2.0 * Math.PI * cutoffHz / sampleRate;
            double cos = Math.cos(omega), alpha = Math.sin(omega) / (2.0 * BUTTERWORTH_Q);
            return new Biquad((1 + cos) / 2, -(1 + cos), (1 + cos) / 2,
                    1 + alpha, -2 * cos, 1 - alpha);
        }

        double process(double input) {
            double output = b0 * input + z1;
            z1 = b1 * input - a1 * output + z2;
            z2 = b2 * input - a2 * output;
            return output;
        }

        void reset() { z1 = 0; z2 = 0; }
    }
}
