package com.pdm.mvp.analytics;

import com.pdm.mvp.asset.DeviceRepository;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.waveform.WaveframeService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 频谱基线带：窗口样本累积 → 达下限冻结（逐带 MAD/P50 与参考带上下限）、冻结后不再吸收、
 * 重复时间戳去重、非振动测点不学习、基线重置一并清空（同一条重置路径）。
 */
class SpectrumBaselineServiceTest {
    private final SpectrumBandSampleRepository sampleRepo = mock(SpectrumBandSampleRepository.class);
    private final SpectrumBandStateRepository stateRepo = mock(SpectrumBandStateRepository.class);
    private final PointEntity point = mock(PointEntity.class);
    private final List<SpectrumBandSampleEntity> sampleStore = new ArrayList<>();
    private final List<SpectrumBandStateEntity> stateStore = new ArrayList<>();

    private SpectrumBaselineService service;

    @BeforeEach
    void setUp() {
        when(point.getId()).thenReturn(7L);
        when(point.getModalityType()).thenReturn("ROTATING_VIB");
        // 内存版样本仓库：窗口读写与清理
        when(sampleRepo.save(any())).thenAnswer(inv -> { sampleStore.add(inv.getArgument(0)); return inv.getArgument(0); });
        when(sampleRepo.existsByPoint_IdAndKindAndSampledAt(anyLong(), anyString(), any())).thenAnswer(inv -> sampleStore.stream()
                .anyMatch(s -> s.getKind().equals(inv.getArgument(1)) && s.getSampledAt().equals(inv.getArgument(2))));
        doAnswer(inv -> sampleStore.removeIf(s -> s.getKind().equals(inv.getArgument(1))
                && s.getSampledAt().isBefore(inv.getArgument(2)))).when(sampleRepo)
                .deleteByPoint_IdAndKindAndSampledAtBefore(anyLong(), anyString(), any());
        when(sampleRepo.countByPoint_IdAndKind(anyLong(), anyString())).thenAnswer(inv -> sampleStore.stream()
                .filter(s -> s.getKind().equals(inv.getArgument(1))).count());
        when(sampleRepo.findByPoint_IdAndKindAndSampledAtBetweenOrderBySampledAtAsc(anyLong(), anyString(), any(), any()))
                .thenAnswer(inv -> sampleStore.stream()
                        .filter(s -> s.getKind().equals(inv.getArgument(1))
                                && !s.getSampledAt().isBefore(inv.getArgument(2)) && !s.getSampledAt().isAfter(inv.getArgument(3)))
                        .toList());
        // 冻结状态仓库：有行 = 已冻结
        when(stateRepo.save(any())).thenAnswer(inv -> { stateStore.add(inv.getArgument(0)); return inv.getArgument(0); });
        when(stateRepo.existsByPoint_IdAndKind(anyLong(), anyString())).thenAnswer(inv -> stateStore.stream()
                .anyMatch(s -> s.getKind().equals(inv.getArgument(1))));
        when(stateRepo.findByPoint_IdAndKindOrderByBandIndex(anyLong(), anyString())).thenAnswer(inv -> stateStore.stream()
                .filter(s -> s.getKind().equals(inv.getArgument(1)))
                .sorted(Comparator.comparingInt(SpectrumBandStateEntity::getBandIndex)).toList());
        service = new SpectrumBaselineService(sampleRepo, stateRepo, 3, Duration.ofDays(30));
    }

    /** 帧时间戳取当前时刻附近（滚动窗口按 now-窗口 清理，纪元附近的时间戳会被窗口过滤掉）。 */
    private static long at(long offsetMs) {
        return Instant.now().toEpochMilli() + offsetMs;
    }

    /** 单音 100Hz、幅值随帧递增的 FFT 帧（包络谱留空，仅学习 FFT 谱型）。 */
    private WaveframeService.Waveframe frame(long timestamp, double amp) {
        return new WaveframeService.Waveframe(timestamp, 1024.0, "m/s^2", new double[] {0}, new double[] {100.0},
                new double[] {amp}, new double[0], new double[0]);
    }

    @Test
    void accumulatesUntilMinimumThenFreezesReferenceBand() {
        service.observe(point, frame(at(1_000L), 0.5));
        service.observe(point, frame(at(2_000L), 1.0));
        assertTrue(stateStore.isEmpty(), "未达样本下限不冻结");
        service.observe(point, frame(at(3_000L), 1.5));
        assertEquals(1, stateStore.size(), "达到下限（3 帧）即按带冻结");
        SpectrumBandStateEntity state = stateStore.getFirst();
        // 样本 [0.5, 1, 1.5]：MAD 清洗后 P50=1、MAD=0.5，参考带 = 1 ± 3×1.4826×0.5
        assertEquals(1.0, state.getP50(), 1e-9);
        assertEquals(0.5, state.getMad(), 1e-9);
        assertEquals(3, state.getSampleCount());
        assertTrue(state.getBandLowHz() < 100 && 100 <= state.getBandHighHz());
        // 视图口径：status=ACTIVE、upper = P50 + 3σ·MAD、lower 不为负
        var view = service.get(7L);
        var fft = view.kinds().stream().filter(k -> "FFT".equals(k.kind())).findFirst().orElseThrow();
        assertEquals("ACTIVE", fft.status());
        assertEquals(3, fft.sampleCount());
        var band = fft.bands().getFirst(); // 本用例只有 100Hz 一根音条：仅该带冻结
        assertEquals(1.0 + SpectrumBaselineService.MAD_SIGMA * 0.5, band.upper(), 1e-9);
        assertEquals(0.0, band.lower(), 1e-9);
        // 包络谱无帧：保持学习空带
        var envelope = view.kinds().stream().filter(k -> "ENVELOPE".equals(k.kind())).findFirst().orElseThrow();
        assertEquals("LEARNING", envelope.status());
        assertTrue(envelope.bands().isEmpty());
    }

    @Test
    void frozenKindStopsAbsorbingNewFrames() {
        service.observe(point, frame(at(1_000L), 1));
        service.observe(point, frame(at(2_000L), 1));
        service.observe(point, frame(at(3_000L), 1));
        assertEquals(3, sampleStore.size());
        service.observe(point, frame(at(4_000L), 1));
        service.observe(point, frame(at(5_000L), 1));
        assertEquals(3, sampleStore.size(), "冻结后不再吸收新帧");
    }

    @Test
    void duplicateTimestampIsIgnored() {
        long timestamp = at(1_000L); // 同一时间戳：两次调用 at() 会因 now 漂移产生不同值，须先固定
        service.observe(point, frame(timestamp, 1));
        service.observe(point, frame(timestamp, 2));
        assertEquals(1, sampleStore.size(), "同一时间戳只计一帧");
    }

    @Test
    void nonVibrationPointIsNotLearned() {
        when(point.getModalityType()).thenReturn("MCSA");
        service.observe(point, frame(at(1_000L), 1));
        assertEquals(0, sampleStore.size());
    }

    @Test
    void frameWithoutSpectrumIsSkipped() {
        service.observe(point, new WaveframeService.Waveframe(at(1_000L), 1024.0, "m/s^2", new double[] {0},
                new double[0], new double[0], new double[0], new double[0]));
        assertEquals(0, sampleStore.size());
    }

    @Test
    void rollingWindowDropsSamplesOlderThanWindow() {
        // 大样本下限的服务：本用例只验证窗口清理，不让累积触发冻结
        var laxService = new SpectrumBaselineService(sampleRepo, stateRepo, 1000, Duration.ofHours(1));
        laxService.observe(point, frame(at(0L), 1));
        laxService.observe(point, frame(at(Duration.ofHours(2).toMillis()), 1));
        assertEquals(1, sampleStore.size(), "窗口外样本被滚动清理");
    }

    @Test
    void confirmResetClearsSamplesAndFrozenBands() {
        service.observe(point, frame(at(1_000L), 1));
        service.observe(point, frame(at(2_000L), 1));
        service.observe(point, frame(at(3_000L), 1));
        assertEquals(3, sampleStore.size());
        PointEntity pending = mock(PointEntity.class);
        when(pending.getId()).thenReturn(7L);
        when(pending.getModalityType()).thenReturn("ROTATING_VIB");
        when(pending.getBaselineStatus()).thenReturn(BaselineService.PENDING_RESET);
        var points = mock(PointRepository.class);
        when(points.findByIdForUpdate(7L)).thenReturn(Optional.of(pending));
        BaselineService baseline = new BaselineService(points, mock(DeviceRepository.class),
                mock(BaselineSampleRepository.class), mock(BaselineFeatureStateRepository.class),
                mock(ModalityBaselineSampleRepository.class), service, Duration.ofMinutes(5));
        baseline.confirmReset(7L);
        verify(sampleRepo).deleteByPoint_Id(7L);
        verify(stateRepo).deleteByPoint_Id(7L);
    }
}
