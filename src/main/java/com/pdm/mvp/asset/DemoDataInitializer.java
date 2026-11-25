package com.pdm.mvp.asset;

import com.pdm.mvp.analytics.BaselineFeatureStateEntity;
import com.pdm.mvp.analytics.BaselineFeatureStateRepository;
import com.pdm.mvp.bearing.BearingLibraryEntity;
import com.pdm.mvp.bearing.BearingLibraryRepository;
import com.pdm.mvp.snapshot.ChannelSnapshotEntity;
import com.pdm.mvp.snapshot.ChannelSnapshotRepository;
import com.pdm.mvp.snapshot.StrokeAnnotationCalculator;
import com.pdm.mvp.snapshot.ValveLoopAnalyzer;
import com.pdm.mvp.waveform.WaveformSnapshotEntity;
import com.pdm.mvp.waveform.WaveformSnapshotRepository;
import com.pdm.mvp.waveform.WaveformStorage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 演示种子（pdm.demo-data.enabled=true 时启用）：示范厂 + 泵、电机、10kV 断路器与 feed 调节阀四台设备、
 * 各测点一条开箱即用的快照，以及已 ACTIVE 的基线特征行（工作台免等 200 样本学习期）。
 * 幂等：advisory-lock 串行化，全部按“存在即跳过”补齐，不重写既有数据。
 * 注意：不回填 InfluxDB 时序——调节阀回环图表需要真实遥测（scripts/Publish-MultimodalSamples.ps1）
 * 或已有历史数据才能出曲线。
 */
@Configuration
@ConditionalOnProperty(prefix = "pdm.demo-data", name = "enabled", havingValue = "true")
public class DemoDataInitializer {
    private static final String FACTORY_CODE = "FACT_01";
    private static final String DEVICE_CODE = "PUMP_P102A";
    private static final String POINT_CODE = "DE_RADIAL";
    private static final String BEARER_DEVICE_CODE = "BREAKER_10KV_02";
    private static final String BEARER_POINT_CODE = "CH_TRIP_UNIT";
    private static final String VALVE_DEVICE_CODE = "FEED_VALVE_FCV101";
    private static final String VALVE_POINT_CODE = "CH_VALVE_LOOP";
    private static final String MOTOR_DEVICE_CODE = "MOTOR_M01";
    private static final String MOTOR_POINT_CODE = "CH_STATOR_CURRENT";
    private static final String MOTOR_TYPE_CODE = "ELECTRIC_MOTOR";
    private static final String BREAKER_TYPE_CODE = "CIRCUIT_BREAKER";
    private static final String VALVE_TYPE_CODE = "CONTROL_VALVE";
    private static final String BEARING_MODEL = "SKF 6208";
    private static final long DEMO_ASSET_LOCK_KEY = 0x50444D5F53454544L; // PDM_SEED

    @PersistenceContext
    private EntityManager entityManager;

    @Bean
    @Order(100)
    ApplicationRunner seedDemoAssets(FactoryRepository factories, DeviceRepository devices, PointRepository points,
            DeviceTypeService deviceTypes, BearingLibraryRepository bearings,
            WaveformSnapshotRepository waveforms, WaveformStorage waveformStorage,
            ChannelSnapshotRepository channelSnapshots, BaselineFeatureStateRepository featureStates,
            PlatformTransactionManager transactionManager) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return args -> transaction.executeWithoutResult(status ->
                seed(factories, devices, points, deviceTypes, bearings, waveforms, waveformStorage,
                        channelSnapshots, featureStates));
    }

    void seed(FactoryRepository factories, DeviceRepository devices, PointRepository points,
            DeviceTypeService deviceTypes, BearingLibraryRepository bearings,
            WaveformSnapshotRepository waveforms, WaveformStorage waveformStorage,
            ChannelSnapshotRepository channelSnapshots, BaselineFeatureStateRepository featureStates) {
        // Serialize demo initialization across instances until the seed transaction completes.
        entityManager.createNativeQuery("select cast(pg_advisory_xact_lock(?1) as text)")
                .setParameter(1, DEMO_ASSET_LOCK_KEY)
                .getSingleResult();

        FactoryEntity factory = factories.findByFactoryCode(FACTORY_CODE)
                .orElseGet(() -> factories.save(new FactoryEntity(FACTORY_CODE, "示范生产厂")));
        DeviceEntity pump = devices.findByFactory_FactoryCodeAndDeviceCode(FACTORY_CODE, DEVICE_CODE)
                .orElseGet(() -> devices.save(new DeviceEntity(factory, DEVICE_CODE, "循环水泵 A",
                        new BigDecimal("1500.00"), new BigDecimal("75.00"), deviceTypes.general())));
        PointEntity point = points.findByDevice_Factory_FactoryCodeAndDevice_DeviceCodeAndPointCode(
                FACTORY_CODE, DEVICE_CODE, POINT_CODE).orElse(null);
        if (point == null) point = points.save(new PointEntity(pump, POINT_CODE, "驱动端径向", null));
        // PHM 升级：绑定标准轴承型号（专家工作站故障线与包络基线依赖几何库）
        if (point.getBearingModel() == null) {
            BearingLibraryEntity bearing = bearings.findByModelNameIgnoreCase(BEARING_MODEL).orElse(null);
            if (bearing != null) {
                point.setBearingModel(bearing);
                points.save(point);
            }
        }
        // PHM 升级：种子一条波形快照，保证诊断工作站开箱即有谱帧
        if (waveforms.findTopByPoint_IdOrderBySampledAtDesc(point.getId()).isEmpty()) {
            long timestamp = System.currentTimeMillis();
            List<Double> preview = demoWaveform();
            WaveformSnapshotEntity snapshot = new WaveformSnapshotEntity(point,
                    WaveformSnapshotEntity.TRIGGER_SCHEDULED, Instant.ofEpochMilli(timestamp),
                    8192.0, preview.size(), "m/s^2", preview, point.getLastHealthIndex());
            snapshot.setStorageRef(waveformStorage.store(point.getId(), timestamp, preview));
            waveforms.save(snapshot);
        }

        // 多模态扩展：10kV 高压断路器（事件型）与 feed 调节阀（过程型）
        DeviceTypeEntity breakerType = deviceTypes.getOrCreate(BREAKER_TYPE_CODE, "高压断路器");
        DeviceTypeEntity valveType = deviceTypes.getOrCreate(VALVE_TYPE_CODE, "调节阀");
        DeviceTypeEntity motorType = deviceTypes.getOrCreate(MOTOR_TYPE_CODE, "电动机");
        DeviceEntity breaker = devices.findByFactory_FactoryCodeAndDeviceCode(FACTORY_CODE, BEARER_DEVICE_CODE)
                .orElseGet(() -> devices.save(new DeviceEntity(factory, BEARER_DEVICE_CODE, "高压断路器 10kV",
                        BigDecimal.ZERO, null, breakerType)));
        assignDeviceType(breaker, breakerType);
        PointEntity tripUnit = seedPoint(points, breaker, BEARER_POINT_CODE, "分合闸单元",
                com.pdm.mvp.modality.ModalityTypes.SWITCHGEAR_EVENT, 10000, "A");
        DeviceEntity valve = devices.findByFactory_FactoryCodeAndDeviceCode(FACTORY_CODE, VALVE_DEVICE_CODE)
                .orElseGet(() -> devices.save(new DeviceEntity(factory, VALVE_DEVICE_CODE, "feed 调节阀",
                        BigDecimal.ZERO, null, valveType)));
        assignDeviceType(valve, valveType);
        PointEntity valveLoop = seedPoint(points, valve, VALVE_POINT_CODE, "阀位反馈回路",
                com.pdm.mvp.modality.ModalityTypes.CONTROL_VALVE_PROCESS, 1, "%");
        DeviceEntity motor = devices.findByFactory_FactoryCodeAndDeviceCode(FACTORY_CODE, MOTOR_DEVICE_CODE)
                .orElseGet(() -> devices.save(new DeviceEntity(factory, MOTOR_DEVICE_CODE, "异步电机 M01",
                        new BigDecimal("1500.00"), new BigDecimal("75.00"), motorType)));
        assignDeviceType(motor, motorType);
        PointEntity motorCurrent = seedPoint(points, motor, MOTOR_POINT_CODE, "定子电流频谱",
                com.pdm.mvp.modality.ModalityTypes.MCSA, 1, "dB");

        // 快照（含平台侧标注）+ ACTIVE 基线特征行：两个多模态工作台开箱即渲染
        seedBreakerSnapshot(channelSnapshots, waveformStorage, tripUnit);
        seedValveSnapshot(channelSnapshots, waveformStorage, valveLoop);
        activateBaseline(points, featureStates, tripUnit, Map.of(
                "trip_duration_ms", 40.0, "peak_coil_current_a", 5.0,
                "closing_velocity_mps", 2.15, "contact_wear_ratio", 0.10), 240);
        activateBaseline(points, featureStates, valveLoop, Map.of(
                "hysteresis_deadband_pct", 1.0, "travel_time_lag_ms", 400.0,
                "friction_force_kn", 1.2, "ultrasonic_leak_rms", 0.04), 360);
        activateBaseline(points, featureStates, motorCurrent, Map.of(
                "sideband_ratio", 0.001, "carrier_amp_db", -33.0), 240);
    }

    /** 测点补齐：仅新建时指定模态；已存在测点保留原模态与业务字段，仅补空的采样率/单位。 */
    private PointEntity seedPoint(PointRepository points, DeviceEntity device, String code, String name,
            String modalityType, int samplingRate, String unit) {
        PointEntity point = points.findByDevice_Factory_FactoryCodeAndDevice_DeviceCodeAndPointCode(
                device.getFactory().getFactoryCode(), device.getDeviceCode(), code).orElse(null);
        if (point == null) {
            point = points.save(new PointEntity(device, code, name, null));
            point.setModalityType(modalityType);
        }
        if (point.getSamplingRate() == null) point.setSamplingRate(samplingRate);
        if (point.getEngineeringUnit() == null) point.setEngineeringUnit(unit);
        return points.save(point);
    }

    /** Demo classification may be corrected explicitly while leaving all other device fields untouched. */
    private static void assignDeviceType(DeviceEntity device, DeviceTypeEntity type) {
        if (!type.getTypeCode().equals(device.getDeviceType().getTypeCode())) device.assignDeviceType(type);
    }


    /** 断路器分闸快照：设计 §2.2 十点曲线（时间轴/线圈电流/触头行程）+ 行程标注计算。 */
    private void seedBreakerSnapshot(ChannelSnapshotRepository snapshots, WaveformStorage storage, PointEntity point) {
        if (!snapshots.findTopByPoint_IdOrderBySampledAtDesc(point.getId()).isEmpty()) return;
        List<Double> time = List.of(0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0, 40.0, 45.0);
        List<Double> current = List.of(0.0, 0.8, 3.2, 5.42, 4.1, 2.0, 0.5, 0.1, 0.0, 0.0);
        List<Double> travel = List.of(0.0, 0.0, 0.2, 1.5, 4.8, 9.2, 14.5, 18.2, 20.0, 20.0);
        Map<String, List<Double>> curves = new LinkedHashMap<>();
        curves.put(StrokeAnnotationCalculator.TIME_AXIS_MS, time);
        curves.put(StrokeAnnotationCalculator.COIL_CURRENT_A, current);
        curves.put(StrokeAnnotationCalculator.TRAVEL_STROKE_MM, travel);
        ChannelSnapshotEntity snapshot = new ChannelSnapshotEntity(point,
                ChannelSnapshotEntity.TRIGGER_TRIP_ACTION_BURST, Instant.now(), 10000.0, current.size(),
                current, 96.5);
        snapshot.setStorageRef(storage.store(point.getId(), snapshot.getSampledAt().toEpochMilli(), current));
        snapshot.setCurves(curves);
        snapshot.setCurveUnits(Map.of("time_axis_ms", "ms", "coil_current_a", "A", "travel_stroke_mm", "mm"));
        snapshot.setMetrics(Map.of("trip_duration_ms", 42.5, "peak_coil_current_a", 5.42));
        snapshot.setAnnotations(StrokeAnnotationCalculator.fromCurves(curves, 10000.0).toMap());
        snapshots.save(snapshot);
    }

    /** 调节阀回环快照：合成开度指令周期，反馈滞后约 2% 后收敛；标注由回环分析器计算。 */
    private void seedValveSnapshot(ChannelSnapshotRepository snapshots, WaveformStorage storage, PointEntity point) {
        if (!snapshots.findTopByPoint_IdOrderBySampledAtDesc(point.getId()).isEmpty()) return;
        List<Double> cv = List.of(0.0, 25.0, 50.0, 75.0, 75.0, 50.0, 25.0, 0.0, 0.0);
        List<Double> fb = List.of(0.0, 23.0, 48.0, 72.0, 75.0, 50.0, 25.0, 2.0, 0.0);
        Map<String, List<Double>> curves = new LinkedHashMap<>();
        curves.put("cv_pct", cv);
        curves.put("fb_pct", fb);
        ChannelSnapshotEntity snapshot = new ChannelSnapshotEntity(point,
                ChannelSnapshotEntity.TRIGGER_SCHEDULED, Instant.now(), 1.0, cv.size(),
                fb, 97.0);
        snapshot.setStorageRef(storage.store(point.getId(), snapshot.getSampledAt().toEpochMilli(), fb));
        snapshot.setCurves(curves);
        snapshot.setCurveUnits(Map.of("cv_pct", "%", "fb_pct", "%"));
        snapshot.setMetrics(Map.of("hysteresis_deadband_pct", 1.0, "travel_time_lag_ms", 400.0));
        snapshot.setAnnotations(ValveLoopAnalyzer.fromCurves(curves).toMap());
        snapshots.save(snapshot);
    }

    /** 基线特征行补齐（存在即跳过）并把测点置为 ACTIVE，多模态工作台免等学习期。 */
    private void activateBaseline(PointRepository points, BaselineFeatureStateRepository featureStates,
            PointEntity point, Map<String, Double> p50ByCode, int sampleCount) {
        for (var entry : p50ByCode.entrySet()) {
            if (featureStates.findByPoint_IdAndModalityTypeAndFeatureCode(
                    point.getId(), point.getModalityType(), entry.getKey()).isPresent()) continue;
            BaselineFeatureStateEntity state = new BaselineFeatureStateEntity(
                    point, point.getModalityType(), entry.getKey());
            state.setP50(entry.getValue());
            state.setCv(0.05);
            state.setSampleCount(sampleCount);
            state.setStatus(com.pdm.mvp.analytics.BaselineService.ACTIVE);
            state.setLockedAt(Instant.now());
            featureStates.save(state);
        }
        if (point.getBaselineStatus() != com.pdm.mvp.analytics.BaselineService.ACTIVE) {
            point.setBaselineStatus(com.pdm.mvp.analytics.BaselineService.ACTIVE);
            point.setBaselineLockedAt(Instant.now());
            if (point.getLearnedSamples() <= 0) point.setLearnedSamples(sampleCount);
            points.save(point);
        }
    }

    /** 健康态合成波形：25Hz 转频 + 1250Hz 共振带小幅载波，供谱计算演示。 */
    private static List<Double> demoWaveform() {
        int n = 4096;
        double fs = 8192.0;
        List<Double> samples = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            double value = 0.45 * Math.sin(2 * Math.PI * 25.0 * t)
                    + 0.08 * Math.sin(2 * Math.PI * 1250.0 * t + 0.5 * Math.sin(2 * Math.PI * 2.0 * t))
                    + 0.02 * Math.sin(2 * Math.PI * 97.0 * t + i % 7);
            samples.add(Math.round(value * 1000.0) / 1000.0);
        }
        return samples;
    }
}
