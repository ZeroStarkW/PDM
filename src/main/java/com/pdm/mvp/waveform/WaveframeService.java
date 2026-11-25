package com.pdm.mvp.waveform;

import com.pdm.mvp.dsp.ButterworthBandpass;
import com.pdm.mvp.dsp.Fft;
import com.pdm.mvp.dsp.HilbertEnvelope;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 波形帧服务：快照到达后用平台 DSP 内核计算 时域波形 / FFT 频谱 / 希尔伯特包络谱
 * 并按测点线程安全缓存最新一帧（带容量淘汰）与实时工况转速，供诊断工作站与 SSE 实时流共享。
 */
@Service
public class WaveframeService {
    static final int MAX_CACHED_POINTS = 1024;
    private final Map<Long, Waveframe> latest = new ConcurrentHashMap<>();
    private final Map<Long, Double> latestRpm = new ConcurrentHashMap<>();
    private final double bandLowHz;
    private final double bandHighHz;

    public WaveframeService(@Value("${pdm.waveform.band-low:1000}") double bandLowHz,
            @Value("${pdm.waveform.band-high:2500}") double bandHighHz) {
        this.bandLowHz = bandLowHz;
        this.bandHighHz = bandHighHz;
    }

    /** 波形分析帧：时域点阵 + FFT 幅值谱 + 包络谱（包络带不可用时为空数组）。 */
    public record Waveframe(long timestamp, double samplingRate, String unit, double[] waveform,
            double[] fftFreqs, double[] fftAmps, double[] envelopeFreqs, double[] envelopeAmps) {}

    public void cache(Long pointId, Waveframe frame) {
        if (pointId == null || frame == null) return;
        latest.put(pointId, frame);
        if (latest.size() > MAX_CACHED_POINTS) evictOldestFrame();
    }

    public Waveframe frame(Long pointId) {
        return pointId == null ? null : latest.get(pointId);
    }

    /** 记录测点最近上报的有效运行转速（变频工况供实时谱谐波与故障线解算）。 */
    public void recordRpm(Long pointId, Double rpm) {
        if (pointId == null || rpm == null || !Double.isFinite(rpm) || rpm <= 0) return;
        latestRpm.put(pointId, rpm);
        if (latestRpm.size() > MAX_CACHED_POINTS * 2) latestRpm.clear();
    }

    public Double latestRpm(Long pointId) {
        return pointId == null ? null : latestRpm.get(pointId);
    }

    public void clearRpm(Long pointId) {
        if (pointId != null) latestRpm.remove(pointId);
    }

    private void evictOldestFrame() {
        latest.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().timestamp()))
                .map(Map.Entry::getKey)
                .ifPresent(latest::remove);
    }

    /** 从快照波形计算分析帧（支持任意 >=64 点波形：2 的幂走 radix-2，非 2 的幂走 Bluestein 精确 FFT）。 */
    public Waveframe analyze(WaveformSnapshotEntity snapshot) {
        double[] waveform = snapshot.getPreview().stream().mapToDouble(Double::doubleValue).toArray();
        double sampleRate = snapshot.getSamplingRate();
        Fft.Spectrum fft = waveform.length >= 64
                ? Fft.amplitudeSpectrum(waveform, sampleRate) : null;
        double[] envFreqs = new double[0], envAmps = new double[0];
        if (fft != null && sampleRate > 2 * bandHighHz && waveform.length >= 512) {
            try {
                // 波形时间跨度需覆盖滤波器建立段，先带通验证可用性（过窄采样率会抛参数异常）
                new ButterworthBandpass(sampleRate, bandLowHz, bandHighHz);
                Fft.Spectrum envelope = HilbertEnvelope.envelopeSpectrum(waveform, sampleRate, bandLowHz, bandHighHz);
                envFreqs = envelope.frequencies();
                envAmps = envelope.amplitudes();
            } catch (IllegalArgumentException ignored) {
                // 解调带超出奈奎斯特等参数不满足时保留空包络谱
            }
        }
        Waveframe frame = new Waveframe(snapshot.getSampledAt().toEpochMilli(), sampleRate, snapshot.getUnit(),
                waveform,
                fft == null ? new double[0] : fft.frequencies(),
                fft == null ? new double[0] : fft.amplitudes(),
                envFreqs, envAmps);
        cache(snapshot.getPoint().getId(), frame);
        return frame;
    }
}
