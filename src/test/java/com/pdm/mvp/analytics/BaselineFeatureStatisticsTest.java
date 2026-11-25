package com.pdm.mvp.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import com.pdm.mvp.asset.DeviceRepository;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.mcsa.McsaPlugin;
import com.pdm.mvp.mcsa.McsaProperties;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.telemetry.OperatingCondition;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 按特征编码的基线学习：各特征独立 MAD 清洗与统计，不同模态/特征互不干扰。
 */
class BaselineFeatureStatisticsTest {
    @Test
    void featuresAreCleanedAndScoredIndependently() {
        double[] stable = new double[200];
        for (int i = 0; i < stable.length; i++) stable[i] = 1.0;
        double[] noisy = new double[200];
        for (int i = 0; i < noisy.length; i++) noisy[i] = 10.0 + (i % 7);
        for (int i = 0; i < 5; i++) noisy[i * 7] = 1000.0;

        BaselineService.FeatureStatistics stats =
                BaselineService.featureStatistics(Map.of("f_stable", stable, "f_noisy", noisy));

        assertTrue(stats.cleaned().get("f_noisy").length < 200, "离群尖峰应被清洗");
        assertEquals(200, stats.cleaned().get("f_stable").length);
        assertEquals(0.0, stats.cvByCode().get("f_stable"), 1e-9, "恒定特征 CV 为 0");
        assertTrue(stats.cvByCode().get("f_noisy") > 0.01);
        assertTrue(stats.maxCv() >= stats.cvByCode().get("f_noisy"));
        assertTrue(stats.cvByCode().get("f_stable") < stats.cvByCode().get("f_noisy"),
                "特征统计必须相互独立");
    }

    @Test
    void mcsaFeatureBasedLearningLocksIntoFeatureStateRows() {
        PointEntity point = mock(PointEntity.class);
        when(point.getId()).thenReturn(1L);
        when(point.getBaselineStatus()).thenReturn(BaselineService.LEARNING);

        List<ModalityBaselineSampleEntity> sampleStore = new ArrayList<>();
        ModalityBaselineSampleRepository modalitySamples = mock(ModalityBaselineSampleRepository.class);
        when(modalitySamples.existsByPoint_IdAndModalityTypeAndSampledAt(any(), anyString(), any()))
                .thenAnswer(inv -> sampleStore.stream().anyMatch(s -> s.getSampledAt().equals(inv.getArgument(2))));
        when(modalitySamples.save(any())).thenAnswer(inv -> { sampleStore.add(inv.getArgument(0)); return inv.getArgument(0); });
        when(modalitySamples.findByPoint_IdAndModalityTypeAndSampledAtBetweenOrderBySampledAtAsc(
                any(), anyString(), any(), any()))
                .thenAnswer(inv -> sampleStore.stream()
                        .filter(s -> !s.getSampledAt().isBefore(inv.getArgument(2))
                                && !s.getSampledAt().isAfter(inv.getArgument(3))).toList());

        BaselineFeatureStateRepository featureStates = mock(BaselineFeatureStateRepository.class);
        when(featureStates.findByPoint_IdAndModalityTypeAndFeatureCode(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(featureStates.findByPoint_IdAndModalityType(any(), anyString())).thenReturn(List.of());
        when(featureStates.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BaselineService service = new BaselineService(mock(PointRepository.class), mock(DeviceRepository.class),
                mock(BaselineSampleRepository.class), featureStates, modalitySamples, mock(com.pdm.mvp.analytics.SpectrumBaselineService.class), Duration.ofMinutes(5));
        McsaPlugin plugin = new McsaPlugin(new McsaProperties(false, 20, 100, 15, 54, 40), null);

        Instant start = Instant.parse("2026-11-23T03:00:00Z");
        com.pdm.mvp.modality.BaselineSnapshot snapshot = null;
        // 1 Hz × 320 个样本：需同时满足 ≥200 个样本且覆盖学习窗口（与生产约束一致）
        for (int i = 0; i < 320; i++) {
            TelemetryEnvelope envelope = new TelemetryEnvelope(2, ModalityTypes.MCSA,
                    start.plusSeconds(i).toEpochMilli(), "FACT_01", "MOTOR_M01", "CURRENT", 0,
                    new OperatingCondition(1440.0, 75.0, true),
                    Map.of("mains_freq_hz", 50.0, "slip_pct", 2.5,
                            "sideband_amp_db", -93.0, "carrier_amp_db", -33.0),
                    null, null);
            snapshot = service.observe(point, envelope, plugin);
        }

        // mock 的 getBaselineStatus 恒为 LEARNING，锁定会在后续样本上重复触发，故用 atLeastOnce
        verify(point, org.mockito.Mockito.atLeastOnce()).setBaselineStatus(BaselineService.ACTIVE);
        ArgumentCaptor<BaselineFeatureStateEntity> captor = ArgumentCaptor.forClass(BaselineFeatureStateEntity.class);
        verify(featureStates, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(s -> "sideband_ratio".equals(s.getFeatureCode())),
                "派生特征应落入特征状态行");
        assertTrue(captor.getAllValues().stream().anyMatch(s -> s.getStatus() == BaselineService.ACTIVE),
                "特征状态行应随锁定写入 ACTIVE");
        assertEquals(ModalityTypes.MCSA, snapshot.modalityType());
        assertEquals("MCSA", captor.getAllValues().getFirst().getModalityType(),
                "MCSA 基线样本与状态必须按模态隔离");
    }

    @Test
    void vibrationAndMcsaBaselineSnapshotsDoNotShareFeatureCodes() {
        PointEntity point = mock(PointEntity.class);
        when(point.getFreq1xBase()).thenReturn(1.0);
        when(point.getVBaseRms()).thenReturn(2.0);
        BaselineService service = new BaselineService(mock(PointRepository.class), mock(DeviceRepository.class),
                mock(BaselineSampleRepository.class), mock(BaselineFeatureStateRepository.class),
                mock(ModalityBaselineSampleRepository.class), mock(com.pdm.mvp.analytics.SpectrumBaselineService.class), Duration.ofMinutes(5));
        var snapshot = serviceFeatureSnapshot(service, point);
        assertTrue(snapshot.featureBase().containsKey("freq_1x_amp"));
        assertTrue(!snapshot.featureBase().containsKey("sideband_ratio"),
                "振动快照不得携带其他模态特征");
    }

    private com.pdm.mvp.modality.BaselineSnapshot serviceFeatureSnapshot(BaselineService service, PointEntity point) {
        return service.snapshotFromPoint(point, null);
    }
}
