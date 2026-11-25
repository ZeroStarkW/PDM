package com.pdm.mvp.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.PointValues;
import com.influxdb.v3.client.write.WritePrecision;
import com.pdm.mvp.alert.AlertEntity;
import com.pdm.mvp.alert.AlertRepository;
import com.pdm.mvp.alert.AlertService;
import com.pdm.mvp.alert.DeviceHealthWindow;
import com.pdm.mvp.alert.NotificationRepository;
import com.pdm.mvp.asset.AssetController;
import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.AssetService;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.DeviceRepository;
import com.pdm.mvp.asset.DeviceTypeEntity;
import com.pdm.mvp.asset.DeviceTypeService;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.FactoryRepository;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.bearing.BearingLibraryEntity;
import com.pdm.mvp.bearing.BearingLibraryService;
import com.pdm.mvp.diagnostics.DiagnosticEvaluator;
import com.pdm.mvp.diagnostics.DiagnosticMatch;
import com.pdm.mvp.diagnostics.DiagnosticMetric;
import com.pdm.mvp.diagnostics.DiagnosticRuleRepository;
import com.pdm.mvp.dsp.BandRms;
import com.pdm.mvp.dsp.Fft;
import com.pdm.mvp.dsp.HilbertEnvelope;
import com.pdm.mvp.mcsa.McsaPlugin;
import com.pdm.mvp.mcsa.McsaProperties;
import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.modality.DiagnosticMicrokernel;
import com.pdm.mvp.modality.ModalityPluginRegistry;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.modality.TelemetryFreshnessPolicy;
import com.pdm.mvp.telemetry.OperatingCondition;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import com.pdm.mvp.telemetry.TelemetryPoint;
import com.pdm.mvp.telemetry.TelemetryRequest;
import com.pdm.mvp.telemetry.TelemetryService;
import com.pdm.mvp.telemetry.TelemetryValidator;
import com.pdm.mvp.telemetry.VibrationContractAdapter;
import com.pdm.mvp.timeseries.InfluxTelemetryStore;
import com.pdm.mvp.timeseries.TelemetryStore;
import com.pdm.mvp.waveform.LiveStreamController;
import com.pdm.mvp.waveform.RawWaveformPayload;
import com.pdm.mvp.waveform.WaveframeService;
import com.pdm.mvp.waveform.WaveformService;
import com.pdm.mvp.waveform.WaveformSnapshotEntity;
import com.pdm.mvp.waveform.WaveformSnapshotRepository;
import com.pdm.mvp.waveform.WaveformStorage;
import com.pdm.mvp.workorder.DiagnosisVerdict;
import com.pdm.mvp.workorder.WorkOrderController;
import com.pdm.mvp.workorder.WorkOrderEntity;
import com.pdm.mvp.workorder.WorkOrderRepository;
import com.pdm.mvp.workorder.WorkOrderService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * P0 核心代码与算法缺陷回归验证：
 * 覆盖 Bug 1~7、ANALYSIS_PENDING 双写消除、并发安全与订阅泄漏防护、检修/报废设备静默、ISO 10816 动态分级、非旋转设备零转速台账及波形快照 QoS1 幂等重投。
 */
class CoreP0FixesVerificationTest {
    private static final Instant T0 = Instant.parse("2026-11-25T00:00:00Z");
    private static final long T0_MS = T0.toEpochMilli();

    // ==================== Bug 1: 1Hz 旋转振动 dk/dt 雪崩抑制与噪声死区 ====================

    @Test
    void bug1_rotatingVibration1HzFourHourAvalancheIsDetectedWithoutOverflowingWindow() {
        RotatingVibrationPlugin plugin = vibrationPlugin();
        PointEntity point = rotatingPoint(101L, "CLASS_II", DeviceEntity.STATUS_ACTIVE);
        BaselineSnapshot baseline = activeVibBaseline(101L);

        // 连续 4 小时 1Hz 采样（14,401 条）：1X 从 1.05 线性恶化至 1.30（静态倍率 1.30 得分仍高达 96.8，需靠 η_dyn 压分）
        int totalSeconds = 4 * 3600;
        for (int sec = 0; sec <= totalSeconds; sec++) {
            double ratio = 1.05 + (1.30 - 1.05) * sec / (double) totalSeconds;
            TelemetryEnvelope sample = vibEnvelope(T0_MS + sec * 1000L, ratio, 0.2, 1.0, 45.0, 1500.0);
            plugin.observeDegradation(point, sample, baseline);
        }

        TelemetryEnvelope latest = vibEnvelope(T0_MS + totalSeconds * 1000L, 1.30, 0.2, 1.0, 45.0, 1500.0);
        DiagnosticMatch diag = plugin.diagnose(point, latest, baseline);
        HealthAssessment assessment = plugin.score(point, latest, baseline, diag);

        assertEquals(1.5, assessment.slopeDkDt(), 0.08, "1Hz 经 5 分钟抽稀后应准确拟合约 1.5/24h 斜率");
        assertTrue(assessment.etaDyn() < 0.05, "η_dyn 必须触发强抑制: " + assessment.etaDyn());
        assertTrue(assessment.healthIndex() < 30.0, "综合 HI 必须被压至 DANGER: " + assessment.healthIndex());
        assertEquals("DANGER", assessment.level());
        assertTrue(assessment.diagnosis().factor().contains("雪崩"), "根因阶梯应输出雪崩告警: " + assessment.diagnosis().factor());
    }

    @Test
    void bug1_rotatingVibration1HzSteadyNoiseNeverTriggersFalseAvalanche() {
        RotatingVibrationPlugin plugin = vibrationPlugin();
        PointEntity point = rotatingPoint(102L, "CLASS_II", DeviceEntity.STATUS_ACTIVE);
        BaselineSnapshot baseline = activeVibBaseline(102L);
        Random random = new Random(2026L);

        // 连续 2 小时 1Hz 微小高斯噪声（k 在 1.00 ± 0.03 内波动，极差 < 0.15）
        for (int sec = 0; sec <= 7200; sec++) {
            double ratio = 1.0 + random.nextGaussian() * 0.015;
            plugin.observeDegradation(point, vibEnvelope(T0_MS + sec * 1000L, ratio, 0.2, 1.0, 45.0, 1500.0), baseline);
        }

        TelemetryEnvelope latest = vibEnvelope(T0_MS + 7200_000L, 1.02, 0.2, 1.0, 45.0, 1500.0);
        HealthAssessment assessment = plugin.score(point, latest, baseline, plugin.diagnose(point, latest, baseline));

        assertEquals(0.0, assessment.slopeDkDt(), 1e-9, "极差不足 0.15 的传感器微噪斜率必须归零");
        assertEquals(1.0, assessment.etaDyn(), 1e-9);
        assertEquals(100.0, assessment.healthIndex(), 1e-6);
    }

    // ==================== Bug 2: 频谱基线带拒绝越限触发与带病状态 ====================

    @Test
    void bug2_spectrumBaselineRejectsThresholdExceededAndUnhealthyPointStates() {
        SpectrumBandSampleRepository sampleRepo = mock(SpectrumBandSampleRepository.class);
        SpectrumBandStateRepository stateRepo = mock(SpectrumBandStateRepository.class);
        List<SpectrumBandSampleEntity> savedSamples = new ArrayList<>();
        List<SpectrumBandStateEntity> savedStates = new ArrayList<>();
        when(sampleRepo.save(any())).thenAnswer(inv -> { savedSamples.add(inv.getArgument(0)); return inv.getArgument(0); });
        when(sampleRepo.countByPoint_IdAndKind(anyLong(), anyString())).thenAnswer(inv -> (long) savedSamples.size());
        SpectrumBaselineService spectrumBaseline = new SpectrumBaselineService(sampleRepo, stateRepo, 2, Duration.ofDays(30));

        PointEntity point = rotatingPoint(7L, "CLASS_II", DeviceEntity.STATUS_ACTIVE);
        point.setBaselineStatus(BaselineService.ACTIVE);
        point.setLastHealthIndex(95.0);
        point.setLastStatus("HEALTHY");

        AssetLookup assets = mock(AssetLookup.class);
        when(assets.requirePointByDeviceAndPoint(anyString(), anyString())).thenReturn(point);
        WaveformSnapshotRepository snapshotRepo = mock(WaveformSnapshotRepository.class);
        when(snapshotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        WaveformStorage storage = mock(WaveformStorage.class);
        when(storage.store(anyLong(), anyLong(), any())).thenReturn("file:/tmp/wf.json");
        WaveframeService waveframes = new WaveframeService(1000, 2500);
        WaveformService waveformService = new WaveformService(snapshotRepo, storage, waveframes, assets,
                mock(AlertRepository.class), mock(PointRepository.class), mock(com.pdm.mvp.telemetry.MqttCommandPublisher.class),
                JsonMapper.builder().build(), spectrumBaseline, 16384);

        List<Double> signal = new ArrayList<>(128);
        for (int i = 0; i < 128; i++) signal.add(Math.sin(2 * Math.PI * 25.0 * i / 1024.0));

        // 1) 故障越限帧（THRESHOLD_EXCEEDED）连续上报 10 次，0 帧进入学习
        for (int i = 0; i < 10; i++) {
            waveformService.accept(new RawWaveformPayload("2.0.0", T0_MS + i * 60_000L, "PUMP_01", "DE",
                    WaveformSnapshotEntity.TRIGGER_THRESHOLD, 1024.0, signal.size(), "m/s^2", null, null, signal));
        }
        assertEquals(0, savedSamples.size(), "THRESHOLD_EXCEEDED 越限帧绝不能污染频谱基线带");

        // 2) 标量健康分已跌入 ATTENTION/WARNING（HI=48.0）时的定时巡检帧同样拒绝吸收
        point.setLastHealthIndex(48.0);
        point.setLastStatus("WARNING");
        waveformService.accept(new RawWaveformPayload("2.0.0", T0_MS + 700_000L, "PUMP_01", "DE",
                WaveformSnapshotEntity.TRIGGER_SCHEDULED, 1024.0, signal.size(), "m/s^2", null, null, signal));
        assertEquals(0, savedSamples.size(), "测点处于 WARNING/HI<70 时定时巡检帧也不得进入参考带学习");

        // 3) 标量基线处于 DIVERGED 时拒绝吸收
        point.setBaselineStatus(BaselineService.DIVERGED);
        point.setLastHealthIndex(null);
        point.setLastStatus("BASELINE_DIVERGENCE");
        waveformService.accept(new RawWaveformPayload("2.0.0", T0_MS + 800_000L, "PUMP_01", "DE",
                WaveformSnapshotEntity.TRIGGER_ON_DEMAND, 1024.0, signal.size(), "m/s^2", null, null, signal));
        assertEquals(0, savedSamples.size(), "标量基线未收敛（DIVERGED）时不得吸收频谱样本");
        assertTrue(savedStates.isEmpty());
    }

    // ==================== 波形快照幂等：MQTT QoS1 同戳重投去重 ====================

    @Test
    void waveformQos1RedeliveryWithSamePointAndTimestampPersistsSnapshotExactlyOnce() {
        PointEntity point = rotatingPoint(91L, "CLASS_II", DeviceEntity.STATUS_ACTIVE);
        AssetLookup assets = mock(AssetLookup.class);
        when(assets.requirePointByDeviceAndPoint(anyString(), anyString())).thenReturn(point);

        List<WaveformSnapshotEntity> store = new ArrayList<>();
        WaveformSnapshotRepository snapshotRepo = mock(WaveformSnapshotRepository.class);
        when(snapshotRepo.save(any())).thenAnswer(inv -> {
            WaveformSnapshotEntity entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 901L);
            store.add(entity);
            return entity;
        });
        when(snapshotRepo.findByPoint_IdAndSampledAt(anyLong(), any())).thenAnswer(inv -> store.stream()
                .filter(s -> s.getPoint().getId().equals(inv.getArgument(0)) && s.getSampledAt().equals(inv.getArgument(1)))
                .findFirst());

        WaveformStorage storage = mock(WaveformStorage.class);
        when(storage.store(anyLong(), anyLong(), any())).thenReturn("file:/tmp/wf-replay.json");
        WaveframeService waveframes = new WaveframeService(1000, 2500);
        SpectrumBaselineService spectrumBaseline = mock(SpectrumBaselineService.class);
        WaveformService waveformService = new WaveformService(snapshotRepo, storage, waveframes, assets,
                mock(AlertRepository.class), mock(PointRepository.class), mock(com.pdm.mvp.telemetry.MqttCommandPublisher.class),
                JsonMapper.builder().build(), spectrumBaseline, 16384);

        List<Double> signal = new ArrayList<>(128);
        for (int i = 0; i < 128; i++) signal.add(Math.sin(2 * Math.PI * 25.0 * i / 1024.0));
        RawWaveformPayload redelivered = new RawWaveformPayload("2.0.0", T0_MS, "PUMP_01", "DE",
                WaveformSnapshotEntity.TRIGGER_SCHEDULED, 1024.0, signal.size(), "m/s^2", null, null, signal);

        WaveformService.WaveformView first = waveformService.accept(redelivered);
        WaveformService.WaveformView replay = waveformService.accept(redelivered);

        assertEquals(1, store.size(), "同 (point_id, sampled_at) 的 QoS1 重投报文不得二次入库");
        verify(storage, times(1)).store(anyLong(), anyLong(), any());
        verify(snapshotRepo, times(1)).save(any());
        verify(spectrumBaseline, times(1)).observe(any(PointEntity.class), any(WaveframeService.Waveframe.class));
        assertEquals(first.id(), replay.id());
        assertEquals(first.sampledAt(), replay.sampledAt());
        assertEquals(first.storageRef(), replay.storageRef());
    }

    // ==================== Bug 3: MCSA 负分贝特征改为派生线性比尖峰抑制 ====================

    @Test
    void bug3_mcsaSpikeFilterSuppressesSinglePointTransientInNegativeDbDomain() {
        McsaPlugin plugin = new McsaPlugin(new McsaProperties(false, 20, 100, 15, 54, 40), mock(DeviceTypeService.class));
        SpikeFilter filter = new SpikeFilter();

        // 正常样本 sideband=-93dB, carrier=-33dB (sideband_ratio = 0.001)
        // 中心样本电磁瞬态干扰跳变至 sideband=-73dB (sideband_ratio = 0.01 = 10x > 5x 邻均)
        TelemetryEnvelope s1 = mcsaSample(T0_MS, -93.0, -33.0);
        TelemetryEnvelope s2Spike = mcsaSample(T0_MS + 1000, -73.0, -33.0);
        TelemetryEnvelope s3 = mcsaSample(T0_MS + 2000, -93.0, -33.0);

        assertTrue(filter.accept(9L, s1, plugin).isEmpty());
        assertTrue(filter.accept(9L, s2Spike, plugin).isEmpty());
        SpikeFilter.FilteredSample filtered = filter.accept(9L, s3, plugin).orElseThrow();
        assertTrue(filtered.spike(), "单点 +20dB 瞬态毛刺（线性幅值比 10 倍）必须被判为尖峰抑制");

        // 持续故障（连续两帧停留在 -73dB）：邻均上升为 (0.001+0.01)/2=0.0055，中心 0.01 < 5*0.0055，正常放行评分
        TelemetryEnvelope s4Fault = mcsaSample(T0_MS + 3000, -73.0, -33.0);
        TelemetryEnvelope s5Fault = mcsaSample(T0_MS + 4000, -73.0, -33.0);
        assertFalse(filter.accept(9L, s4Fault, plugin).orElseThrow().spike());
        assertFalse(filter.accept(9L, s5Fault, plugin).orElseThrow().spike(), "持续性导条故障不得被误判为单点尖峰");
    }

    // ==================== Bug 4: 自动建单携带 HIGH 优先级与诊断建议措施 ====================

    @Test
    void bug4_alertAutoCreatedWorkOrderCarriesDangerHighPriorityAndDiagnosticAdvice() {
        DeviceHealthWindow window = new DeviceHealthWindow();
        AlertRepository alerts = mock(AlertRepository.class);
        WorkOrderRepository workOrders = mock(WorkOrderRepository.class);
        PointRepository points = mock(PointRepository.class);
        NotificationRepository notifications = mock(NotificationRepository.class);
        TelemetryFreshnessPolicy freshness = mock(TelemetryFreshnessPolicy.class);
        when(freshness.byModality()).thenReturn(m -> Duration.ofMinutes(5));

        PointEntity point = rotatingPoint(15L, "CLASS_II", DeviceEntity.STATUS_ACTIVE);
        point.setBaselineStatus(BaselineService.ACTIVE);
        point.setLastHealthIndex(22.0);
        point.setLastStatus("DANGER");
        // AlertService.observe() 内部用 Instant.now() 判定新鲜度，纪元固定的 T0 会被判 STALE 而不建单
        Instant now = Instant.now();
        point.recordIngest(now, now, 0);
        when(points.findById(15L)).thenReturn(Optional.of(point));
        when(points.findByDevice_IdOrderByPointCode(any())).thenReturn(List.of(point));
        when(alerts.save(any(AlertEntity.class))).thenAnswer(inv -> {
            AlertEntity entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 88L);
            return entity;
        });

        DiagnosticMicrokernel kernel = mock(DiagnosticMicrokernel.class);
        when(kernel.shouldTriggerAlarm(any(), any(), anyLong(), any(), anyBoolean())).thenReturn(true);
        ModalityPluginRegistry registry = new ModalityPluginRegistry(List.of(vibrationPlugin()));
        AlertService alertService = new AlertService(window, alerts, workOrders, points, notifications, freshness, kernel, registry);

        DiagnosticMatch match = new DiagnosticMatch(1L, "通用设备", null, null, DiagnosticMetric.ENVELOPE_RMS,
                null, null, null, null, null, null,
                "轴承内圈点蚀损伤", "BPFI 包络显著恶化", "立即安排停机更换 SKF 6208 轴承并复核润滑脂。");
        HealthAssessment assessment = new HealthAssessment(22.0, 100.0, 22.0, "DANGER", 3, match);

        alertService.observe(15L, vibEnvelope(now.toEpochMilli(), 1.0, 0.2, 1.0, 45.0, 1500.0), assessment, 1.0, 1.0, false);

        ArgumentCaptor<WorkOrderEntity> orderCaptor = ArgumentCaptor.forClass(WorkOrderEntity.class);
        verify(workOrders).save(orderCaptor.capture());
        WorkOrderEntity savedOrder = orderCaptor.getValue();
        assertEquals(WorkOrderEntity.Priority.HIGH, savedOrder.getPriority(), "DANGER 告警自动建单必须赋 HIGH 优先级");
        assertEquals("立即安排停机更换 SKF 6208 轴承并复核润滑脂。", savedOrder.getMaintenanceAction());

        // CMMS 二次派单未填 action 时保留既有建议措施而非覆盖为 null
        when(alerts.findByIdForUpdate(88L)).thenReturn(Optional.of(savedOrder.getAlert()));
        when(workOrders.findByAlert_IdForUpdate(88L)).thenReturn(Optional.of(savedOrder));
        WorkOrderService workOrderService = new WorkOrderService(workOrders, alerts);
        var dispatched = workOrderService.dispatch(88L, "   ", "URGENT", List.of(new WorkOrderEntity.SparePart("轴承", "SKF 6208", 1)));
        assertEquals("URGENT", dispatched.priority());
        assertEquals("立即安排停机更换 SKF 6208 轴承并复核润滑脂。", dispatched.maintenanceAction());
    }

    // ==================== Bug 5: 无环境温度时轴承高温回退分支根因推断 ====================

    @Test
    void bug5_bearingTemperatureWithoutAmbientProducesDeterministicRootCause() {
        HealthIndexCalculator calculator = new HealthIndexCalculator();
        BaselineSnapshot baseline = new BaselineSnapshot(1L, ModalityTypes.ROTATING_VIB, "ACTIVE", 200, T0, null,
                Map.of(VibrationContractAdapter.FREQ_1X_AMP, 0.5,
                        VibrationContractAdapter.FREQ_2X_AMP, 0.2,
                        VibrationContractAdapter.TEMPERATURE, 40.0),
                Map.of());
        // 无 temp_ambient：轴承温度升至 140℃（倍率 3.5 → Sigmoid 得分 < 35），1X/2X/ISO 均满分
        TelemetryEnvelope sample = vibEnvelope(T0_MS, 0.5, 0.2, 1.0, 140.0, 1500.0);
        HealthAssessment assessment = calculator.calculate(sample, baseline, 0.0, null);
        assertTrue(assessment.healthIndex() < 40.0, "高温短板应拉低 HI: " + assessment.healthIndex());

        var verdict = DefectClassifier.classify(assessment);
        assertTrue(verdict.isPresent(), "无环境温度的『轴承温度』短板不得在 DefectClassifier 断链返回 empty");
        assertEquals(DiagnosticMetric.TEMPERATURE, verdict.get().metric());
        assertTrue(verdict.get().defect().contains("轴承温度"));
    }

    // ==================== Bug 7: 非 2 幂波形精确 FFT/包络谱 + 变频实时转速阶次跟踪 ====================

    @Test
    void bug7_nonPowerOfTwo5000And10000PointWaveformsProduceAccurateFftAndEnvelope() {
        WaveframeService waveframes = new WaveframeService(1000.0, 2500.0);
        for (int n : new int[] {5000, 10000, 5001}) {
            double fs = 10000.0;
            List<Double> preview = new ArrayList<>(n);
            Random random = new Random(99L);
            for (int i = 0; i < n; i++) {
                double t = i / fs;
                // 20 Hz 变频转频（幅值 0.60）+ 1800 Hz 共振带受 110 Hz 轴承冲击调制
                double carrier = (1.0 + 0.8 * Math.sin(2 * Math.PI * 110.0 * t)) * 0.25 * Math.sin(2 * Math.PI * 1800.0 * t);
                preview.add(0.60 * Math.sin(2 * Math.PI * 20.0 * t) + carrier + 0.01 * random.nextGaussian());
            }
            PointEntity point = rotatingPoint(70L + n, "CLASS_II", DeviceEntity.STATUS_ACTIVE);
            WaveformSnapshotEntity snapshot = new WaveformSnapshotEntity(point, WaveformSnapshotEntity.TRIGGER_SCHEDULED,
                    T0, fs, n, "m/s^2", preview, 92.0);

            WaveframeService.Waveframe frame = waveframes.analyze(snapshot);
            assertEquals(n / 2 + 1, frame.fftFreqs().length, "点数 " + n + " 不得返回空 FFT");
            assertEquals(n / 2 + 1, frame.envelopeFreqs().length, "点数 " + n + " 不得返回空包络谱");

            double amp20Hz = BandRms.of(frame.fftFreqs(), frame.fftAmps(), 20.0);
            assertEquals(0.60, amp20Hz, 0.03, "非 2 幂点数 " + n + " 的 BandRms 幅值必须无补零失真: " + amp20Hz);

            double env110Hz = BandRms.of(frame.envelopeFreqs(), frame.envelopeAmps(), 110.0);
            assertTrue(env110Hz > 0.05, "非 2 幂点数 " + n + " 包络谱必须检出 110Hz 调制峰: " + env110Hz);
        }

        // Bluestein 正逆变换可逆性边界（质数长度 127 与长度 1）
        double[] re = new double[127], im = new double[127], expected = new double[127];
        for (int i = 0; i < 127; i++) {
            re[i] = Math.cos(0.3 * i) - 0.5 * Math.sin(1.1 * i);
            expected[i] = re[i];
        }
        Fft.transform(re, im);
        Fft.inverse(re, im);
        for (int i = 0; i < 127; i++) {
            assertEquals(expected[i], re[i], 1e-9);
            assertEquals(0.0, im[i], 1e-9);
        }
        double[] singleRe = {4.2}, singleIm = {-1.5};
        Fft.transform(singleRe, singleIm);
        assertEquals(4.2, singleRe[0], 1e-12);
    }

    @Test
    void bug7_liveStreamUsesRealtimeVfdRpmAndHandlesZeroOrNullRpmSafely() throws Exception {
        WaveframeService waveframes = new WaveframeService(1000.0, 2500.0);
        PointRepository pointRepo = mock(PointRepository.class);
        WaveformService waveformService = mock(WaveformService.class);
        LiveStreamController controller = new LiveStreamController(waveformService, waveframes, pointRepo,
                JsonMapper.builder().build());

        // 设备额定转速 1500 RPM (25Hz)，但变频运行在 1200 RPM (20Hz)
        PointEntity point = rotatingPoint(88L, "CLASS_II", DeviceEntity.STATUS_ACTIVE);
        BearingLibraryEntity bearing = mock(BearingLibraryEntity.class);
        when(bearing.getModelName()).thenReturn("SKF 6208");
        when(bearing.getBpfoFactor()).thenReturn(new BigDecimal("3.5395"));
        when(bearing.getBpfiFactor()).thenReturn(new BigDecimal("5.4605"));
        when(bearing.getBsfFactor()).thenReturn(new BigDecimal("2.2358"));
        when(bearing.getFtfFactor()).thenReturn(new BigDecimal("0.3933"));
        point.setBearingModel(bearing);

        waveframes.recordRpm(88L, 1200.0);
        WaveframeService.Waveframe frame = new WaveframeService.Waveframe(T0_MS, 8192.0, "m/s^2",
                new double[] {0.1, -0.1}, new double[] {20.0, 40.0}, new double[] {0.5, 0.2},
                new double[] {50.0, 70.79, 109.21}, new double[] {0.01, 0.25, 0.02});

        var method = LiveStreamController.class.getDeclaredMethod("buildFrame", PointEntity.class, WaveframeService.Waveframe.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> vfdPayload = (Map<String, Object>) method.invoke(controller, point, frame);
        assertEquals(1200.0, (Double) vfdPayload.get("rpm"), 1e-6);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> harmonics = (List<Map<String, Object>>) vfdPayload.get("harmonics");
        assertEquals(20.0, (Double) harmonics.getFirst().get("hz"), 1e-3, "1X 光标必须跟随实时变频 20Hz 而非额定 25Hz");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> faultLines = (List<Map<String, Object>>) vfdPayload.get("fault_lines");
        assertEquals(70.79, (Double) faultLines.getFirst().get("hz"), 0.01, "BPFO 故障线必须按 20Hz×3.5395=70.79Hz 解算");

        // 非旋转设备 rpmNominal=null / 0 时不抛 NPE，返回空谐波与故障线
        waveframes.clearRpm(88L);
        DeviceEntity zeroRpmDevice = mock(DeviceEntity.class);
        when(zeroRpmDevice.getRpmNominal()).thenReturn(null);
        PointEntity nonRotating = new PointEntity(zeroRpmDevice, "TRIP", "分闸", null);
        ReflectionTestUtils.setField(nonRotating, "id", 89L);
        @SuppressWarnings("unchecked")
        Map<String, Object> zeroPayload = (Map<String, Object>) method.invoke(controller, nonRotating, frame);
        assertEquals(0.0, (Double) zeroPayload.get("rpm"), 1e-6);
        assertTrue(((List<?>) zeroPayload.get("harmonics")).isEmpty());

        var shutdown = LiveStreamController.class.getDeclaredMethod("shutdown");
        shutdown.setAccessible(true);
        shutdown.invoke(controller);
    }

    // ==================== P0.2: 消除双写 + 时序同戳去重 + SSE 并发竞态无泄漏 ====================

    @Test
    void p02_telemetrySingleWriteAndInfluxRawDeduplicatesHistoricalPendingRows() {
        InfluxDBClient influx = mock(InfluxDBClient.class);
        // 模拟历史数据同一时间戳既有 ANALYSIS_PENDING (-1.0) 又有正式 HEALTHY (91.5)
        long ns = 1732262400000000000L;
        when(influx.queryPoints(anyString())).thenReturn(Stream.of(
                PointValues.measurement("point_telemetry").setTimestamp(ns, WritePrecision.NS)
                        .setField("health_index", -1.0).setField("data_quality", 0L)
                        .setField("status", "ANALYSIS_PENDING").setField("cv_pct", 50.0),
                PointValues.measurement("point_telemetry").setTimestamp(ns, WritePrecision.NS)
                        .setField("health_index", 91.5).setField("data_quality", 0L)
                        .setField("status", "HEALTHY").setField("cv_pct", 50.0)));
        PointEntity point = rotatingPoint(1L, "CLASS_II", DeviceEntity.STATUS_ACTIVE);
        try (InfluxTelemetryStore store = new InfluxTelemetryStore(influx)) {
            List<TelemetryStore.ChannelSample> raw = store.queryChannel(point, T0.minusSeconds(60), T0, "raw", List.of("cv_pct"), 100);
            assertEquals(1, raw.size(), "同毫秒重复脏行应去重为 1 条");
            assertEquals("HEALTHY", raw.getFirst().status());
            assertEquals(91.5, raw.getFirst().healthIndex(), 1e-6);
        }
    }

    @Test
    void p02_liveStreamConcurrentSubscribeAndImmediateSendFailureLeavesZeroLeakedTasks() throws Exception {
        WaveframeService waveframes = new WaveframeService(1000.0, 2500.0);
        waveframes.cache(5L, new WaveframeService.Waveframe(T0_MS, 4096.0, "m/s^2",
                new double[] {0.1}, new double[] {25.0}, new double[] {0.4}, new double[0], new double[0]));
        PointRepository pointRepo = mock(PointRepository.class);
        // rotatingPoint() 内含 mock()/when() 打桩，须先于外层 when(...).thenReturn(...) 完成，避免 UnfinishedStubbing
        PointEntity livePoint = rotatingPoint(5L, "CLASS_II", DeviceEntity.STATUS_ACTIVE);
        when(pointRepo.findByIdWithDeviceAndBearing(5L)).thenReturn(Optional.of(livePoint));

        // 构造序列化抛错使首帧发送在 initialDelay=0 时立即失败，检验 Subscription 是否存在任务泄漏
        JsonMapper failingMapper = mock(JsonMapper.class);
        doThrow(new RuntimeException("client disconnected")).when(failingMapper).writeValueAsString(any());
        LiveStreamController controller = new LiveStreamController(mock(WaveformService.class), waveframes, pointRepo, failingMapper);

        int threads = 16;
        var pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int j = 0; j < 5; j++) {
                        SseEmitter emitter = controller.stream(5L);
                        assertNotNull(emitter);
                    }
                } catch (Exception ignored) {
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(doneGate.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();

        // 等待异步首帧异常清理完毕
        long deadline = System.currentTimeMillis() + 2000;
        var activeCount = LiveStreamController.class.getDeclaredMethod("activeSubscriberCount");
        activeCount.setAccessible(true);
        while ((int) activeCount.invoke(controller) > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, (int) activeCount.invoke(controller), "首帧异常断开的所有订阅必须全部清理，零任务泄漏");

        var shutdown = LiveStreamController.class.getDeclaredMethod("shutdown");
        shutdown.setAccessible(true);
        shutdown.invoke(controller);
    }

    // ==================== P0.3: 设备检修/报废静默 + ISO 10816 分级 + 非旋转设备零转速 ====================

    @Test
    void p03_maintenanceAndRetiredDevicesSilenceLearningScoringAndAlerts() {
        AssetLookup assets = mock(AssetLookup.class);
        TelemetryStore store = mock(TelemetryStore.class);
        AnalyticsEngine analytics = mock(AnalyticsEngine.class);
        PointRepository points = mock(PointRepository.class);
        AlertService alerts = mock(AlertService.class);
        ModalityPluginRegistry plugins = new ModalityPluginRegistry(List.of(vibrationPlugin()));
        TelemetryService telemetry = new TelemetryService(assets, mock(TelemetryValidator.class), store, analytics, points, alerts, plugins);

        PointEntity maintenancePoint = rotatingPoint(31L, "CLASS_II", DeviceEntity.STATUS_MAINTENANCE);
        when(assets.requirePoint(anyString(), anyString(), anyString())).thenReturn(maintenancePoint);

        var resMaint = telemetry.accept(vibEnvelope(T0_MS, 5.0, 2.0, 9.5, 95.0, 1500.0), "MQTT");
        assertEquals("MAINTENANCE", resMaint.status());
        assertNull(resMaint.healthIndex());
        verify(analytics).reset(31L);
        verify(alerts).resetDebounce(31L);
        verify(analytics, never()).observe(any(), any(), any());
        verify(alerts, never()).observe(any(), any(), any(), anyDouble(), anyDouble(), anyBoolean());
        verify(store, times(1)).write(any(TelemetryPoint.class));

        // 汇总视图同样显示 MAINTENANCE 且不计入故障评分
        var summary = DeviceHealthRollup.assess(maintenancePoint.getDevice(), List.of(maintenancePoint), T0,
                m -> Duration.ofMinutes(5));
        assertEquals("MAINTENANCE", summary.status());
        assertNull(summary.healthIndex());
        assertEquals(0, summary.scoredPointCount());
    }

    @Test
    void p03_isoClassDynamicGradingAndNonRotatingZeroRpmAssetManagement() {
        RotatingVibrationPlugin plugin = vibrationPlugin();
        BaselineSnapshot baseline = activeVibBaseline(1L);
        // v_rms = 3.2 mm/s：
        // - CLASS_I (1.8/2.8) 超过危险限 2.8 → < 30 (DANGER)
        // - CLASS_II (2.8/4.5) 落在关注带 → ~64.1 (ATTENTION)
        // - CLASS_III (4.5/7.1) 低于告警限 4.5 → 100.0 (HEALTHY)
        // - CLASS_IV (7.1/11.2) 低于告警限 7.1 → 100.0 (HEALTHY)
        TelemetryEnvelope sample = vibEnvelope(T0_MS, 0.5, 0.2, 3.2, 45.0, 1500.0);

        PointEntity class1 = rotatingPoint(1L, "CLASS_I", DeviceEntity.STATUS_ACTIVE);
        PointEntity class2 = rotatingPoint(2L, "CLASS_II", DeviceEntity.STATUS_ACTIVE);
        PointEntity class3 = rotatingPoint(3L, "CLASS_III", DeviceEntity.STATUS_ACTIVE);
        PointEntity class4 = rotatingPoint(4L, "CLASS_IV", DeviceEntity.STATUS_ACTIVE);

        HealthAssessment s1 = plugin.score(class1, sample, baseline, new DiagnosticMatch(1L, "电机", null, null, null, null, null, null, null, null, null, "正常", "", ""));
        HealthAssessment s2 = plugin.score(class2, sample, baseline, new DiagnosticMatch(1L, "水泵", null, null, null, null, null, null, null, null, null, "正常", "", ""));
        HealthAssessment s3 = plugin.score(class3, sample, baseline, new DiagnosticMatch(1L, "压缩机", null, null, null, null, null, null, null, null, null, "正常", "", ""));
        HealthAssessment s4 = plugin.score(class4, sample, baseline, new DiagnosticMatch(1L, "汽轮机", null, null, null, null, null, null, null, null, null, "正常", "", ""));

        assertEquals(26.0, s1.healthIndex(), 0.01, "CLASS_I 在 3.2mm/s 应判危险");
        assertEquals("DANGER", s1.level());
        assertTrue(s2.healthIndex() > 60.0 && s2.healthIndex() < 70.0, "CLASS_II 在 3.2mm/s 应判关注");
        assertEquals(100.0, s3.healthIndex(), 0.01, "CLASS_III 在 3.2mm/s 应满分健康");
        assertEquals(100.0, s4.healthIndex(), 0.01, "CLASS_IV 在 3.2mm/s 应满分健康");

        // AssetController Bean Validation + AssetService：放开非旋转设备 rpmNominal=0 / null，并支持 isoClass 校验与持久化
        Validator beanValidator = Validation.buildDefaultValidatorFactory().getValidator();
        var zeroRpmUpdate = new AssetController.DeviceUpdateRequest("10kV 进线断路器", BigDecimal.ZERO, null, 2, 10L, "CLASS_III");
        var nullRpmUpdate = new AssetController.DeviceUpdateRequest("给水调节阀", null, null, 1, 11L, "CLASS_I");
        assertTrue(beanValidator.validate(zeroRpmUpdate).isEmpty(), "rpmNominal=0 必须通过 Bean Validation");
        assertTrue(beanValidator.validate(nullRpmUpdate).isEmpty(), "rpmNominal=null 必须通过 Bean Validation");
        var negativeRpmUpdate = new AssetController.DeviceUpdateRequest("非法设备", new BigDecimal("-1"), null, 1, 11L, null);
        assertFalse(beanValidator.validate(negativeRpmUpdate).isEmpty(), "负数转速仍应被拦截");

        FactoryRepository factories = mock(FactoryRepository.class);
        DeviceRepository devices = mock(DeviceRepository.class);
        DeviceTypeService deviceTypes = mock(DeviceTypeService.class);
        FactoryEntity factory = new FactoryEntity("FACT_01", "一车间");
        DeviceTypeEntity breakerType = mock(DeviceTypeEntity.class);
        when(breakerType.getId()).thenReturn(10L);
        when(breakerType.getTypeCode()).thenReturn("CIRCUIT_BREAKER");
        when(breakerType.getTypeName()).thenReturn("高压断路器");
        when(factories.findByFactoryCode("FACT_01")).thenReturn(Optional.of(factory));
        when(deviceTypes.require(10L)).thenReturn(breakerType);
        when(devices.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssetService assetService = new AssetService(factories, devices, mock(PointRepository.class), deviceTypes,
                new ModalityPluginRegistry(List.of(vibrationPlugin())), mock(BearingLibraryService.class));
        var created = assetService.createDevice("FACT_01", "bkr_09", "开关柜 09", null, null, 10L, "class_iv");
        assertEquals(0, created.rpmNominal().compareTo(BigDecimal.ZERO), "断路器缺省转速应落为 0 而非 1500");
        assertEquals("CLASS_IV", created.isoClass());

        assertThrows(ResponseStatusException.class,
                () -> assetService.createDevice("FACT_01", "bkr_10", "开关柜 10", BigDecimal.ZERO, null, 10L, "CLASS_IX"),
                "非法 isoClass 应返回 400");
    }

    // ==================== 任务单 2: 工单闭环可选联动设备基线重置申请 ====================

    /** 构造 OPEN 工单 + 关联 DANGER 告警的闭环夹具；测点挂在设备 id=10 上。 */
    private WorkOrderEntity closedOrderFixture(WorkOrderRepository workOrders, AlertRepository alerts,
            AlertEntity[] alertOut, long orderId, long alertId) {
        PointEntity point = rotatingPoint(orderId + 17L, "CLASS_II", DeviceEntity.STATUS_ACTIVE);
        DiagnosticMatch match = new DiagnosticMatch(1L, "通用设备", null, null, DiagnosticMetric.ENVELOPE_RMS,
                null, null, null, null, null, null, "轴承内圈点蚀损伤", "BPFI 包络显著恶化", "立即安排停机更换 SKF 6208 轴承并复核润滑脂。");
        AlertEntity alert = new AlertEntity(point, "DANGER", 22.0, match, 2.4, 0.45, List.of());
        ReflectionTestUtils.setField(alert, "id", alertId);
        WorkOrderEntity order = new WorkOrderEntity(alert);
        ReflectionTestUtils.setField(order, "id", orderId);
        when(workOrders.findAlertId(orderId)).thenReturn(Optional.of(alertId));
        when(alerts.findByIdForUpdate(alertId)).thenReturn(Optional.of(alert));
        when(workOrders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        alertOut[0] = alert;
        return order;
    }

    @Test
    void workOrderCloseWithBaselineResetFlagRequestsDeviceRelearnOnAlertDevice() {
        WorkOrderRepository workOrders = mock(WorkOrderRepository.class);
        AlertRepository alerts = mock(AlertRepository.class);
        BaselineService baselines = mock(BaselineService.class);
        WorkOrderService service = new WorkOrderService(workOrders, alerts);
        ReflectionTestUtils.setField(service, "baselines", baselines);

        // 勾选且工单实际从非 CLOSED 完成闭环 → 联动申请告警设备（id=10）基线重学
        AlertEntity[] alertRef = new AlertEntity[1];
        closedOrderFixture(workOrders, alerts, alertRef, 44L, 77L);
        var closed = service.close(44L, "更换轴承后复测合格", DiagnosisVerdict.CONFIRMED,
                "内圈点蚀确认", "更换 SKF 6208 并激光对中", true);
        assertEquals("CLOSED", closed.status());
        assertEquals((short) 2, alertRef[0].getStatus(), "闭环应同步告警状态为已处理");

        // 不勾选 / 旧 5 参 close 兼容重载 / 未装配 BaselineService → 均不触发联动且闭环正常
        closedOrderFixture(workOrders, alerts, new AlertEntity[1], 45L, 78L);
        service.close(45L, "复位异物", DiagnosisVerdict.CONFIRMED, null, null, false);
        closedOrderFixture(workOrders, alerts, new AlertEntity[1], 46L, 79L);
        service.close(46L, "note", null, null, null);
        WorkOrderService unwired = new WorkOrderService(workOrders, alerts);
        closedOrderFixture(workOrders, alerts, new AlertEntity[1], 47L, 80L);
        assertEquals("CLOSED", unwired.close(47L, "更换联轴器", null, null, null, true).status());

        verify(baselines, times(1)).requestDeviceReset(10L, null);
    }

    @Test
    void baselineResetConflictNeverBlocksWorkOrderCloseAndAlreadyClosedSkipsRelearn() {
        WorkOrderRepository workOrders = mock(WorkOrderRepository.class);
        AlertRepository alerts = mock(AlertRepository.class);
        BaselineService baselines = mock(BaselineService.class);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "设备还没有测点"))
                .when(baselines).requestDeviceReset(anyLong(), any());

        WorkOrderService service = new WorkOrderService(workOrders, alerts);
        ReflectionTestUtils.setField(service, "baselines", baselines);

        closedOrderFixture(workOrders, alerts, new AlertEntity[1], 51L, 81L);
        var closed = service.close(51L, "更换轴承", DiagnosisVerdict.CONFIRMED, null, null, true);
        assertEquals("CLOSED", closed.status());

        // 重复闭环（已 CLOSED）直接幂等返回，不再触发基线重置
        WorkOrderEntity alreadyClosed = closedOrderFixture(workOrders, alerts, new AlertEntity[1], 53L, 83L);
        alreadyClosed.close("历史闭环", false);
        service.close(53L, "再来一次", null, null, null, true);
        verify(baselines, times(1)).requestDeviceReset(anyLong(), any());

        // 非 409 异常照常抛出（工单事务交由上层回滚）
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"))
                .when(baselines).requestDeviceReset(anyLong(), any());
        closedOrderFixture(workOrders, alerts, new AlertEntity[1], 54L, 84L);
        assertThrows(ResponseStatusException.class,
                () -> service.close(54L, "note", null, null, null, true));
    }

    @Test
    void closeRequestRecordKeepsFourArgCompatibilityAndCarriesResetFlag() {
        var legacy = new WorkOrderController.CloseRequest("检修完成", DiagnosisVerdict.CONFIRMED, "内圈点蚀", "更换轴承");
        assertEquals(Boolean.FALSE, legacy.requestBaselineReset(), "旧 4 参构造器必须默认不申请基线重置");
        var flagged = new WorkOrderController.CloseRequest("检修完成", null, null, null, true);
        assertTrue(flagged.requestBaselineReset());
    }

    // ==================== 任务单 2: 车间测点单次批量查询消除 N+1 ====================

    @Test
    void factoryPointBatchQueryNormalizesCodeAndControllerRoutesDeviceIdAndFactoryCode() {
        PointRepository pointRepo = mock(PointRepository.class);
        PointEntity de = rotatingPoint(61L, "CLASS_II", DeviceEntity.STATUS_ACTIVE);
        PointEntity nde = rotatingPoint(62L, "CLASS_II", DeviceEntity.STATUS_ACTIVE);
        when(pointRepo.findByFactoryCodeWithContext("FACT_01")).thenReturn(List.of(de, nde));
        AssetService assetService = new AssetService(mock(FactoryRepository.class), mock(DeviceRepository.class),
                pointRepo, mock(DeviceTypeService.class), new ModalityPluginRegistry(List.of(vibrationPlugin())),
                mock(BearingLibraryService.class));

        var views = assetService.pointsByFactory(" fact_01 ");
        verify(pointRepo).findByFactoryCodeWithContext("FACT_01");
        assertEquals(2, views.size());
        assertEquals(10L, views.getFirst().deviceId().longValue());
        assertEquals("DE", views.getFirst().pointCode());

        // Controller 路由：deviceId 优先单设备，factoryCode 批量，二者皆缺失 400
        AssetService assets = mock(AssetService.class);
        AssetController controller = new AssetController(assets);
        when(assets.points(5L)).thenReturn(List.of());
        when(assets.pointsByFactory("FACT_01")).thenReturn(List.of());
        assertTrue(controller.points(5L, null).isEmpty());
        assertTrue(controller.points(5L, "FACT_01").isEmpty(), "deviceId 优先于 factoryCode");
        verify(assets, times(2)).points(5L);
        assertTrue(controller.points(null, "FACT_01").isEmpty());
        verify(assets, times(1)).pointsByFactory("FACT_01");
        assertTrue(controller.points(5L).isEmpty(), "保留单参 Java 兼容方法");

        var ex = assertThrows(ResponseStatusException.class, () -> controller.points(null, "   "));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertThrows(ResponseStatusException.class, () -> controller.points(null, null));
    }

    // ==================== 测试夹具 ====================

    private static RotatingVibrationPlugin vibrationPlugin() {
        DeviceTypeService deviceTypes = mock(DeviceTypeService.class);
        DeviceTypeEntity general = mock(DeviceTypeEntity.class);
        when(general.getId()).thenReturn(1L);
        when(general.getTypeCode()).thenReturn("GENERAL");
        when(general.getTypeName()).thenReturn("通用设备");
        when(deviceTypes.general()).thenReturn(general);
        DiagnosticEvaluator evaluator = new DiagnosticEvaluator(mock(DiagnosticRuleRepository.class), deviceTypes);
        return new RotatingVibrationPlugin(new HealthIndexCalculator(), evaluator, new DegradationTracker(Duration.ofHours(24)));
    }

    private static PointEntity rotatingPoint(Long id, String isoClass, short deviceStatus) {
        FactoryEntity factory = new FactoryEntity("FACT_01", "一号车间");
        ReflectionTestUtils.setField(factory, "id", 1L);
        DeviceTypeEntity type = mock(DeviceTypeEntity.class);
        when(type.getId()).thenReturn(1L);
        when(type.getTypeCode()).thenReturn("GENERAL");
        when(type.getTypeName()).thenReturn("通用设备");
        DeviceEntity device = new DeviceEntity(factory, "PUMP_01", "循环水泵", new BigDecimal("1500.00"),
                new BigDecimal("55.00"), type, isoClass);
        ReflectionTestUtils.setField(device, "id", 10L);
        device.update("循环水泵", new BigDecimal("1500.00"), new BigDecimal("55.00"), deviceStatus, type, isoClass);
        PointEntity point = new PointEntity(device, "DE", "驱动端", null);
        ReflectionTestUtils.setField(point, "id", id);
        point.setModalityType(ModalityTypes.ROTATING_VIB);
        return point;
    }

    private static BaselineSnapshot activeVibBaseline(Long pointId) {
        return new BaselineSnapshot(pointId, ModalityTypes.ROTATING_VIB, "ACTIVE", 200, T0, null,
                Map.of(VibrationContractAdapter.FREQ_1X_AMP, 1.0,
                        VibrationContractAdapter.FREQ_2X_AMP, 0.2,
                        VibrationContractAdapter.V_RMS, 1.0,
                        VibrationContractAdapter.TEMPERATURE, 45.0),
                Map.of());
    }

    private static TelemetryEnvelope vibEnvelope(long ts, double f1x, double f2x, double vRms, Double temp, Double rpm) {
        Map<String, Double> features = new LinkedHashMap<>();
        features.put(VibrationContractAdapter.V_RMS, vRms);
        features.put(VibrationContractAdapter.A_PEAK, vRms * 2.0);
        features.put(VibrationContractAdapter.A_KURTOSIS, 3.0);
        features.put(VibrationContractAdapter.FREQ_1X_AMP, f1x);
        features.put(VibrationContractAdapter.FREQ_2X_AMP, f2x);
        if (temp != null) features.put(VibrationContractAdapter.TEMPERATURE, temp);
        if (rpm != null) features.put(VibrationContractAdapter.RPM, rpm);
        return new TelemetryEnvelope(2, ModalityTypes.ROTATING_VIB, ts, "FACT_01", "PUMP_01", "DE", 0,
                new OperatingCondition(rpm, null, true), features, null, null);
    }

    private static TelemetryEnvelope mcsaSample(long ts, double sidebandDb, double carrierDb) {
        return new TelemetryEnvelope(2, ModalityTypes.MCSA, ts, "FACT_01", "MOTOR_01", "CURRENT", 0,
                new OperatingCondition(1485.0, 75.0, true),
                Map.of("mains_freq_hz", 50.0, "slip_pct", 1.0, "load_pct", 75.0,
                        "sideband_amp_db", sidebandDb, "carrier_amp_db", carrierDb),
                null, null);
    }
}
