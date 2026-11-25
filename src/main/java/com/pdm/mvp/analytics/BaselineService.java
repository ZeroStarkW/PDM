package com.pdm.mvp.analytics;

import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.asset.DeviceRepository;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.modality.MechanismPluginStrategy;
import com.pdm.mvp.modality.ModalityPlugin;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import com.pdm.mvp.telemetry.TelemetryRequest;
import com.pdm.mvp.telemetry.VibrationContractAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 动态基线：共享学习内核（滚动窗口、MAD 清洗、中位数/CV、锁定与发散）。
 * 振动模态沿用旧表与旧列（迁移期保留旧数据读取能力）并双写特征状态行；
 * 其他模态按测点 + 模态 + 特征编码独立学习。
 */
@Service
public class BaselineService {
    public static final short LEARNING = 0;
    public static final short ACTIVE = 1;
    public static final short PENDING_RESET = 2;
    public static final short DIVERGED = 3;
    public static final int MINIMUM_LEARNING_SAMPLES = 200;
    static final double CV_LOCK_THRESHOLD = 0.15;
    private final PointRepository points;
    private final DeviceRepository devices;
    private final BaselineSampleRepository samples;
    private final BaselineFeatureStateRepository featureStates;
    private final ModalityBaselineSampleRepository modalitySamples;
    private final SpectrumBaselineService spectrumBaseline;
    private final Duration learningDuration;

    public BaselineService(PointRepository points, DeviceRepository devices, BaselineSampleRepository samples,
            BaselineFeatureStateRepository featureStates, ModalityBaselineSampleRepository modalitySamples,
            SpectrumBaselineService spectrumBaseline,
            @Value("${pdm.baseline.learning-duration:PT5M}") Duration learningDuration) {
        if (learningDuration.isZero() || learningDuration.isNegative())
            throw new IllegalArgumentException("pdm.baseline.learning-duration must be positive");
        this.points = points; this.devices = devices; this.samples = samples;
        this.featureStates = featureStates; this.modalitySamples = modalitySamples;
        this.spectrumBaseline = spectrumBaseline;
        this.learningDuration = learningDuration;
    }

    /** 共享内核入口：按插件模态分发（振动走旧路径，其他模态走特征编码路径）；非默认工况箱按箱独立学习。 */
    @Transactional
    public BaselineSnapshot observe(PointEntity point, TelemetryEnvelope sample, ModalityPlugin plugin) {
        if (ModalityTypes.ROTATING_VIB.equals(plugin.modalityType())) {
            short binIndex = plugin.resolveBinIndex(point, sample);
            if (binIndex != 0) return observeVibrationBin(point, sample, binIndex);
            BaselineState legacy = observeVibration(point, VibrationContractAdapter.toRequest(sample), sample);
            return snapshotFromPoint(point, legacy.warning());
        }
        return observeFeatureBased(point, sample, plugin);
    }

    /** 振动模态：保持旧行为等价，锁定/发散时双写特征状态行；协议 2.0.0 扩展特征按特征编码路径学习。 */
    @Transactional
    public BaselineState observeVibration(PointEntity point, TelemetryRequest sample, TelemetryEnvelope envelope) {
        var metrics = sample.telemetry();
        // 多工况分箱：非额定转速段的变频稳态样本不再丢弃，转入按箱独立学习（额定箱行为零变化）
        short binIndex = OperatingRegimeBinner.resolveVibrationBin(point, metrics.rpm());
        if (binIndex != 0) {
            observeVibrationBin(point, envelope, binIndex);
            return state(point, null);
        }
        if (point.getBaselineStatus() != LEARNING && point.getBaselineStatus() != DIVERGED) return state(point, null);
        Double rpm = metrics.rpm();
        double nominalRpm = point.getDevice() != null && point.getDevice().getRpmNominal() != null
                ? point.getDevice().getRpmNominal().doubleValue() : 0.0;
        if (rpm != null && (nominalRpm <= 0 || Math.abs(rpm - nominalRpm) / nominalRpm > 0.05))
            return state(point, null);
        Instant sampledAt = Instant.ofEpochMilli(sample.timestamp());
        if (samples.existsByPoint_IdAndSampledAt(point.getId(), sampledAt)) return state(point, null);
        samples.save(new BaselineSampleEntity(point, sampledAt, rpm, metrics.freq1xAmp(), metrics.vRms(), metrics.aPeak(),
                metrics.freq2xAmp(), metrics.envelopeBpfoAmp(), metrics.aKurtosis(), metrics.temperature()));
        // 协议 2.0.0 扩展特征（BPFI/温升/3X/0.44X/sei/crest）随样本双写到 v2 特征样本表
        Map<String, Double> extended = extendedFeatureValues(envelope);
        if (!extended.isEmpty() && !modalitySamples.existsByPoint_IdAndModalityTypeAndSampledAt(
                point.getId(), ModalityTypes.ROTATING_VIB, sampledAt)) {
            modalitySamples.save(new ModalityBaselineSampleEntity(point, ModalityTypes.ROTATING_VIB, sampledAt,
                    new LinkedHashMap<>(extended)));
        }
        Instant cutoff = sampledAt.minus(learningDuration);
        samples.deleteByPoint_IdAndSampledAtBefore(point.getId(), cutoff);
        modalitySamples.deleteByPoint_IdAndModalityTypeAndSampledAtBefore(point.getId(), ModalityTypes.ROTATING_VIB, cutoff);
        List<BaselineSampleEntity> all = samples.findByPoint_IdAndSampledAtBetweenOrderBySampledAtAsc(
                point.getId(), cutoff, sampledAt).stream().filter(s -> s.getBinIndex() == 0).toList();
        point.setLearnedSamples(all.size());
        if (all.size() < MINIMUM_LEARNING_SAMPLES || !spansLearningWindow(all)) {
            point.setBaselineStatus(LEARNING);
            points.save(point);
            return state(point, null);
        }

        double[] oneX = all.stream().mapToDouble(BaselineSampleEntity::getFreq1xAmp).toArray();
        double[] twoX = all.stream().mapToDouble(BaselineSampleEntity::getFreq2xAmp).toArray();
        double[] velocity = all.stream().mapToDouble(BaselineSampleEntity::getVRms).toArray();
        double[] oneXClean = madClean(oneX);
        double[] twoXClean = madClean(twoX);
        double[] velocityClean = madClean(velocity);
        double[] bpfo = madClean(all.stream().map(BaselineSampleEntity::getEnvelopeBpfoAmp)
                .filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).toArray());
        double[] temperatures = madClean(all.stream().map(BaselineSampleEntity::getTemperature).filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).toArray());
        double cv1x = coefficientOfVariation(oneXClean);
        double cv2x = coefficientOfVariation(twoXClean);
        double cvVelocity = coefficientOfVariation(velocityClean);
        double cv = Math.max(cv1x, Math.max(cv2x, cvVelocity));
        if (bpfo.length > 1) cv = Math.max(cv, coefficientOfVariation(bpfo));
        if (temperatures.length > 1) cv = Math.max(cv, coefficientOfVariation(temperatures));
        double cvBpfo = bpfo.length > 1 ? coefficientOfVariation(bpfo) : Double.NaN;
        double cvTemperature = temperatures.length > 1 ? coefficientOfVariation(temperatures) : Double.NaN;
        // a_peak 可选（协议 2.1.0 样本可缺省）：仅统计窗口内非空的峰值，全空时不写基线与特征状态
        double[] peaks = madClean(all.stream().map(BaselineSampleEntity::getAPeak).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).toArray());
        // 扩展特征独立 MAD 清洗 + CV，参与锁定门槛判定
        List<ModalityBaselineSampleEntity> extendedWindow = modalitySamples
                .findByPoint_IdAndModalityTypeAndSampledAtBetweenOrderBySampledAtAsc(
                        point.getId(), ModalityTypes.ROTATING_VIB, cutoff, sampledAt)
                .stream().filter(s -> s.getBinIndex() == 0).toList();
        FeatureStatistics extendedStats = featureStatistics(rawByFeature(extendedWindow,
                new LinkedHashSet<>(VibrationContractAdapter.EXTENDED_CODES)));
        for (Double extendedCv : extendedStats.cvByCode().values()) {
            if (extendedCv != null && Double.isFinite(extendedCv)) cv = Math.max(cv, extendedCv);
        }
        point.setBaselineCv(cv1x);
        short resultingStatus = cv <= CV_LOCK_THRESHOLD ? ACTIVE : DIVERGED;
        Instant lockedAt = null;
        if (resultingStatus == ACTIVE) {
            point.setFreq1xBase(median(oneXClean));
            point.setFreq2xBase(median(twoXClean));
            point.setVBaseRms(median(velocityClean));
            if (bpfo.length > 1) point.setEnvelopeBpfoBase(median(bpfo));
            if (peaks.length > 0) point.setABasePeak(median(peaks));
            if (temperatures.length > 0) point.setTempBase(median(temperatures));
            lockedAt = Instant.now();
            point.setBaselineLockedAt(lockedAt);
        }
        point.setBaselineStatus(resultingStatus);
        points.save(point);
        String warning = resultingStatus == DIVERGED ? "BASELINE_DIVERGENCE" : null;
        // 双写：旧列为准的同时，把按特征编码的状态落到新表（迁移期旧数据读取能力不变）
        Map<String, double[]> cleaned = new LinkedHashMap<>();
        cleaned.put(VibrationContractAdapter.FREQ_1X_AMP, oneXClean);
        cleaned.put(VibrationContractAdapter.FREQ_2X_AMP, twoXClean);
        cleaned.put(VibrationContractAdapter.V_RMS, velocityClean);
        if (bpfo.length > 1) cleaned.put(VibrationContractAdapter.ENVELOPE_BPFO_AMP, bpfo);
        if (peaks.length > 0) cleaned.put(VibrationContractAdapter.A_PEAK, peaks);
        if (temperatures.length > 0) cleaned.put(VibrationContractAdapter.TEMPERATURE, temperatures);
        cleaned.putAll(extendedStats.cleaned());
        Map<String, Double> cvByCode = new LinkedHashMap<>();
        cvByCode.put(VibrationContractAdapter.FREQ_1X_AMP, cv1x);
        cvByCode.put(VibrationContractAdapter.FREQ_2X_AMP, cv2x);
        cvByCode.put(VibrationContractAdapter.V_RMS, cvVelocity);
        if (!Double.isNaN(cvBpfo)) cvByCode.put(VibrationContractAdapter.ENVELOPE_BPFO_AMP, cvBpfo);
        if (!Double.isNaN(cvTemperature)) cvByCode.put(VibrationContractAdapter.TEMPERATURE, cvTemperature);
        cvByCode.putAll(extendedStats.cvByCode());
        // 振动双写保持旧语义：特征状态行的 sample_count = 学习窗口样本数
        upsertFeatureStates(point, ModalityTypes.ROTATING_VIB, (short) 0, cleaned, cvByCode, code -> all.size(),
                resultingStatus, lockedAt);
        return state(point, warning);
    }

    /**
     * 变频旋转振动按工况箱独立学习：非额定转速段样本不再被丢弃，也不与额定箱（bin 0）互污染。
     * 每个 (point_id, ROTATING_VIB, bin_index) 独立积累窗口、独立 MAD 清洗与 CV 锁定；
     * 已锁定箱直接用自己的 P50 组装快照对口评分，绝不拿额定箱旧列或其他箱基线张冠李戴。
     */
    private BaselineSnapshot observeVibrationBin(PointEntity point, TelemetryEnvelope envelope, short binIndex) {
        String modality = ModalityTypes.ROTATING_VIB;
        if (point.getBaselineStatus() == PENDING_RESET)
            return binSnapshot(point, modality, binIndex, "PENDING_RESET", binStates(point, modality, binIndex), null);
        List<BaselineFeatureStateEntity> rows = binStates(point, modality, binIndex);
        if (rows.stream().anyMatch(s -> s.getStatus() == ACTIVE))
            return binSnapshot(point, modality, binIndex, "ACTIVE", rows, null); // 该箱已锁定：不追加样本，直接对口返回
        var metrics = VibrationContractAdapter.toRequest(envelope).telemetry();
        Instant sampledAt = envelope.timestampInstant();
        if (samples.existsByPoint_IdAndBinIndexAndSampledAt(point.getId(), binIndex, sampledAt))
            return binSnapshot(point, modality, binIndex, "LEARNING", rows, null);
        samples.save(new BaselineSampleEntity(point, sampledAt, metrics.rpm(), metrics.freq1xAmp(), metrics.vRms(),
                metrics.aPeak(), metrics.freq2xAmp(), metrics.envelopeBpfoAmp(), metrics.aKurtosis(),
                metrics.temperature(), binIndex));
        Map<String, Double> extended = extendedFeatureValues(envelope);
        if (!extended.isEmpty() && !modalitySamples.existsByPoint_IdAndModalityTypeAndBinIndexAndSampledAt(
                point.getId(), modality, binIndex, sampledAt)) {
            modalitySamples.save(new ModalityBaselineSampleEntity(point, modality, sampledAt,
                    new LinkedHashMap<>(extended), binIndex));
        }
        Instant cutoff = sampledAt.minus(learningDuration);
        samples.deleteByPoint_IdAndBinIndexAndSampledAtBefore(point.getId(), binIndex, cutoff);
        modalitySamples.deleteByPoint_IdAndModalityTypeAndBinIndexAndSampledAtBefore(point.getId(), modality, binIndex, cutoff);
        List<BaselineSampleEntity> binWindow = samples.findByPoint_IdAndSampledAtBetweenOrderBySampledAtAsc(
                point.getId(), cutoff, sampledAt).stream().filter(s -> s.getBinIndex() == binIndex).toList();
        if (point.getBaselineStatus() == LEARNING) {
            point.setLearnedSamples(Math.max(point.getLearnedSamples(), binWindow.size()));
            points.save(point);
        }
        if (binWindow.size() < MINIMUM_LEARNING_SAMPLES || !spansLearningWindow(binWindow))
            // 本箱未学满：诚实返回 LEARNING，让 AnalyticsEngine 输出 BASELINE_LEARNING，不误用其他箱基线
            return binSnapshot(point, modality, binIndex, "LEARNING", binStates(point, modality, binIndex), null);

        double[] oneX = binWindow.stream().mapToDouble(BaselineSampleEntity::getFreq1xAmp).toArray();
        double[] twoX = binWindow.stream().mapToDouble(BaselineSampleEntity::getFreq2xAmp).toArray();
        double[] velocity = binWindow.stream().mapToDouble(BaselineSampleEntity::getVRms).toArray();
        double[] oneXClean = madClean(oneX);
        double[] twoXClean = madClean(twoX);
        double[] velocityClean = madClean(velocity);
        double[] bpfo = madClean(binWindow.stream().map(BaselineSampleEntity::getEnvelopeBpfoAmp)
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).toArray());
        double[] temperatures = madClean(binWindow.stream().map(BaselineSampleEntity::getTemperature)
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).toArray());
        double cv1x = coefficientOfVariation(oneXClean);
        double cv2x = coefficientOfVariation(twoXClean);
        double cvVelocity = coefficientOfVariation(velocityClean);
        double cv = Math.max(cv1x, Math.max(cv2x, cvVelocity));
        if (bpfo.length > 1) cv = Math.max(cv, coefficientOfVariation(bpfo));
        if (temperatures.length > 1) cv = Math.max(cv, coefficientOfVariation(temperatures));
        double cvBpfo = bpfo.length > 1 ? coefficientOfVariation(bpfo) : Double.NaN;
        double cvTemperature = temperatures.length > 1 ? coefficientOfVariation(temperatures) : Double.NaN;
        double[] peaks = madClean(binWindow.stream().map(BaselineSampleEntity::getAPeak).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).toArray());
        List<ModalityBaselineSampleEntity> extendedWindow = modalitySamples
                .findByPoint_IdAndModalityTypeAndSampledAtBetweenOrderBySampledAtAsc(point.getId(), modality, cutoff, sampledAt)
                .stream().filter(s -> s.getBinIndex() == binIndex).toList();
        FeatureStatistics extendedStats = featureStatistics(rawByFeature(extendedWindow,
                new LinkedHashSet<>(VibrationContractAdapter.EXTENDED_CODES)));
        for (Double extendedCv : extendedStats.cvByCode().values()) {
            if (extendedCv != null && Double.isFinite(extendedCv)) cv = Math.max(cv, extendedCv);
        }
        short resultingStatus = cv <= CV_LOCK_THRESHOLD ? ACTIVE : DIVERGED;
        Instant lockedAt = null;
        if (resultingStatus == ACTIVE) {
            lockedAt = Instant.now();
            // 纯非额定变频运行设备（从未跑过额定转速）：任一工况箱首次 ACTIVE 即提升测点总状态，
            // 使设备总览与两步重置对变频设备同样生效；不写 freq1xBase 等额定专属旧列，避免污染 bin 0
            if (point.getBaselineStatus() != ACTIVE) {
                point.setBaselineStatus(ACTIVE);
                point.setBaselineLockedAt(lockedAt);
                point.setLearnedSamples(binWindow.size());
                point.setBaselineCv(cv1x);
                points.save(point);
            }
        }
        Map<String, double[]> cleaned = new LinkedHashMap<>();
        cleaned.put(VibrationContractAdapter.FREQ_1X_AMP, oneXClean);
        cleaned.put(VibrationContractAdapter.FREQ_2X_AMP, twoXClean);
        cleaned.put(VibrationContractAdapter.V_RMS, velocityClean);
        if (bpfo.length > 1) cleaned.put(VibrationContractAdapter.ENVELOPE_BPFO_AMP, bpfo);
        if (peaks.length > 0) cleaned.put(VibrationContractAdapter.A_PEAK, peaks);
        if (temperatures.length > 0) cleaned.put(VibrationContractAdapter.TEMPERATURE, temperatures);
        cleaned.putAll(extendedStats.cleaned());
        Map<String, Double> cvByCode = new LinkedHashMap<>();
        cvByCode.put(VibrationContractAdapter.FREQ_1X_AMP, cv1x);
        cvByCode.put(VibrationContractAdapter.FREQ_2X_AMP, cv2x);
        cvByCode.put(VibrationContractAdapter.V_RMS, cvVelocity);
        if (!Double.isNaN(cvBpfo)) cvByCode.put(VibrationContractAdapter.ENVELOPE_BPFO_AMP, cvBpfo);
        if (!Double.isNaN(cvTemperature)) cvByCode.put(VibrationContractAdapter.TEMPERATURE, cvTemperature);
        cvByCode.putAll(extendedStats.cvByCode());
        upsertFeatureStates(point, modality, binIndex, cleaned, cvByCode, code -> binWindow.size(),
                resultingStatus, lockedAt);
        if (resultingStatus == DIVERGED)
            return binSnapshot(point, modality, binIndex, "DIVERGED", binStates(point, modality, binIndex),
                    "BASELINE_DIVERGENCE");
        return binSnapshot(point, modality, binIndex, "ACTIVE", binStates(point, modality, binIndex), null);
    }

    /** 该测点/模态/工况箱的全部特征状态行（一次取回按箱过滤，避免逐特征 N+1 与多箱并存时的单行查询歧义）。 */
    private List<BaselineFeatureStateEntity> binStates(PointEntity point, String modalityType, short binIndex) {
        return featureStates.findByPoint_IdAndModalityType(point.getId(), modalityType).stream()
                .filter(s -> s.getBinIndex() == binIndex).toList();
    }

    /** 由工况箱特征状态行组装对口快照：base/cv 只取本箱，样本数取箱内最大值，锁定时间取最早。 */
    private BaselineSnapshot binSnapshot(PointEntity point, String modality, short binIndex, String status,
            List<BaselineFeatureStateEntity> rows, String warning) {
        Map<String, Double> base = new LinkedHashMap<>();
        Map<String, Double> cvs = new LinkedHashMap<>();
        int sampleCount = 0;
        Instant lockedAt = null;
        for (BaselineFeatureStateEntity row : rows) {
            if (row.getP50() != null) base.putIfAbsent(row.getFeatureCode(), row.getP50());
            if (row.getCv() != null) cvs.putIfAbsent(row.getFeatureCode(), row.getCv());
            sampleCount = Math.max(sampleCount, row.getSampleCount());
            if (row.getLockedAt() != null && (lockedAt == null || row.getLockedAt().isBefore(lockedAt)))
                lockedAt = row.getLockedAt();
        }
        return new BaselineSnapshot(point.getId(), modality, status, sampleCount, lockedAt, warning, base, cvs, binIndex);
    }

    /** 从包络中提取协议 2.0.0 扩展特征（供特征编码路径学习）。 */
    static Map<String, Double> extendedFeatureValues(TelemetryEnvelope envelope) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (String code : VibrationContractAdapter.EXTENDED_CODES) {
            Double value = envelope.featureOrNull(code);
            if (value != null) values.put(code, value);
        }
        return values;
    }

    /** 非振动模态：按特征编码独立学习（pdm_baseline_sample_v2 + pdm_baseline_feature_state）。
     *  学习窗口与就绪判据由插件按模态声明：windowed 要求样本覆盖窗口，pooled 仅要求样本数达标（事件型稀疏模态）。 */
    private BaselineSnapshot observeFeatureBased(PointEntity point, TelemetryEnvelope sample, ModalityPlugin plugin) {
        String modality = plugin.modalityType();
        short binIndex = plugin.resolveBinIndex(point, sample);
        if (binIndex != 0) return observeFeatureBin(point, sample, plugin, modality, binIndex);
        if (point.getBaselineStatus() != LEARNING && point.getBaselineStatus() != DIVERGED)
            return featureSnapshot(point, modality, null);
        Map<String, Double> featureValues = plugin.baselineFeatureValues(sample);
        if (featureValues.isEmpty()) return featureSnapshot(point, modality, null);
        var policy = plugin.baselineLearning();
        Duration window = policy.window() != null ? policy.window() : learningDuration;
        Instant sampledAt = sample.timestampInstant();
        if (modalitySamples.existsByPoint_IdAndModalityTypeAndSampledAt(point.getId(), modality, sampledAt))
            return featureSnapshot(point, modality, null);
        modalitySamples.save(new ModalityBaselineSampleEntity(point, modality, sampledAt, new LinkedHashMap<>(featureValues)));
        Instant cutoff = sampledAt.minus(window);
        modalitySamples.deleteByPoint_IdAndModalityTypeAndSampledAtBefore(point.getId(), modality, cutoff);
        List<ModalityBaselineSampleEntity> all = modalitySamples
                .findByPoint_IdAndModalityTypeAndSampledAtBetweenOrderBySampledAtAsc(point.getId(), modality, cutoff, sampledAt)
                .stream().filter(s -> s.getBinIndex() == 0).toList();
        point.setLearnedSamples(all.size());
        boolean ready = all.size() >= MINIMUM_LEARNING_SAMPLES && (policy.pooled() || spansLearningWindow(
                all.getFirst().getSampledAt(), all.getLast().getSampledAt(), all.size(), window));
        if (!ready) {
            point.setBaselineStatus(LEARNING);
            points.save(point);
            return featureSnapshot(point, modality, null);
        }
        Map<String, double[]> raw = rawByFeature(all, plugin.baselineFeatureCodes());
        FeatureStatistics stats = featureStatistics(raw);
        double lockCv = lockCv(stats.cvByCode(), raw, all.size(), convergenceCodes(plugin));
        point.setBaselineCv(lockCv);
        short resultingStatus;
        Instant lockedAt = null;
        if (lockCv <= CV_LOCK_THRESHOLD) {
            resultingStatus = ACTIVE;
            lockedAt = Instant.now();
            point.setBaselineLockedAt(lockedAt);
        } else {
            resultingStatus = DIVERGED;
        }
        point.setBaselineStatus(resultingStatus);
        points.save(point);
        String warning = resultingStatus == DIVERGED ? "BASELINE_DIVERGENCE" : null;
        // 全部特征统计照常存储；sample_count 为该特征在窗口内的原始样本数（可选特征可少于窗口样本数）
        upsertFeatureStates(point, modality, (short) 0, stats.cleaned(), stats.cvByCode(), code -> raw.get(code).length,
                resultingStatus, lockedAt);
        return featureSnapshot(point, modality, warning);
    }

    /** 非振动模态按工况箱独立学习（如配置 load_bins 的 MCSA 负载段）：箱间互不污染、互不解锁。 */
    private BaselineSnapshot observeFeatureBin(PointEntity point, TelemetryEnvelope sample, ModalityPlugin plugin,
            String modality, short binIndex) {
        if (point.getBaselineStatus() == PENDING_RESET)
            return binSnapshot(point, modality, binIndex, "PENDING_RESET", binStates(point, modality, binIndex), null);
        List<BaselineFeatureStateEntity> rows = binStates(point, modality, binIndex);
        if (rows.stream().anyMatch(s -> s.getStatus() == ACTIVE))
            return binSnapshot(point, modality, binIndex, "ACTIVE", rows, null);
        Map<String, Double> featureValues = plugin.baselineFeatureValues(sample);
        if (featureValues.isEmpty()) return binSnapshot(point, modality, binIndex, "LEARNING", rows, null);
        var policy = plugin.baselineLearning();
        Duration window = policy.window() != null ? policy.window() : learningDuration;
        Instant sampledAt = sample.timestampInstant();
        if (modalitySamples.existsByPoint_IdAndModalityTypeAndBinIndexAndSampledAt(
                point.getId(), modality, binIndex, sampledAt))
            return binSnapshot(point, modality, binIndex, "LEARNING", rows, null);
        modalitySamples.save(new ModalityBaselineSampleEntity(point, modality, sampledAt,
                new LinkedHashMap<>(featureValues), binIndex));
        Instant cutoff = sampledAt.minus(window);
        modalitySamples.deleteByPoint_IdAndModalityTypeAndBinIndexAndSampledAtBefore(point.getId(), modality, binIndex, cutoff);
        List<ModalityBaselineSampleEntity> binWindow = modalitySamples
                .findByPoint_IdAndModalityTypeAndSampledAtBetweenOrderBySampledAtAsc(point.getId(), modality, cutoff, sampledAt)
                .stream().filter(s -> s.getBinIndex() == binIndex).toList();
        if (point.getBaselineStatus() == LEARNING) {
            point.setLearnedSamples(Math.max(point.getLearnedSamples(), binWindow.size()));
            points.save(point);
        }
        boolean ready = binWindow.size() >= MINIMUM_LEARNING_SAMPLES && (policy.pooled() || spansLearningWindow(
                binWindow.getFirst().getSampledAt(), binWindow.getLast().getSampledAt(), binWindow.size(), window));
        if (!ready)
            return binSnapshot(point, modality, binIndex, "LEARNING", binStates(point, modality, binIndex), null);
        Map<String, double[]> raw = rawByFeature(binWindow, plugin.baselineFeatureCodes());
        FeatureStatistics stats = featureStatistics(raw);
        double lockCv = lockCv(stats.cvByCode(), raw, binWindow.size(), convergenceCodes(plugin));
        short resultingStatus;
        Instant lockedAt = null;
        if (lockCv <= CV_LOCK_THRESHOLD) {
            resultingStatus = ACTIVE;
            lockedAt = Instant.now();
            if (point.getBaselineStatus() != ACTIVE) {
                point.setBaselineStatus(ACTIVE);
                point.setBaselineLockedAt(lockedAt);
                point.setLearnedSamples(binWindow.size());
                point.setBaselineCv(lockCv);
            }
            points.save(point);
        } else {
            resultingStatus = DIVERGED;
        }
        upsertFeatureStates(point, modality, binIndex, stats.cleaned(), stats.cvByCode(), code -> raw.get(code).length,
                resultingStatus, lockedAt);
        if (resultingStatus == DIVERGED)
            return binSnapshot(point, modality, binIndex, "DIVERGED", binStates(point, modality, binIndex),
                    "BASELINE_DIVERGENCE");
        return binSnapshot(point, modality, binIndex, "ACTIVE", binStates(point, modality, binIndex), null);
    }

    /** 参与锁定收敛判据的特征编码：机理策略插件声明的收敛特征，其余插件为全部基线特征。 */
    private static Set<String> convergenceCodes(ModalityPlugin plugin) {
        if (plugin instanceof MechanismPluginStrategy strategy && strategy.convergenceFeatureCodes() != null)
            return strategy.convergenceFeatureCodes();
        return plugin.baselineFeatureCodes() == null ? Set.of() : plugin.baselineFeatureCodes();
    }

    /**
     * 锁定收敛判据用的最大 CV：只统计收敛特征中出现于至少一半窗口样本的特征。
     * 少数样本才出现的可选特征不能凭少量观测否决锁定；非有限 CV（中位数近零）同样不计入。
     */
    static double lockCv(Map<String, Double> cvByCode, Map<String, double[]> raw, int windowSize, Set<String> codes) {
        double max = 0.0;
        for (String code : codes) {
            Double cv = cvByCode.get(code);
            double[] values = raw.get(code);
            if (cv == null || values == null || !Double.isFinite(cv) || values.length * 2L < windowSize) continue;
            max = Math.max(max, cv);
        }
        return max;
    }

    @Transactional
    public PointEntity requirePoint(Long pointId) {
        return points.findById(pointId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "测点不存在"));
    }

    /** 按模态解析基线学习窗口：插件声明优先，否则回落全局 pdm.baseline.learning-duration。 */
    public Duration learningWindow(com.pdm.mvp.modality.ModalityPlugin plugin) {
        Duration window = plugin.baselineLearning().window();
        return window != null ? window : learningDuration;
    }

    @Transactional(readOnly = true)
    public BaselineState get(Long pointId) {
        PointEntity point = requirePoint(pointId);
        return baselineState(point);
    }

    private BaselineState baselineState(PointEntity point) {
        List<BaselineState.FeatureStateView> views = featureStates
                .findByPoint_IdAndModalityType(point.getId(), point.getModalityType()).stream()
                .map(s -> new BaselineState.FeatureStateView(s.getFeatureCode(), s.getP50(), s.getCv(),
                        s.getSampleCount(), statusName(s.getStatus()), s.getLockedAt(), s.getBinIndex(),
                        OperatingRegimeBinner.regimeLabel(point, point.getModalityType(), s.getBinIndex())))
                .toList();
        return state(point, null).withFeatureStates(views).withRegimes(regimeSummaries(views));
    }

    /** 学习期预览样本下限：特征原始样本少于该数时统计噪声过大，宁缺毋滥（该特征不展示预览行）。 */
    static final int PROVISIONAL_MIN_FEATURE_SAMPLES = 8;

    /**
     * 基线视图（含学习期预览，只读不落库）：持久化特征行优先；插件声明的基线特征若尚无持久化行，
     * 用当前学习窗口样本现算 PROVISIONAL 预览行（P50 = 清洗后中位数、CV、原始样本数、lockedAt = null），
     * 让通道在达到锁定门槛前的数月里也能看到特征统计。通道状态与告警语义不变。
     */
    @Transactional(readOnly = true)
    public BaselineState getProvisional(Long pointId, com.pdm.mvp.modality.ModalityPlugin plugin) {
        PointEntity point = requirePoint(pointId);
        BaselineState state = baselineState(point);
        List<BaselineState.FeatureStateView> provisional = provisionalFeatureStates(point, plugin, state);
        if (provisional.isEmpty()) return state;
        List<BaselineState.FeatureStateView> merged = new ArrayList<>(state.featureStates());
        merged.addAll(provisional);
        return state.withFeatureStates(merged).withRegimes(regimeSummaries(merged));
    }

    /**
     * 学习期预览行：仅限该工况箱无持久化行的插件基线特征；窗口样本数达标才输出，CV 非有限值（中位数近零）置空。
     * 多工况分箱：按样本的 bin_index 分组，各工况箱独立生成带箱号与工况标签的 PROVISIONAL 预览行。
     */
    private List<BaselineState.FeatureStateView> provisionalFeatureStates(PointEntity point,
            com.pdm.mvp.modality.ModalityPlugin plugin, BaselineState state) {
        List<BaselineState.FeatureStateView> views = new ArrayList<>();
        if (plugin == null) return views;
        java.util.Set<String> persisted = new LinkedHashSet<>();
        for (BaselineState.FeatureStateView view : state.featureStates()) persisted.add(view.binIndex() + "|" + view.featureCode());
        Instant now = Instant.now();
        Map<Short, Map<String, double[]>> rawByBin = new LinkedHashMap<>();
        if (ModalityTypes.ROTATING_VIB.equals(plugin.modalityType())) {
            List<BaselineSampleEntity> window = samples.findByPoint_IdAndSampledAtBetweenOrderBySampledAtAsc(
                    point.getId(), now.minus(learningWindow(plugin)), now);
            LinkedHashSet<Short> bins = new LinkedHashSet<>();
            for (BaselineSampleEntity sample : window) bins.add(sample.getBinIndex());
            for (short bin : bins) rawByBin.put(bin,
                    legacyRawByFeature(window.stream().filter(s -> s.getBinIndex() == bin).toList(), plugin));
        } else {
            List<ModalityBaselineSampleEntity> window = modalitySamples
                    .findByPoint_IdAndModalityTypeAndSampledAtBetweenOrderBySampledAtAsc(point.getId(),
                            plugin.modalityType(), now.minus(learningWindow(plugin)), now);
            LinkedHashSet<Short> bins = new LinkedHashSet<>();
            for (ModalityBaselineSampleEntity sample : window) bins.add(sample.getBinIndex());
            for (short bin : bins) rawByBin.put(bin, rawByFeature(
                    window.stream().filter(s -> s.getBinIndex() == bin).toList(), plugin.baselineFeatureCodes()));
        }
        for (var binEntry : rawByBin.entrySet()) {
            short bin = binEntry.getKey();
            Map<String, double[]> raw = binEntry.getValue();
            FeatureStatistics stats = featureStatistics(raw);
            for (String code : plugin.baselineFeatureCodes()) {
                if (persisted.contains(bin + "|" + code)) continue;
                double[] values = raw.get(code);
                if (values == null || values.length < PROVISIONAL_MIN_FEATURE_SAMPLES) continue;
                Double cv = stats.cvByCode().get(code);
                views.add(new BaselineState.FeatureStateView(code, median(stats.cleaned().get(code)),
                        cv == null || !Double.isFinite(cv) ? null : cv, values.length, "PROVISIONAL", null, bin,
                        OperatingRegimeBinner.regimeLabel(point, plugin.modalityType(), bin)));
            }
        }
        return views;
    }

    /** 振动学习窗口的旧表特征原始值（旧表没有的扩展特征编码不参与学习期预览）。 */
    private Map<String, double[]> legacyRawByFeature(List<BaselineSampleEntity> window,
            com.pdm.mvp.modality.ModalityPlugin plugin) {
        Map<String, double[]> raw = new LinkedHashMap<>();
        for (String code : plugin.baselineFeatureCodes()) {
            double[] values = window.stream().map(sample -> legacyColumn(sample, code))
                    .filter(Objects::nonNull).mapToDouble(Double::doubleValue).toArray();
            if (values.length > 0) raw.put(code, values);
        }
        return raw;
    }

    /** 旧表列 → 特征编码映射；旧表没有的特征编码返回 null（不参与预览）。 */
    private static Double legacyColumn(BaselineSampleEntity sample, String code) {
        return switch (code) {
            case VibrationContractAdapter.V_RMS -> sample.getVRms();
            case VibrationContractAdapter.A_PEAK -> sample.getAPeak();
            case VibrationContractAdapter.FREQ_1X_AMP -> sample.getFreq1xAmp();
            case VibrationContractAdapter.FREQ_2X_AMP -> sample.getFreq2xAmp();
            case VibrationContractAdapter.ENVELOPE_BPFO_AMP -> sample.getEnvelopeBpfoAmp();
            case VibrationContractAdapter.TEMPERATURE -> sample.getTemperature();
            default -> null;
        };
    }

    @Transactional
    public BaselineState requestReset(Long pointId) {
        PointEntity point = requirePointForUpdate(pointId);
        if (point.getBaselineStatus() != ACTIVE) throw new ResponseStatusException(HttpStatus.CONFLICT, "只有已生效的基线可以申请重置");
        point.setBaselineStatus(PENDING_RESET);
        points.save(point);
        return state(point, null);
    }

    @Transactional
    public BaselineState confirmReset(Long pointId) {
        PointEntity point = requirePointForUpdate(pointId);
        if (point.getBaselineStatus() != PENDING_RESET) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前测点没有待确认重置");
        samples.deleteByPoint_Id(pointId);
        modalitySamples.deleteByPoint_IdAndModalityType(pointId, point.getModalityType());
        featureStates.deleteByPoint_Id(pointId);
        spectrumBaseline.deleteByPoint(pointId); // 频谱参考带一并重学（同一条重置路径）
        point.setFreq1xBase(null);
        point.setFreq2xBase(null);
        point.setEnvelopeBpfoBase(null);
        point.setBaselineCv(null);
        point.setVBaseRms(null);
        point.setABasePeak(null);
        point.setTempBase(null);
        point.setLearnedSamples(0);
        point.setBaselineLockedAt(null);
        point.setBaselineStatus(LEARNING);
        point.setLastHealthIndex(null);
        point.setLastStatus("BASELINE_LEARNING");
        points.save(point);
        return state(point, null);
    }

    @Transactional
    public DeviceBaselineOperation requestDeviceReset(Long deviceId, List<Long> requestedPointIds) {
        DeviceEntity device = requireDevice(deviceId);
        List<Long> selectedIds = resolveDevicePointIds(deviceId, requestedPointIds);
        List<BaselineState> states = new ArrayList<>(selectedIds.size());
        int affected = 0;
        for (Long pointId : selectedIds) {
            BaselineState current = get(pointId);
            if ("ACTIVE".equals(current.status())) {
                current = requestReset(pointId);
                affected++;
            }
            states.add(current);
        }
        String action = affected == 0 ? "NO_ACTIVE_BASELINE" : "RESET_REQUESTED";
        return new DeviceBaselineOperation(device.getId(), device.getDeviceCode(), action, affected, states);
    }

    @Transactional
    public DeviceBaselineOperation confirmDeviceReset(Long deviceId, List<Long> requestedPointIds) {
        DeviceEntity device = requireDevice(deviceId);
        List<Long> selectedIds = resolveDevicePointIds(deviceId, requestedPointIds);
        List<BaselineState> states = new ArrayList<>(selectedIds.size());
        int affected = 0;
        for (Long pointId : selectedIds) {
            BaselineState current = get(pointId);
            if ("PENDING_RESET".equals(current.status())) {
                current = confirmReset(pointId);
                affected++;
            }
            states.add(current);
        }
        if (affected == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "所选测点没有待确认的基线重置");
        return new DeviceBaselineOperation(device.getId(), device.getDeviceCode(), "RESET_CONFIRMED", affected, states);
    }

    private DeviceEntity requireDevice(Long deviceId) {
        return devices.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"));
    }

    private List<Long> resolveDevicePointIds(Long deviceId, List<Long> requestedPointIds) {
        List<Long> existingIds = points.findByDevice_IdOrderByPointCode(deviceId).stream().map(PointEntity::getId).toList();
        if (existingIds.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "设备还没有测点");
        if (requestedPointIds == null || requestedPointIds.isEmpty()) return existingIds;
        LinkedHashSet<Long> requested = new LinkedHashSet<>(requestedPointIds);
        if (requested.size() != requestedPointIds.size() || requested.contains(null)
                || !new LinkedHashSet<>(existingIds).containsAll(requested)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选测点无效或不属于该设备");
        }
        return existingIds.stream().filter(requested::contains).toList();
    }

    private BaselineState state(PointEntity point, String warning) {
        String name = statusName(point.getBaselineStatus());
        return new BaselineState(point.getId(), name, point.getLearnedSamples(), point.getFreq1xBase(), point.getVBaseRms(),
                point.getBaselineLockedAt(), warning, point.getBaselineCv(), point.getFreq2xBase(),
                point.getEnvelopeBpfoBase(), point.getTempBase(),
                learningDuration.getSeconds() + learningDuration.getNano() / 1_000_000_000.0,
                MINIMUM_LEARNING_SAMPLES);
    }

    static String statusName(short status) {
        return switch (status) {
            case ACTIVE -> "ACTIVE"; case PENDING_RESET -> "PENDING_RESET"; case DIVERGED -> "DIVERGED"; default -> "LEARNING";
        };
    }

    private boolean spansLearningWindow(List<BaselineSampleEntity> window) {
        return spansLearningWindow(window.getFirst().getSampledAt(), window.getLast().getSampledAt(), window.size(),
                learningDuration);
    }

    private boolean spansLearningWindow(Instant first, Instant last, int size, Duration window) {
        Duration span = Duration.between(first, last);
        Duration averageInterval = span.dividedBy(Math.max(1, size - 1L));
        return span.plus(averageInterval).compareTo(window) >= 0;
    }

    private PointEntity requirePointForUpdate(Long pointId) {
        return points.findByIdForUpdate(pointId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "测点不存在"));
    }

    /**
     * 写入/更新各特征基线状态行（按工况箱隔离）；sampleCountOf 给出该特征的样本数（振动 = 窗口样本数，其余模态 = 该特征原始样本数）。
     * 一次取回该测点/模态全部箱的状态行在内存按 (bin_index, feature_code) 匹配：
     * 既消除逐特征 SELECT 的 N+1，也避免多箱并存时单行查询的非唯一结果异常。
     */
    private void upsertFeatureStates(PointEntity point, String modalityType, short binIndex, Map<String, double[]> cleaned,
            Map<String, Double> cvByCode, java.util.function.Function<String, Integer> sampleCountOf, short status,
            Instant lockedAt) {
        Map<String, BaselineFeatureStateEntity> existing = new LinkedHashMap<>();
        for (BaselineFeatureStateEntity state : featureStates.findByPoint_IdAndModalityType(point.getId(), modalityType))
            if (state.getBinIndex() == binIndex) existing.putIfAbsent(state.getFeatureCode(), state);
        for (var entry : cleaned.entrySet()) {
            if (entry.getValue().length == 0) continue;
            BaselineFeatureStateEntity state = existing.get(entry.getKey());
            if (state == null) state = new BaselineFeatureStateEntity(point, modalityType, binIndex, entry.getKey());
            state.setP50(median(entry.getValue()));
            Double cv = cvByCode.get(entry.getKey());
            // 中位数近零时 CV 为 +Infinity：落库置空（JSON 绝不序列化 "Infinity"），锁定门槛判定不受影响
            state.setCv(cv == null || !Double.isFinite(cv) ? null : cv);
            state.setSampleCount(sampleCountOf.apply(entry.getKey()));
            state.setStatus(status);
            state.setLockedAt(lockedAt);
            state.touch();
            featureStates.save(state);
        }
    }

    BaselineSnapshot snapshotFromPoint(PointEntity point, String warning) {
        Map<String, Double> base = new LinkedHashMap<>();
        if (point.getFreq1xBase() != null) base.put(VibrationContractAdapter.FREQ_1X_AMP, point.getFreq1xBase());
        if (point.getFreq2xBase() != null) base.put(VibrationContractAdapter.FREQ_2X_AMP, point.getFreq2xBase());
        if (point.getVBaseRms() != null) base.put(VibrationContractAdapter.V_RMS, point.getVBaseRms());
        if (point.getABasePeak() != null) base.put(VibrationContractAdapter.A_PEAK, point.getABasePeak());
        if (point.getEnvelopeBpfoBase() != null) base.put(VibrationContractAdapter.ENVELOPE_BPFO_AMP, point.getEnvelopeBpfoBase());
        if (point.getTempBase() != null) base.put(VibrationContractAdapter.TEMPERATURE, point.getTempBase());
        Map<String, Double> cv = new LinkedHashMap<>();
        if (point.getBaselineCv() != null) cv.put(VibrationContractAdapter.FREQ_1X_AMP, point.getBaselineCv());
        // 协议 2.0.0 扩展特征基线（BPFI/温升/3X/0.44X/sei/crest）落在特征状态表，仅合并额定箱（bin 0）行
        for (BaselineFeatureStateEntity state : featureStates.findByPoint_IdAndModalityType(
                point.getId(), ModalityTypes.ROTATING_VIB)) {
            if (state.getBinIndex() != 0) continue;
            if (state.getP50() != null) base.putIfAbsent(state.getFeatureCode(), state.getP50());
            if (state.getCv() != null) cv.putIfAbsent(state.getFeatureCode(), state.getCv());
        }
        return new BaselineSnapshot(point.getId(), ModalityTypes.ROTATING_VIB, statusName(point.getBaselineStatus()),
                point.getLearnedSamples(), point.getBaselineLockedAt(), warning, base, cv);
    }

    private BaselineSnapshot featureSnapshot(PointEntity point, String modality, String warning) {
        Map<String, Double> base = new LinkedHashMap<>();
        Map<String, Double> cvs = new LinkedHashMap<>();
        for (BaselineFeatureStateEntity state : featureStates.findByPoint_IdAndModalityType(point.getId(), modality)) {
            if (state.getBinIndex() != 0) continue; // 默认箱快照不混入其他工况箱的 P50，杜绝张冠李戴
            if (state.getP50() != null) base.put(state.getFeatureCode(), state.getP50());
            if (state.getCv() != null) cvs.put(state.getFeatureCode(), state.getCv());
        }
        return new BaselineSnapshot(point.getId(), modality, statusName(point.getBaselineStatus()),
                point.getLearnedSamples(), point.getBaselineLockedAt(), warning, base, cvs);
    }

    private static Map<String, double[]> rawByFeature(List<ModalityBaselineSampleEntity> window, java.util.Set<String> codes) {
        Map<String, double[]> raw = new LinkedHashMap<>();
        for (String code : codes) {
            double[] values = window.stream().map(sample -> sample.getFeatureValues().get(code))
                    .filter(Objects::nonNull).mapToDouble(Double::doubleValue).toArray();
            if (values.length > 0) raw.put(code, values);
        }
        return raw;
    }

    /** 各特征独立 MAD 清洗并统计中位数变异系数；取最大 CV 作为锁定门槛。 */
    public record FeatureStatistics(Map<String, double[]> cleaned, Map<String, Double> cvByCode, double maxCv) {}

    public static FeatureStatistics featureStatistics(Map<String, double[]> raw) {
        Map<String, double[]> cleaned = new LinkedHashMap<>();
        Map<String, Double> cvByCode = new LinkedHashMap<>();
        double maxCv = 0;
        for (var entry : raw.entrySet()) {
            double[] values = madClean(entry.getValue());
            cleaned.put(entry.getKey(), values);
            if (values.length == 0) continue;
            double cv = coefficientOfVariation(values);
            cvByCode.put(entry.getKey(), cv);
            if (Double.isFinite(cv)) maxCv = Math.max(maxCv, cv);
        }
        return new FeatureStatistics(cleaned, cvByCode, maxCv);
    }

    static double median(double[] values) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2.0 : sorted[middle];
    }

    static double coefficientOfVariation(double[] values) {
        if (values.length == 0) return Double.POSITIVE_INFINITY;
        double center = median(values);
        if (center <= 1e-12) return java.util.Arrays.stream(values).max().orElse(0) == 0 ? 0 : Double.POSITIVE_INFINITY;
        double variance = java.util.Arrays.stream(values).map(value -> (value - center) * (value - center)).sum()
                / Math.max(1, values.length - 1);
        return Math.sqrt(variance) / center;
    }

    static double[] madClean(double[] values) {
        if (values.length < 3) return values;
        double center = median(values);
        double[] deviations = java.util.Arrays.stream(values).map(value -> Math.abs(value - center)).toArray();
        double mad = median(deviations);
        if (mad < 1e-12) return java.util.Arrays.stream(values).filter(value -> Math.abs(value - center) <= 1e-9).toArray();
        double limit = 3.0 * 1.4826 * mad;
        return java.util.Arrays.stream(values).filter(value -> Math.abs(value - center) <= limit).toArray();
    }

    /** 按工况箱聚合多特征视图（bin 0 + 各非额定箱）：状态取箱内持久化行的最高优先级，仅预览的箱按 LEARNING 汇总。 */
    static List<BaselineState.RegimeBinSummary> regimeSummaries(List<BaselineState.FeatureStateView> views) {
        Map<Short, List<BaselineState.FeatureStateView>> byBin = new LinkedHashMap<>();
        for (BaselineState.FeatureStateView view : views)
            byBin.computeIfAbsent(view.binIndex(), k -> new ArrayList<>()).add(view);
        List<BaselineState.RegimeBinSummary> summaries = new ArrayList<>();
        for (var entry : byBin.entrySet()) {
            String label = entry.getValue().getFirst().regimeLabel();
            String status = "LEARNING";
            int rank = regimeStatusRank(status);
            for (BaselineState.FeatureStateView view : entry.getValue()) {
                int viewRank = regimeStatusRank(view.status());
                if (viewRank > rank) { rank = viewRank; status = view.status(); }
            }
            int sampleCount = 0;
            Instant lockedAt = null;
            Map<String, Double> featureP50 = new LinkedHashMap<>();
            for (BaselineState.FeatureStateView view : entry.getValue()) {
                sampleCount = Math.max(sampleCount, view.sampleCount());
                if (view.p50() != null) featureP50.putIfAbsent(view.featureCode(), view.p50());
                if (view.lockedAt() != null && (lockedAt == null || view.lockedAt().isBefore(lockedAt)))
                    lockedAt = view.lockedAt();
            }
            summaries.add(new BaselineState.RegimeBinSummary(entry.getKey(), label, status, sampleCount, lockedAt, featureP50));
        }
        return summaries;
    }

    private static int regimeStatusRank(String status) {
        return switch (status == null ? "" : status) {
            case "ACTIVE" -> 4; case "PENDING_RESET" -> 3; case "DIVERGED" -> 2; case "LEARNING" -> 1; default -> 0;
        };
    }

    public record BaselineState(Long pointId, String status, int learnedSamples, Double freq1xBase,
            Double vBaseRms, Instant lockedAt, String warning, Double cv1x, Double freq2xBase,
            Double envelopeBpfoBase, Double tempBase, double learningDurationSeconds, int minimumSamples,
            List<FeatureStateView> featureStates, List<RegimeBinSummary> regimes) {

        public BaselineState(Long pointId, String status, int learnedSamples, Double freq1xBase,
                Double vBaseRms, Instant lockedAt, String warning, Double cv1x, Double freq2xBase,
                Double envelopeBpfoBase, Double tempBase, double learningDurationSeconds, int minimumSamples) {
            this(pointId, status, learnedSamples, freq1xBase, vBaseRms, lockedAt, warning, cv1x, freq2xBase,
                    envelopeBpfoBase, tempBase, learningDurationSeconds, minimumSamples, List.of(), List.of());
        }

        public BaselineState(Long pointId, String status, int learnedSamples, Double freq1xBase,
                Double vBaseRms, Instant lockedAt, String warning, Double cv1x, Double freq2xBase,
                Double envelopeBpfoBase, Double tempBase, double learningDurationSeconds, int minimumSamples,
                List<FeatureStateView> featureStates) {
            this(pointId, status, learnedSamples, freq1xBase, vBaseRms, lockedAt, warning, cv1x, freq2xBase,
                    envelopeBpfoBase, tempBase, learningDurationSeconds, minimumSamples, featureStates, List.of());
        }

        public BaselineState withFeatureStates(List<FeatureStateView> states) {
            return new BaselineState(pointId, status, learnedSamples, freq1xBase, vBaseRms, lockedAt, warning, cv1x,
                    freq2xBase, envelopeBpfoBase, tempBase, learningDurationSeconds, minimumSamples, states, regimes);
        }

        public BaselineState withRegimes(List<RegimeBinSummary> summaries) {
            return new BaselineState(pointId, status, learnedSamples, freq1xBase, vBaseRms, lockedAt, warning, cv1x,
                    freq2xBase, envelopeBpfoBase, tempBase, learningDurationSeconds, minimumSamples, featureStates,
                    summaries);
        }

        public record FeatureStateView(String featureCode, Double p50, Double cv, int sampleCount,
                String status, Instant lockedAt, short binIndex, String regimeLabel) {

            /** 兼容构造器：单工况调用方默认额定/默认工况箱。 */
            public FeatureStateView(String featureCode, Double p50, Double cv, int sampleCount,
                    String status, Instant lockedAt) {
                this(featureCode, p50, cv, sampleCount, status, lockedAt, (short) 0, "默认/额定工况");
            }
        }

        /** 工况箱汇总（界面多工况列表）：一个出现过的 bin_index 一行。 */
        public record RegimeBinSummary(short binIndex, String regimeLabel, String status, int sampleCount,
                Instant lockedAt, Map<String, Double> featureP50) {}
    }

    public record DeviceBaselineOperation(Long deviceId, String deviceCode, String action, int affectedCount,
            List<BaselineState> baselines) {}
}
