package com.pdm.mvp.waveform;

import com.pdm.mvp.analytics.SpectrumBaselineService;
import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 波形快照入库 → 谱帧分析 → 频谱基线带学习的串联（学习失败不阻断入库）。 */
class WaveformServiceTest {
    private final WaveformSnapshotRepository snapshots = mock(WaveformSnapshotRepository.class);
    private final WaveformStorage storage = mock(WaveformStorage.class);
    private final WaveframeService frames = mock(WaveframeService.class);
    private final AssetLookup assets = mock(AssetLookup.class);
    private final PointRepository points = mock(PointRepository.class);
    private final SpectrumBaselineService spectrumBaseline = mock(SpectrumBaselineService.class);
    private final PointEntity point = mock(PointEntity.class);
    private WaveformService service;

    @BeforeEach
    void setUp() {
        when(point.getId()).thenReturn(7L);
        when(point.getPointCode()).thenReturn("CHANNEL");
        when(assets.requirePointByDeviceAndPoint(any(), any())).thenReturn(point);
        when(snapshots.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.store(anyLong(), anyLong(), any())).thenReturn("ref");
        when(frames.analyze(any())).thenAnswer(inv -> {
            WaveformSnapshotEntity snapshot = inv.getArgument(0);
            return new WaveframeService.Waveframe(snapshot.getSampledAt().toEpochMilli(), 1024.0, "m/s^2",
                    new double[] {0}, new double[] {10, 20}, new double[] {1, 1}, new double[0], new double[0]);
        });
        when(points.findByIdWithDeviceAndBearing(anyLong())).thenReturn(java.util.Optional.of(point));
        service = new WaveformService(snapshots, storage, frames, assets,
                mock(com.pdm.mvp.alert.AlertRepository.class), points, mock(com.pdm.mvp.telemetry.MqttCommandPublisher.class),
                mock(JsonMapper.class), spectrumBaseline, 16384);
    }

    @Test
    void acceptFeedsFrameIntoSpectrumBaselineLearning() {
        RawWaveformPayload payload = new RawWaveformPayload("2.0.0", Instant.now().toEpochMilli(), "DEVICE_01", "CHANNEL",
                WaveformSnapshotEntity.TRIGGER_SCHEDULED, 1024.0, 1, "m/s^2", null, null, List.of(0.0));
        service.accept(payload);
        verify(frames).analyze(any(WaveformSnapshotEntity.class));
        verify(spectrumBaseline).observe(any(PointEntity.class), any(WaveframeService.Waveframe.class));
    }

    @Test
    void learningFailureDoesNotBlockIngest() {
        doThrow(new IllegalStateException("spectrum boom")).when(spectrumBaseline).observe(any(), any());
        RawWaveformPayload payload = new RawWaveformPayload("2.0.0", Instant.now().toEpochMilli(), "DEVICE_01", "CHANNEL",
                WaveformSnapshotEntity.TRIGGER_SCHEDULED, 1024.0, 1, "m/s^2", null, null, List.of(0.0));
        service.accept(payload);
        verify(snapshots).save(any(WaveformSnapshotEntity.class));
    }
}
