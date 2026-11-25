package com.pdm.mvp.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pdm.mvp.asset.DeviceRepository;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.mcsa.McsaPlugin;
import com.pdm.mvp.mcsa.McsaProperties;
import com.pdm.mvp.modality.ModalityTypes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 学习期基线预览（B4）：通道未锁定前，无持久化行的插件基线特征用当前学习窗口样本
 * 现算 PROVISIONAL 行（P50/CV/样本数，lockedAt = null）；持久化行永远优先；样本不足 8 的特征省略。
 */
class ChannelBaselineProvisionalTest {
    private final PointRepository points = mock(PointRepository.class);
    private final BaselineFeatureStateRepository featureStates = mock(BaselineFeatureStateRepository.class);
    private final ModalityBaselineSampleRepository modalitySamples = mock(ModalityBaselineSampleRepository.class);
    private final BaselineSampleRepository legacySamples = mock(BaselineSampleRepository.class);
    private final BaselineService service = new BaselineService(points, mock(DeviceRepository.class),
            legacySamples, featureStates, modalitySamples, mock(com.pdm.mvp.analytics.SpectrumBaselineService.class), Duration.ofMinutes(5));

    private PointEntity point(String modalityType, short status) {
        PointEntity point = mock(PointEntity.class);
        when(point.getId()).thenReturn(7L);
        when(point.getModalityType()).thenReturn(modalityType);
        when(point.getBaselineStatus()).thenReturn(status);
        return point;
    }

    @Test
    void learningChannelGetsProvisionalRowsFromCurrentWindow() {
        PointEntity point = point(ModalityTypes.MCSA, BaselineService.LEARNING);
        when(points.findById(7L)).thenReturn(Optional.of(point));
        when(featureStates.findByPoint_IdAndModalityType(7L, ModalityTypes.MCSA)).thenReturn(List.of());
        // 10 个窗口样本：sideband_ratio 全部出现，carrier_amp_db 只出现 4 次（< 8 → 省略）
        List<ModalityBaselineSampleEntity> window = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Double> values = new LinkedHashMap<>();
            values.put("sideband_ratio", 1.0 + (i % 3));
            if (i < 4) values.put("carrier_amp_db", -30.0);
            window.add(new ModalityBaselineSampleEntity(point, ModalityTypes.MCSA,
                    Instant.now().minusSeconds(600L - i), values));
        }
        when(modalitySamples.findByPoint_IdAndModalityTypeAndSampledAtBetweenOrderBySampledAtAsc(
                eq(7L), eq(ModalityTypes.MCSA), any(), any())).thenReturn(window);

        var state = service.getProvisional(7L, new McsaPlugin(new McsaProperties(false, 20, 100, 15, 54, 40), null));

        assertEquals("LEARNING", state.status(), "通道状态保持 LEARNING");
        assertEquals(1, state.featureStates().size(), "样本不足的特征被省略");
        var view = state.featureStates().getFirst();
        assertEquals("sideband_ratio", view.featureCode());
        assertEquals("PROVISIONAL", view.status());
        assertEquals(10, view.sampleCount(), "sampleCount 为该特征在窗口内的原始样本数");
        assertEquals(2.0, view.p50(), 1e-9, "P50 为清洗后中位数（1,2,3 中位数 2）");
        assertNotNull(view.cv());
        assertNull(view.lockedAt(), "预览行没有锁定时间");
    }

    @Test
    void lockedFeatureStatesWinOverProvisionalRecomputation() {
        PointEntity point = point(ModalityTypes.MCSA, BaselineService.ACTIVE);
        when(points.findById(7L)).thenReturn(Optional.of(point));
        BaselineFeatureStateEntity persisted = new BaselineFeatureStateEntity(
                point, ModalityTypes.MCSA, "sideband_ratio");
        persisted.setP50(1.05);
        persisted.setCv(0.02);
        persisted.setSampleCount(200);
        persisted.setStatus(BaselineService.ACTIVE);
        persisted.setLockedAt(Instant.parse("2026-11-23T00:00:00Z"));
        when(featureStates.findByPoint_IdAndModalityType(7L, ModalityTypes.MCSA))
                .thenReturn(List.of(persisted));
        when(modalitySamples.findByPoint_IdAndModalityTypeAndSampledAtBetweenOrderBySampledAtAsc(
                eq(7L), eq(ModalityTypes.MCSA), any(), any())).thenReturn(List.of());

        var state = service.getProvisional(7L, new McsaPlugin(new McsaProperties(false, 20, 100, 15, 54, 40), null));

        assertEquals(1, state.featureStates().size(), "已锁定行照常返回，无重复预览行");
        var view = state.featureStates().getFirst();
        assertEquals("ACTIVE", view.status());
        assertEquals(200, view.sampleCount());
        assertTrue(view.featureCode().equals("sideband_ratio"));
    }

    @Test
    void vibrationLearningChannelGetsProvisionalRowsFromLegacyTable() {
        PointEntity point = point(ModalityTypes.ROTATING_VIB, BaselineService.LEARNING);
        when(points.findById(7L)).thenReturn(Optional.of(point));
        when(featureStates.findByPoint_IdAndModalityType(7L, ModalityTypes.ROTATING_VIB)).thenReturn(List.of());
        List<BaselineSampleEntity> legacyWindow = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            legacyWindow.add(new BaselineSampleEntity(point, Instant.now().minusSeconds(300L - i),
                    1480.0, 0.45 + (i % 3), 2.0 + (i % 2), i < 10 ? 1.2 : null, 0.1, null, 3.0, 55.0));
        }
        when(legacySamples.findByPoint_IdAndSampledAtBetweenOrderBySampledAtAsc(eq(7L), any(), any()))
                .thenReturn(legacyWindow);
        when(modalitySamples.findByPoint_IdAndModalityTypeAndSampledAtBetweenOrderBySampledAtAsc(
                eq(7L), anyString(), any(), any())).thenReturn(List.of());

        var state = service.getProvisional(7L, new com.pdm.mvp.analytics.RotatingVibrationPlugin(
                new com.pdm.mvp.analytics.HealthIndexCalculator(), null,
                new com.pdm.mvp.analytics.DegradationTracker(Duration.ofHours(24))));

        Map<String, BaselineService.BaselineState.FeatureStateView> byCode = new LinkedHashMap<>();
        for (var view : state.featureStates()) byCode.put(view.featureCode(), view);
        assertTrue(byCode.containsKey("v_rms"), "振动学习通道应有 v_rms 预览行");
        assertEquals("PROVISIONAL", byCode.get("v_rms").status());
        assertEquals(12, byCode.get("v_rms").sampleCount());
        assertTrue(byCode.containsKey("freq_1x_amp"));
        assertTrue(!byCode.containsKey("envelope_bpfo_amp"), "窗口内全空的旧表列不产出预览行");
        assertEquals("LEARNING", state.status());
    }
}
