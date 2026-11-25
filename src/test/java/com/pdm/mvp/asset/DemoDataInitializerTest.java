package com.pdm.mvp.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pdm.mvp.analytics.BaselineFeatureStateEntity;
import com.pdm.mvp.analytics.BaselineFeatureStateRepository;
import com.pdm.mvp.bearing.BearingLibraryRepository;
import com.pdm.mvp.snapshot.ChannelSnapshotEntity;
import com.pdm.mvp.snapshot.ChannelSnapshotRepository;
import com.pdm.mvp.waveform.WaveformSnapshotEntity;
import com.pdm.mvp.waveform.WaveformSnapshotRepository;
import com.pdm.mvp.waveform.WaveformStorage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 多模态演示种子（B11）：MCSA 电机/断路器/调节阀设备与测点、事件/过程快照、ACTIVE 基线特征行；
 * 全部“存在即跳过”，重复执行不产生重复数据；不写 InfluxDB（回环图表需脚本或真实遥测）。
 */
class DemoDataInitializerTest {
    private final FactoryRepository factories = mock(FactoryRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final PointRepository points = mock(PointRepository.class);
    private final DeviceTypeService deviceTypes = mock(DeviceTypeService.class);
    private final BearingLibraryRepository bearings = mock(BearingLibraryRepository.class);
    private final WaveformSnapshotRepository waveforms = mock(WaveformSnapshotRepository.class);
    private final WaveformStorage storage = mock(WaveformStorage.class);
    private final ChannelSnapshotRepository channelSnapshots = mock(ChannelSnapshotRepository.class);
    private final BaselineFeatureStateRepository featureStates = mock(BaselineFeatureStateRepository.class);

    private final List<DeviceEntity> deviceStore = new ArrayList<>();
    private final List<PointEntity> pointStore = new ArrayList<>();
    private final List<ChannelSnapshotEntity> snapshotStore = new ArrayList<>();
    private final List<WaveformSnapshotEntity> waveformStore = new ArrayList<>();
    private final List<BaselineFeatureStateEntity> featureStateStore = new ArrayList<>();

    private final DemoDataInitializer initializer = new DemoDataInitializer();

    @BeforeEach
    void setUp() {
        EntityManager entityManager = mock(EntityManager.class);
        Query lockQuery = mock(Query.class);
        when(lockQuery.setParameter(anyInt(), any())).thenReturn(lockQuery);
        when(lockQuery.getSingleResult()).thenReturn("locked");
        when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
        ReflectionTestUtils.setField(initializer, "entityManager", entityManager);

        when(factories.findByFactoryCode("FACT_01")).thenReturn(Optional.of(new FactoryEntity("FACT_01", "示范生产厂")));
        when(deviceTypes.general()).thenReturn(new DeviceTypeEntity("GENERAL", "通用设备"));
        when(deviceTypes.getOrCreate("ELECTRIC_MOTOR", "电动机"))
                .thenReturn(new DeviceTypeEntity("ELECTRIC_MOTOR", "电动机"));
        when(deviceTypes.getOrCreate("CIRCUIT_BREAKER", "高压断路器"))
                .thenReturn(new DeviceTypeEntity("CIRCUIT_BREAKER", "高压断路器"));
        when(deviceTypes.getOrCreate("CONTROL_VALVE", "调节阀"))
                .thenReturn(new DeviceTypeEntity("CONTROL_VALVE", "调节阀"));
        when(devices.findByFactory_FactoryCodeAndDeviceCode(anyString(), anyString())).thenAnswer(inv ->
                deviceStore.stream().filter(d -> d.getDeviceCode().equals(inv.getArgument(1))).findFirst());
        when(devices.save(any())).thenAnswer(inv -> {
            deviceStore.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(points.findByDevice_Factory_FactoryCodeAndDevice_DeviceCodeAndPointCode(anyString(), anyString(),
                anyString())).thenAnswer(inv -> pointStore.stream()
                .filter(p -> p.getPointCode().equals(inv.getArgument(2))
                        && p.getDevice().getDeviceCode().equals(inv.getArgument(1))).findFirst());
        when(points.save(any())).thenAnswer(inv -> {
            PointEntity point = inv.getArgument(0);
            // 真实实体没有 ID 生成器：测试内模拟持久化时发号，供按 point_id 的查找桩使用
            if (point.getId() == null) {
                org.springframework.test.util.ReflectionTestUtils.setField(point, "id",
                        (long) (pointStore.size() + 1));
            }
            pointStore.add(point);
            return point;
        });
        when(bearings.findByModelNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(waveforms.findTopByPoint_IdOrderBySampledAtDesc(any())).thenAnswer(inv ->
                waveformStore.stream().filter(w -> w.getPoint().getId().equals(inv.getArgument(0))).findFirst());
        when(waveforms.save(any())).thenAnswer(inv -> {
            waveformStore.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(channelSnapshots.findTopByPoint_IdOrderBySampledAtDesc(any())).thenAnswer(inv ->
                snapshotStore.stream().filter(s -> s.getPoint().getId().equals(inv.getArgument(0))).findFirst());
        when(channelSnapshots.save(any())).thenAnswer(inv -> {
            snapshotStore.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(featureStates.findByPoint_IdAndModalityTypeAndFeatureCode(any(), anyString(), anyString()))
                .thenAnswer(inv -> featureStateStore.stream()
                        .filter(s -> s.getPoint().getId().equals(inv.getArgument(0))
                                && s.getModalityType().equals(inv.getArgument(1))
                                && s.getFeatureCode().equals(inv.getArgument(2))).findFirst());
        when(featureStates.save(any())).thenAnswer(inv -> {
            featureStateStore.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(storage.store(anyLong(), anyLong(), any())).thenReturn("file:/seed/demo.json");
    }

    @Test
    void seedsMultimodalAssetsSnapshotsAndActiveBaselines() {
        initializer.seed(factories, devices, points, deviceTypes, bearings, waveforms, storage,
                channelSnapshots, featureStates);

        // 设备：泵 + MCSA 电机 + 断路器 + 调节阀
        assertEquals(4, deviceStore.size());
        assertTrue(deviceStore.stream().anyMatch(d -> "BREAKER_10KV_02".equals(d.getDeviceCode())
                && "高压断路器 10kV".equals(d.getDeviceName())));
        assertTrue(deviceStore.stream().anyMatch(d -> "FEED_VALVE_FCV101".equals(d.getDeviceCode())));
        assertTrue(deviceStore.stream().anyMatch(d -> "MOTOR_M01".equals(d.getDeviceCode())
                && "异步电机 M01".equals(d.getDeviceName())));
        assertEquals("ELECTRIC_MOTOR", deviceByCode("MOTOR_M01").getDeviceType().getTypeCode());
        assertEquals("CIRCUIT_BREAKER", deviceByCode("BREAKER_10KV_02").getDeviceType().getTypeCode());
        assertEquals("CONTROL_VALVE", deviceByCode("FEED_VALVE_FCV101").getDeviceType().getTypeCode());

        // 测点：模态/采样率/单位
        PointEntity tripUnit = pointByCode("CH_TRIP_UNIT");
        assertNotNull(tripUnit);
        assertEquals(com.pdm.mvp.modality.ModalityTypes.SWITCHGEAR_EVENT, tripUnit.getModalityType());
        assertEquals(10000, tripUnit.getSamplingRate());
        assertEquals("A", tripUnit.getEngineeringUnit());
        PointEntity valveLoop = pointByCode("CH_VALVE_LOOP");
        assertEquals(com.pdm.mvp.modality.ModalityTypes.CONTROL_VALVE_PROCESS, valveLoop.getModalityType());
        assertEquals(1, valveLoop.getSamplingRate());
        assertEquals("%", valveLoop.getEngineeringUnit());
        PointEntity motorCurrent = pointByCode("CH_STATOR_CURRENT");
        assertNotNull(motorCurrent);
        assertEquals("MCSA", motorCurrent.getModalityType());
        assertEquals(1, motorCurrent.getSamplingRate());
        assertEquals("dB", motorCurrent.getEngineeringUnit());

        // 快照：断路器 §2.2 十点曲线 + 行程标注；调节阀 cv/fb 回环 + 回环标注
        assertEquals(2, snapshotStore.size());
        ChannelSnapshotEntity breaker = snapshotByPoint(tripUnit);
        assertEquals(3, breaker.getCurves().size());
        assertEquals(10, breaker.getCurves().get("time_axis_ms").size());
        assertTrue(breaker.getAnnotations().containsKey("tripStartMs"));
        assertTrue(breaker.getAnnotations().containsKey("peakCurrentA"));
        ChannelSnapshotEntity valve = snapshotByPoint(valveLoop);
        assertTrue(valve.getAnnotations().containsKey("deadbandEstimatePct"));
        assertEquals(valve.getCurves().get("fb_pct"), valve.getPreview(), "调节阀 preview_curve = fb 序列");

        // 基线：ACTIVE 特征行（P50 与规格一致）+ 测点 ACTIVE
        var states = new java.util.ArrayList<BaselineFeatureStateEntity>();
        org.mockito.ArgumentCaptor<BaselineFeatureStateEntity> captor =
                org.mockito.ArgumentCaptor.forClass(BaselineFeatureStateEntity.class);
        org.mockito.Mockito.verify(featureStates, times(10)).save(captor.capture());
        states.addAll(captor.getAllValues());
        assertTrue(states.stream().anyMatch(s -> "trip_duration_ms".equals(s.getFeatureCode())
                && s.getP50() == 40.0 && s.getStatus() == com.pdm.mvp.analytics.BaselineService.ACTIVE));
        assertTrue(states.stream().anyMatch(s -> "peak_coil_current_a".equals(s.getFeatureCode()) && s.getP50() == 5.0));
        assertTrue(states.stream().anyMatch(s -> "closing_velocity_mps".equals(s.getFeatureCode()) && s.getP50() == 2.15));
        assertTrue(states.stream().anyMatch(s -> "contact_wear_ratio".equals(s.getFeatureCode()) && s.getP50() == 0.10));
        assertTrue(states.stream().anyMatch(s -> "hysteresis_deadband_pct".equals(s.getFeatureCode()) && s.getP50() == 1.0));
        assertTrue(states.stream().anyMatch(s -> "travel_time_lag_ms".equals(s.getFeatureCode()) && s.getP50() == 400.0));
        assertTrue(states.stream().anyMatch(s -> "friction_force_kn".equals(s.getFeatureCode()) && s.getP50() == 1.2));
        assertTrue(states.stream().anyMatch(s -> "ultrasonic_leak_rms".equals(s.getFeatureCode()) && s.getP50() == 0.04));
        assertTrue(states.stream().anyMatch(s -> "sideband_ratio".equals(s.getFeatureCode())
                && s.getP50() == 0.001 && s.getModalityType().equals("MCSA")));
        assertTrue(states.stream().anyMatch(s -> "carrier_amp_db".equals(s.getFeatureCode())
                && s.getP50() == -33.0 && s.getModalityType().equals("MCSA")));
        assertEquals(com.pdm.mvp.analytics.BaselineService.ACTIVE, tripUnit.getBaselineStatus());
        assertNotNull(tripUnit.getBaselineLockedAt());
        assertEquals(com.pdm.mvp.analytics.BaselineService.ACTIVE, valveLoop.getBaselineStatus());
        assertEquals(com.pdm.mvp.analytics.BaselineService.ACTIVE, motorCurrent.getBaselineStatus());
    }

    @Test
    void repeatedSeedDoesNotDuplicateSnapshotsOrBaselines() {
        initializer.seed(factories, devices, points, deviceTypes, bearings, waveforms, storage,
                channelSnapshots, featureStates);
        initializer.seed(factories, devices, points, deviceTypes, bearings, waveforms, storage,
                channelSnapshots, featureStates);

        // 第二轮全部命中“存在即跳过”：快照仍只有 2 条、基线特征行仍只有 8 行、测点仍只有 3 个（去重计）
        verify(channelSnapshots, times(2)).save(any());
        org.mockito.Mockito.verify(featureStates, times(10)).save(any());
        assertEquals(4, deviceStore.size());
        assertEquals(4, pointStore.stream().distinct().count(),
                "泵 + MCSA 电机 + 断路器 + 调节阀各一个测点（save 可重入但实体去重后仅 4 个）");
    }

    private PointEntity pointByCode(String code) {
        return pointStore.stream().filter(p -> code.equals(p.getPointCode())).findFirst().orElse(null);
    }

    private DeviceEntity deviceByCode(String code) {
        return deviceStore.stream().filter(device -> code.equals(device.getDeviceCode())).findFirst().orElse(null);
    }

    private ChannelSnapshotEntity snapshotByPoint(PointEntity point) {
        return snapshotStore.stream().filter(s -> s.getPoint() == point).findFirst().orElse(null);
    }
}
