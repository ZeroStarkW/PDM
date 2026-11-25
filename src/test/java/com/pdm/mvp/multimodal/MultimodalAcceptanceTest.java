package com.pdm.mvp.multimodal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pdm.mvp.alert.DeviceHealthWindow;
import com.pdm.mvp.analytics.BaselineFeatureStateEntity;
import com.pdm.mvp.analytics.BaselineFeatureStateRepository;
import com.pdm.mvp.analytics.BaselineSampleRepository;
import com.pdm.mvp.analytics.BaselineService;
import com.pdm.mvp.analytics.DegradationTracker;
import com.pdm.mvp.analytics.HealthIndexCalculator;
import com.pdm.mvp.analytics.ModalityBaselineSampleRepository;
import com.pdm.mvp.analytics.RotatingVibrationPlugin;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.DeviceRepository;
import com.pdm.mvp.asset.DeviceTypeEntity;
import com.pdm.mvp.asset.DeviceTypeService;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.controlvalve.ControlValvePlugin;
import com.pdm.mvp.controlvalve.ControlValveProperties;
import com.pdm.mvp.diagnostics.DiagnosticEvaluator;
import com.pdm.mvp.diagnostics.DiagnosticRuleRepository;
import com.pdm.mvp.dsp.BandRms;
import com.pdm.mvp.dsp.HilbertEnvelope;
import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.modality.DiagnosticMicrokernel;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.switchgear.CircuitBreakerPlugin;
import com.pdm.mvp.switchgear.SwitchgearProperties;
import com.pdm.mvp.telemetry.OperatingCondition;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 多模态异构数据端到端验收（设计说明书 §7 DoD）：
 * TC-MM-01 旋转振动包络解调、TC-MM-02 断路器事件硬限直通与 M-of-N 防抖、
 * TC-MM-03 调节阀死区分级评分、TC-MM-04 微内核 MAD 清洗与断路器学习池锁定。
 */
class MultimodalAcceptanceTest {
    private static final long NOW = 1_780_000_000_000L;
    private static final long HOUR_MS = Duration.ofHours(1).toMillis();
    private static final long DAY_MS = Duration.ofDays(1).toMillis();

    private final DiagnosticMicrokernel kernel = new DiagnosticMicrokernel(new DeviceHealthWindow());

    /** TC-MM-01：1X 与总 RMS 正常，高频共振区注入 140Hz 调制冲击 —— 包络解调提取 env_bpfo，
     *  共享 Sigmoid 曲线在 k 交点处把 HI 准确扣至 55 分，归因锁定“轴承外圈点蚀”。 */
    @Test
    void tcMM01_rotatingVibrationEnvelopeSpectrumScoresAttentionWithBearingAttribution() {
        int n = 8192;
        double fs = 8192.0;
        double f1 = 25.0, bpfo = 140.0, carrier = 2000.0;
        double[] signal = new double[n];
        Random random = new Random(42L);
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            signal[i] = 0.40 * Math.sin(2 * Math.PI * f1 * t)
                    + 0.08 * Math.sin(2 * Math.PI * 2 * f1 * t)
                    + 0.02 * random.nextGaussian();
        }
        // 每个 BPFO 周期注入一次指数衰减共振冲击（经典外圈点蚀模型）
        double shockInterval = fs / bpfo;
        for (int k = 0; shockInterval * k < n - 64; k++) {
            int start = (int) Math.round(shockInterval * k);
            for (int j = 0; j < 64 && start + j < n; j++) {
                double tau = j / fs;
                signal[start + j] += 0.30 * Math.exp(-160.0 * tau) * Math.sin(2 * Math.PI * carrier * tau);
            }
        }
        var envelopeSpectrum = HilbertEnvelope.envelopeSpectrum(signal, fs, 1000.0, 2500.0);
        double envBpfo = BandRms.of(envelopeSpectrum.frequencies(), envelopeSpectrum.amplitudes(), bpfo);
        double floor = noiseFloor(envelopeSpectrum, 30.0, 500.0, bpfo - 15.0, bpfo + 15.0);
        assertTrue(envBpfo / Math.max(1e-9, floor) > 5.0,
                "包络谱 140Hz 特征尖峰不显著: SNR=" + envBpfo / Math.max(1e-9, floor));

        // 共享 Sigmoid 曲线上恰好等于 55 分的恶化倍率 k
        double kAtScore55 = 1.0 + 1.5 * Math.pow(100.0 / 55.0 - 1.0, 1.0 / 2.2);
        double baselineBpfo = envBpfo / kAtScore55;

        var plugin = vibrationPlugin();
        var baseline = new BaselineSnapshot(1L, ModalityTypes.ROTATING_VIB, "ACTIVE", 200, Instant.now(), null,
                Map.of("freq_1x_amp", 0.40, "freq_2x_amp", 0.08, "v_rms", 1.0,
                        "envelope_bpfo_amp", baselineBpfo),
                Map.of("freq_1x_amp", 0.05));
        var sample = envelope(ModalityTypes.ROTATING_VIB, NOW, new OperatingCondition(1440.0, null, null),
                Map.of("v_rms", 1.0, "a_peak", 1.0, "a_kurtosis", 3.0,
                        "freq_1x_amp", 0.40, "freq_2x_amp", 0.08, "envelope_bpfo_amp", envBpfo));

        assertTrue(plugin.gate(sample).assessable(), "正常运行工况应可评估");
        var assessment = plugin.score(point(1L), sample, baseline, plugin.diagnose(point(1L), sample, baseline));
        assertEquals(55.0, assessment.healthIndex(), 0.5, "BPFO k 交点应把 HI 扣至 55 分: " + assessment.healthIndex());
        assertEquals("ATTENTION", assessment.level());
        assertTrue(assessment.diagnosis().factor().contains("轴承外圈点蚀"),
                "应锁定轴承外圈点蚀归因: " + assessment.diagnosis().factor());
    }

    /** TC-MM-02：分闸动作耗时由基线 40ms 突增至 52ms（滞后 30% > 20% 危险倍率）——
     *  scoreDuration=25，硬限击穿首次动作即直通报警，归因“分闸动作迟缓，机械传动卡涩”。 */
    @Test
    void tcMM02_switchgearHardLimitBreachTriggersAlarmOnFirstEvent() {
        var plugin = breakerPlugin();
        var baseline = new BaselineSnapshot(11L, ModalityTypes.SWITCHGEAR_EVENT, "ACTIVE", 205, Instant.now(), null,
                Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 5.0, "closing_velocity_mps", 2.15),
                Map.of());
        var sample = envelope(ModalityTypes.SWITCHGEAR_EVENT, NOW, null,
                Map.of("trip_duration_ms", 52.0, "peak_coil_current_a", 5.42, "closing_velocity_mps", 2.15));
        var point = point(11L);

        var result = plugin.processDiagnosis(sample.features(), baseline.featureBase(), Map.of());
        assertEquals(25.0, result.staticHealthScore(), 0.001, "滞后 30% 应落在危险档 25 分");
        assertTrue(result.isHardLimitBreached());
        assertEquals(25.0, result.contributions().stream()
                .filter(c -> "分闸时间".equals(c.feature())).findFirst().orElseThrow().score(), 0.001);

        var outcome = kernel.execute(point, 1L, plugin, sample, baseline);
        assertEquals(25.0, outcome.finalHealthScore(), 0.001);
        assertEquals("DANGER", outcome.alarmLevel());
        assertTrue(outcome.hardLimitBreached());
        assertTrue(outcome.shouldTriggerAlarm(), "硬限击穿必须绕过 M-of-N 防抖直通报警");
        assertTrue(outcome.defectAttribution().contains("分闸动作迟缓"));
        assertTrue(outcome.defectAttribution().contains("机械传动卡涩"));
    }

    /** TC-MM-02 M-of-N 变体：线圈电流 3.9A（比率 0.78 < 0.80 → 40 分）无硬限击穿，
     *  动作间隔一天（事件型不断档清窗）—— 前 4 次动作不触发，第 5 次满足 4-of-5 触发。 */
    @Test
    void tcMM02b_switchgearLowCurrentDebouncesAcrossDaysUntilFifthEvent() {
        var plugin = breakerPlugin();
        var baseline = new BaselineSnapshot(11L, ModalityTypes.SWITCHGEAR_EVENT, "ACTIVE", 205, Instant.now(), null,
                Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 5.0, "closing_velocity_mps", 2.15),
                Map.of());
        var point = point(11L);
        long t0 = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
        for (int i = 0; i < 4; i++) {
            var sample = envelope(ModalityTypes.SWITCHGEAR_EVENT, t0 + i * DAY_MS, null,
                    Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 3.9));
            var outcome = kernel.execute(point, 1L, plugin, sample, baseline);
            assertEquals(40.0, outcome.finalHealthScore(), 0.001);
            assertFalse(outcome.hardLimitBreached());
            assertFalse(outcome.shouldTriggerAlarm(), "第 " + (i + 1) + " 次动作不应触发告警");
        }
        var fifth = envelope(ModalityTypes.SWITCHGEAR_EVENT, t0 + 4 * DAY_MS, null,
                Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 3.9));
        var outcome = kernel.execute(point, 1L, plugin, fifth, baseline);
        assertTrue(outcome.shouldTriggerAlarm(), "第 5 次动作应满足 4-of-5 防抖触发");
    }

    /** TC-MM-03：开度 CV 与反馈 FB 之间死区由基线 1.0% 扩大至 3.5%（k=3.5 落在关注档）——
     *  分级倍率评分扣至 40 分 WARNING，归因提示“填料压盖过紧或阀杆结垢卡阻”。 */
    @Test
    void tcMM03_controlValveDeadbandExpansionScoresWarning() {
        var plugin = valvePlugin();
        var baseline = new BaselineSnapshot(12L, ModalityTypes.CONTROL_VALVE_PROCESS, "ACTIVE", 200, Instant.now(), null,
                Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04), Map.of());
        var sample = envelope(ModalityTypes.CONTROL_VALVE_PROCESS, NOW,
                new OperatingCondition(null, null, true),
                Map.of("hysteresis_deadband_pct", 3.5, "ultrasonic_leak_rms", 0.04));

        var outcome = kernel.execute(point(12L), 1L, plugin, sample, baseline);
        assertEquals(40.0, outcome.finalHealthScore(), 0.001, "k=3.5 应按分级倍率扣至 40 分");
        assertEquals("WARNING", outcome.alarmLevel());
        assertFalse(outcome.hardLimitBreached());
        assertTrue(outcome.defectAttribution().contains("填料压盖过紧或阀杆结垢卡阻"));
    }

    /** TC-MM-04：205 条分闸耗时学习池注入 5 条 200ms 飞刺 —— MAD 精确剔除全部离群点，
     *  P50 基线误差 ≤1%，CV ≤0.10 并以 pooled 策略锁定 ACTIVE。 */
    @Test
    void tcMM04_microkernelMadCleaningLocksBreakerLearningPool() {
        double[] pool = breakerPool();

        var stats = BaselineService.featureStatistics(Map.of("trip_duration_ms", pool));
        assertEquals(200, stats.cleaned().get("trip_duration_ms").length, "5 条飞刺必须被精确剔除");
        assertTrue(stats.cvByCode().get("trip_duration_ms") <= 0.10, "清洗后 CV 必须达标锁定");

        PointEntity point = realSwitchgearPoint();
        List<BaselineFeatureStateEntity> featureStateStore = new ArrayList<>();
        var featureStates = mock(BaselineFeatureStateRepository.class);
        when(featureStates.findByPoint_IdAndModalityTypeAndFeatureCode(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(java.util.Optional.empty());
        when(featureStates.findByPoint_IdAndModalityType(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        when(featureStates.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> { featureStateStore.add(inv.getArgument(0)); return inv.getArgument(0); });

        var modalitySamples = mock(ModalityBaselineSampleRepository.class);
        List<com.pdm.mvp.analytics.ModalityBaselineSampleEntity> sampleStore = new ArrayList<>();
        when(modalitySamples.existsByPoint_IdAndModalityTypeAndSampledAt(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> sampleStore.stream().anyMatch(s -> s.getSampledAt().equals(inv.getArgument(2))));
        when(modalitySamples.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> { sampleStore.add(inv.getArgument(0)); return inv.getArgument(0); });
        when(modalitySamples.findByPoint_IdAndModalityTypeAndSampledAtBetweenOrderBySampledAtAsc(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> sampleStore.stream()
                        .filter(s -> !s.getSampledAt().isBefore(inv.getArgument(2))
                                && !s.getSampledAt().isAfter(inv.getArgument(3))).toList());

        var service = new BaselineService(mock(PointRepository.class), mock(DeviceRepository.class),
                mock(BaselineSampleRepository.class), featureStates, modalitySamples, mock(com.pdm.mvp.analytics.SpectrumBaselineService.class), Duration.ofMinutes(5));

        long t0 = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
        BaselineSnapshot snapshot = null;
        for (int i = 0; i < pool.length; i++) {
            snapshot = service.observe(point, envelope(ModalityTypes.SWITCHGEAR_EVENT, t0 + i * HOUR_MS,
                    new OperatingCondition(null, null, true), Map.of("trip_duration_ms", pool[i])), breakerPlugin());
        }

        ArgumentCaptor<BaselineFeatureStateEntity> captor = ArgumentCaptor.forClass(BaselineFeatureStateEntity.class);
        verify(featureStates, atLeastOnce()).save(captor.capture());
        var state = captor.getAllValues().stream()
                .filter(s -> "trip_duration_ms".equals(s.getFeatureCode())).findFirst().orElseThrow();
        assertEquals(40.0, state.getP50(), 0.4, "P50 基线误差必须 ≤1%: " + state.getP50());
        assertTrue(state.getCv() != null && state.getCv() <= 0.10, "锁定 CV 必须 ≤0.10");
        assertEquals(BaselineService.ACTIVE, state.getStatus());
        assertEquals("ACTIVE", snapshot.status(), "学习池应锁定为 ACTIVE");
    }

    /** TC-MM-01 对照组：同一随机种子、同一 DSP 链路（带通 → 希尔伯特包络 → 汉宁窗幅值谱 → BandRms）下的无冲击信号
     *  —— 包络谱 140Hz 处没有特征峰，对“让故障信号恰得 55 分”的同一基线评满分；
     *  故障信号的包络谱峰值谱线必须落在 140Hz（BPFO）上。 */
    @Test
    void tcMM01_controlSignalScoresFullAndFaultEnvelopePeakBinIsAtBpfo() {
        int n = 8192;
        double fs = 8192.0, bpfo = 140.0;
        var controlSpectrum = HilbertEnvelope.envelopeSpectrum(signal(false, n, fs), fs, 1000.0, 2500.0);
        var faultSpectrum = HilbertEnvelope.envelopeSpectrum(signal(true, n, fs), fs, 1000.0, 2500.0);
        double controlBpfo = BandRms.of(controlSpectrum.frequencies(), controlSpectrum.amplitudes(), bpfo);
        double faultBpfo = BandRms.of(faultSpectrum.frequencies(), faultSpectrum.amplitudes(), bpfo);
        double controlSnr = controlBpfo / Math.max(1e-9, noiseFloor(controlSpectrum, 30.0, 500.0, bpfo - 15.0, bpfo + 15.0));
        double faultSnr = faultBpfo / Math.max(1e-9, noiseFloor(faultSpectrum, 30.0, 500.0, bpfo - 15.0, bpfo + 15.0));

        // 故障信号：包络谱 30–500Hz 内最大谱线即 BPFO
        double[] frequencies = faultSpectrum.frequencies();
        double[] amplitudes = faultSpectrum.amplitudes();
        int peakBin = -1;
        for (int i = 0; i < frequencies.length; i++)
            if (frequencies[i] >= 30.0 && frequencies[i] <= 500.0 && (peakBin < 0 || amplitudes[i] > amplitudes[peakBin]))
                peakBin = i;
        assertTrue(peakBin >= 0);
        assertEquals(bpfo, frequencies[peakBin], 0.5 * fs / n, "包络谱峰值谱线应在 140Hz（BPFO）: " + frequencies[peakBin]);
        assertTrue(faultSnr > 3.0 * controlSnr,
                "故障信号 140Hz 信噪比应远高于对照组: 故障=" + faultSnr + " 对照=" + controlSnr);
        assertTrue(faultBpfo > 2.5 * controlBpfo, "故障信号 BPFO 频带能量应远高于对照组: "
                + faultBpfo + " vs " + controlBpfo);

        // 同一基线：让故障信号在共享 Sigmoid 曲线上恰得 55 分
        double kAtScore55 = 1.0 + 1.5 * Math.pow(100.0 / 55.0 - 1.0, 1.0 / 2.2);
        var plugin = vibrationPlugin();
        var baseline = new BaselineSnapshot(1L, ModalityTypes.ROTATING_VIB, "ACTIVE", 200, Instant.now(), null,
                Map.of("freq_1x_amp", 0.40, "freq_2x_amp", 0.08, "v_rms", 1.0, "envelope_bpfo_amp", faultBpfo / kAtScore55),
                Map.of("freq_1x_amp", 0.05));
        var controlSample = envelope(ModalityTypes.ROTATING_VIB, NOW, new OperatingCondition(1440.0, null, null),
                Map.of("v_rms", 1.0, "a_kurtosis", 3.0, "freq_1x_amp", 0.40, "freq_2x_amp", 0.08,
                        "envelope_bpfo_amp", controlBpfo));
        var controlAssessment = plugin.score(point(1L), controlSample, baseline,
                plugin.diagnose(point(1L), controlSample, baseline));
        assertEquals(100.0, controlAssessment.healthIndex(), 0.5, "对照组经同一链路与同一基线应评满分: "
                + controlAssessment.healthIndex());
        assertEquals("HEALTHY", controlAssessment.level());
        assertFalse(controlAssessment.diagnosis().factor().contains("轴承外圈点蚀"), "对照组不得被误判为轴承外圈点蚀");
    }

    /** TC-MM-04 边界：手造 MAD 边界值钉住清洗限 4.4478·MAD —— 中位数 10、MAD=2（上限 8.8956），
     *  距中位数 4.4·MAD（8.8）的样本被保留，4.5·MAD（9.0）的样本被剔除。 */
    @Test
    void tcMM04_madCleaningKeeps44MadAndRemoves45MadFromTheMedian() {
        assertEquals(4.4478, 3.0 * 1.4826, 1e-4, "文档 §4.1 的清洗限 4.4478·MAD");

        var kept = BaselineService.featureStatistics(Map.of("trip_duration_ms", poolWithOutliers(8.8)));
        var removed = BaselineService.featureStatistics(Map.of("trip_duration_ms", poolWithOutliers(9.0)));

        assertEquals(202, kept.cleaned().get("trip_duration_ms").length, "4.4·MAD 在清洗限内，两条边界样本都必须保留");
        assertEquals(200, removed.cleaned().get("trip_duration_ms").length, "4.5·MAD 超出清洗限，两条边界样本都必须剔除");
        assertTrue(removed.cvByCode().get("trip_duration_ms") < kept.cvByCode().get("trip_duration_ms"));
    }

    // ---------- 测试装配 ----------

    /** 合成旋转振动信号：25Hz 基波 + 50Hz 谐波 + 高斯噪声（种子 42）；withShocks 时按 BPFO 周期叠加指数衰减共振冲击。 */
    private static double[] signal(boolean withShocks, int n, double fs) {
        double f1 = 25.0, bpfo = 140.0, carrier = 2000.0;
        double[] signal = new double[n];
        Random random = new Random(42L);
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            signal[i] = 0.40 * Math.sin(2 * Math.PI * f1 * t)
                    + 0.08 * Math.sin(2 * Math.PI * 2 * f1 * t)
                    + 0.02 * random.nextGaussian();
        }
        if (!withShocks) return signal;
        double shockInterval = fs / bpfo;
        for (int k = 0; shockInterval * k < n - 64; k++) {
            int start = (int) Math.round(shockInterval * k);
            for (int j = 0; j < 64 && start + j < n; j++) {
                double tau = j / fs;
                signal[start + j] += 0.30 * Math.exp(-160.0 * tau) * Math.sin(2 * Math.PI * carrier * tau);
            }
        }
        return signal;
    }

    /** 学习池：100 条 8ms + 100 条 12ms（中位数 10、MAD=2），外加关于中位数对称、偏离 deviation 的两条样本。 */
    private static double[] poolWithOutliers(double deviation) {
        double[] pool = new double[202];
        java.util.Arrays.fill(pool, 0, 100, 8.0);
        java.util.Arrays.fill(pool, 100, 200, 12.0);
        pool[200] = 10.0 - deviation;
        pool[201] = 10.0 + deviation;
        return pool;
    }
    private RotatingVibrationPlugin vibrationPlugin() {
        DeviceTypeService deviceTypes = mock(DeviceTypeService.class);
        var general = mock(DeviceTypeEntity.class);
        when(general.getId()).thenReturn(1L);
        when(general.getTypeName()).thenReturn("通用设备");
        when(deviceTypes.general()).thenReturn(general);
        return new RotatingVibrationPlugin(new HealthIndexCalculator(),
                new DiagnosticEvaluator(mock(DiagnosticRuleRepository.class), deviceTypes),
                new DegradationTracker(Duration.ofHours(24)));
    }

    private CircuitBreakerPlugin breakerPlugin() {
        return new CircuitBreakerPlugin(new SwitchgearProperties(true, 1.10, 1.20, 0.80,
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)));
    }

    private ControlValvePlugin valvePlugin() {
        return new ControlValvePlugin(new ControlValveProperties(true, 2.5, 5.0, 0.2, 0.5, Duration.ofHours(1)), Duration.ofHours(24));
    }

    private PointEntity point(long id) {
        PointEntity point = mock(PointEntity.class);
        when(point.getId()).thenReturn(id);
        var device = mock(DeviceEntity.class);
        when(point.getDevice()).thenReturn(device);
        when(device.getDeviceType()).thenReturn(null);
        return point;
    }

    /** 真实测点实体：走完整基线状态机（LEARNING → ACTIVE），不依赖 mock 状态。 */
    private PointEntity realSwitchgearPoint() {
        var factory = new FactoryEntity("FACT_01", "一号工厂");
        var device = new DeviceEntity(factory, "BKR_10KV_02", "10kV进线开关", null, null, null);
        PointEntity point = new PointEntity(device, "TRIP_UNIT", "分闸特性", null);
        point.setModalityType(ModalityTypes.SWITCHGEAR_EVENT);
        return point;
    }

    /** 205 条样本：200 条 ~40ms 正常值（种子随机 σ≈0.3，限幅在 MAD 清洗窗内）+ 5 条 200ms 飞刺。 */
    private static double[] breakerPool() {
        Random random = new Random(20261123L);
        double[] values = new double[205];
        for (int i = 0; i < values.length; i++) {
            values[i] = 40.0 + Math.clamp(random.nextGaussian() * 0.3, -0.6, 0.6);
        }
        for (int index : new int[]{10, 50, 90, 130, 170}) values[index] = 200.0;
        return values;
    }

    private TelemetryEnvelope envelope(String modalityType, long timestamp, OperatingCondition condition,
            Map<String, Double> features) {
        return new TelemetryEnvelope(ModalityTypes.SUPPORTED_CONTRACT_VERSION, modalityType, timestamp,
                "FACT_01", "BKR_10KV_02", "TRIP_UNIT", 0, condition, new LinkedHashMap<>(features), null, null);
    }

    /** 包络谱噪底：频带内幅值中位数（排除目标特征 ±15Hz 邻域）。 */
    private static double noiseFloor(com.pdm.mvp.dsp.Fft.Spectrum spectrum, double lowHz, double highHz,
            double excludeLowHz, double excludeHighHz) {
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < spectrum.frequencies().length; i++) {
            double freq = spectrum.frequencies()[i];
            if (freq < lowHz || freq > highHz) continue;
            if (freq >= excludeLowHz && freq <= excludeHighHz) continue;
            values.add(spectrum.amplitudes()[i]);
        }
        values.sort(Double::compare);
        return values.isEmpty() ? 0.0 : values.get(values.size() / 2);
    }
}
