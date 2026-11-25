package com.pdm.mvp.waveform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.dsp.BandRms;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 波形帧谱计算：FFT 主峰、包络谱 140Hz 检出与低采样率降级。 */
class WaveframeServiceTest {
    private final WaveframeService service = new WaveframeService(1000.0, 2500.0);

    @Test
    void analyzesWaveformIntoFftAndEnvelopeSpectra() {
        int n = 8192;
        double fs = 8192.0;
        List<Double> preview = new ArrayList<>(n);
        Random random = new Random(11L);
        // 25Hz 转频 + 2kHz 载波 140Hz 调制冲击（外圈点蚀模型）
        double shockInterval = fs / 140.0;
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            double value = 0.40 * Math.sin(2 * Math.PI * 25.0 * t)
                    + 0.30 * Math.sin(2 * Math.PI * 2000.0 * t)
                    + 0.02 * random.nextGaussian();
            preview.add(value);
        }
        for (int k = 0; shockInterval * k < n - 64; k++) {
            int start = (int) Math.round(shockInterval * k);
            for (int j = 0; j < 64 && start + j < n; j++) {
                double tau = j / fs;
                preview.set(start + j, preview.get(start + j) + 0.25 * Math.exp(-160.0 * tau)
                        * Math.sin(2 * Math.PI * 2000.0 * tau));
            }
        }
        WaveformSnapshotEntity snapshot = new WaveformSnapshotEntity(point(1L),
                WaveformSnapshotEntity.TRIGGER_SCHEDULED, Instant.ofEpochMilli(1732262400000L),
                fs, n, "m/s^2", preview, 88.0);

        WaveframeService.Waveframe frame = service.analyze(snapshot);

        assertEquals(n, frame.waveform().length);
        assertTrue(service.frame(1L) != null, "帧应按测点缓存");
        assertTrue(frame.fftFreqs().length > 1000);
        // FFT 主峰应落在 25Hz 频带
        double near1x = BandRms.of(frame.fftFreqs(), frame.fftAmps(), 25.0);
        assertTrue(near1x > 0.35, "1X 频带能量应接近 0.40: " + near1x);
        // 包络谱应检出 140Hz 特征
        assertTrue(frame.envelopeFreqs().length > 0);
        double envBpfo = BandRms.of(frame.envelopeFreqs(), frame.envelopeAmps(), 140.0);
        List<Double> floorValues = new ArrayList<>();
        for (int i = 0; i < frame.envelopeFreqs().length; i++) {
            double freq = frame.envelopeFreqs()[i];
            if (freq >= 30 && freq <= 500 && Math.abs(freq - 140) > 15) floorValues.add(frame.envelopeAmps()[i]);
        }
        floorValues.sort(Double::compare);
        double floor = floorValues.get(floorValues.size() / 2);
        assertTrue(envBpfo / Math.max(1e-9, floor) > 5.0, "包络谱 140Hz SNR 应 >5: " + envBpfo / floor);
    }

    @Test
    void skipsEnvelopeWhenSampleRateTooLow() {
        int n = 4096;
        List<Double> preview = new ArrayList<>(n);
        for (int i = 0; i < n; i++) preview.add(Math.sin(2 * Math.PI * 25.0 * i / 2048.0));
        WaveformSnapshotEntity snapshot = new WaveformSnapshotEntity(point(2L),
                WaveformSnapshotEntity.TRIGGER_ON_DEMAND, Instant.ofEpochMilli(1732262400000L),
                2048.0, n, "m/s^2", preview, 90.0);

        WaveframeService.Waveframe frame = service.analyze(snapshot);

        assertEquals(0, frame.envelopeFreqs().length, "低采样率不得构造越界解调带");
        assertTrue(frame.fftAmps().length > 0);
    }

    private PointEntity point(long id) {
        PointEntity point = mock(PointEntity.class);
        when(point.getId()).thenReturn(id);
        return point;
    }
}
