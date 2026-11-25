package com.pdm.mvp.analytics;

import com.pdm.mvp.analytics.SpectrumBandGrid.Band;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.waveform.WaveframeService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 频谱基线带（文档 §4.2 的谱域扩展）：对每帧入库的波形快照按 1/6 倍频程频带聚合能量，
 * 滚动窗口逐带 MAD 清洗后冻结 P50 ± 3×1.4826×MAD 的健康参考带，供工作站频谱对比。
 *
 * <p>生命周期独立于标量特征基线：波形快照稀疏（阈值/巡检/召唤触发，无 6 分钟连续窗口），
 * 冻结判据为自身窗口样本数达到下限；一旦冻结不再吸收新帧，基线重置（confirmReset）一并清空重学。
 * 原始波形载荷不携带转速，无法做标量学习同款的 ±5% 转速门禁——快照触发语义（阈值越限/定时巡检/专家召唤）
 * 即为工况可信度的兜底。</p>
 */
@Service
public class SpectrumBaselineService {
    public static final String KIND_FFT = "FFT";
    public static final String KIND_ENVELOPE = "ENVELOPE";
    /** 参考带宽度系数：与 BaselineService.madClean 的 3σ 限一致。 */
    static final double MAD_SIGMA = 3.0 * 1.4826;

    private final SpectrumBandSampleRepository samples;
    private final SpectrumBandStateRepository states;
    private final int minimumSamples;
    private final Duration window;

    public SpectrumBaselineService(SpectrumBandSampleRepository samples, SpectrumBandStateRepository states,
            @Value("${pdm.spectrum-baseline.minimum-samples:30}") int minimumSamples,
            @Value("${pdm.spectrum-baseline.window:P30D}") Duration window) {
        if (minimumSamples <= 0) throw new IllegalArgumentException("pdm.spectrum-baseline.minimum-samples must be positive");
        if (window.isZero() || window.isNegative()) throw new IllegalArgumentException("pdm.spectrum-baseline.window must be positive");
        this.samples = samples; this.states = states;
        this.minimumSamples = minimumSamples; this.window = window;
    }

    /** 仅允许处于健康或正常初始学习态的测点吸收频谱样本（拒绝越限、告警、停机、传感器异常、发散及检修/报废态）。 */
    private static final java.util.Set<String> LEARNABLE_POINT_STATUSES = java.util.Set.of(
            "HEALTHY", "BASELINE_LEARNING", "ANALYSIS_BUFFERING", "ANALYSIS_PENDING", "SPIKE_SUPPRESSED");

    /** 携带触发类型的学习入口：越限触发（THRESHOLD_EXCEEDED）代表故障/异常帧，绝不进入健康参考带学习。 */
    @Transactional
    public void observe(PointEntity point, WaveframeService.Waveframe frame, String triggerType) {
        if (com.pdm.mvp.waveform.WaveformSnapshotEntity.TRIGGER_THRESHOLD.equals(triggerType)) return;
        observe(point, frame);
    }

    /** 波形快照入库后的学习入口：FFT 与包络谱分别按各自网格累积（包络谱可能因帧长/采样率缺省）。 */
    @Transactional
    public void observe(PointEntity point, WaveframeService.Waveframe frame) {
        if (point == null || !ModalityTypes.ROTATING_VIB.equals(point.getModalityType())) return;
        if (frame == null || !isHealthyForLearning(point)) return;
        observeKind(point, KIND_FFT, frame.timestamp(), frame.fftFreqs(), frame.fftAmps(), SpectrumBandGrid.fftBands());
        observeKind(point, KIND_ENVELOPE, frame.timestamp(), frame.envelopeFreqs(), frame.envelopeAmps(),
                SpectrumBandGrid.envelopeBands());
    }

    static boolean isHealthyForLearning(PointEntity point) {
        if (com.pdm.mvp.asset.DeviceEntity.isOutOfService(point.getDevice())) return false;
        if (point.getQualityCode() != null && point.getQualityCode() != 0) return false;
        short baselineStatus = point.getBaselineStatus();
        if (baselineStatus == BaselineService.DIVERGED || baselineStatus == BaselineService.PENDING_RESET) return false;
        Double hi = point.getLastHealthIndex();
        if (hi != null && (!Double.isFinite(hi) || hi < 0.0)) return false;
        // 70 分门槛只约束确实经过遥测评估或已锁定基线的测点；新建测点（lastStatus=null 且 LEARNING）尚未评过分，
        // 不能因其 lastHealthIndex 为 0/缺省而被误拦
        if ((point.getLastStatus() != null || baselineStatus == BaselineService.ACTIVE) && hi != null && hi < 70.0)
            return false;
        String lastStatus = point.getLastStatus();
        return lastStatus == null || LEARNABLE_POINT_STATUSES.contains(lastStatus);
    }

    private void observeKind(PointEntity point, String kind, long timestamp, double[] freqs, double[] amps,
            List<Band> grid) {
        if (amps.length == 0 || freqs.length != amps.length) return;
        if (states.existsByPoint_IdAndKind(point.getId(), kind)) return; // 参考带已冻结，不再吸收新帧
        Instant sampledAt = Instant.ofEpochMilli(timestamp);
        if (samples.existsByPoint_IdAndKindAndSampledAt(point.getId(), kind, sampledAt)) return;
        samples.save(new SpectrumBandSampleEntity(point, kind, sampledAt, toList(SpectrumBandGrid.aggregate(freqs, amps, grid))));
        samples.deleteByPoint_IdAndKindAndSampledAtBefore(point.getId(), kind, sampledAt.minus(window));
        if (samples.countByPoint_IdAndKind(point.getId(), kind) < minimumSamples) return;
        freeze(point, kind, grid, sampledAt);
    }

    /** 冻结：滚动窗口逐带 MAD 清洗 → P50/MAD 落库（有行即冻结）。覆盖不全的帧按带独立统计。窗口锚定最新帧时间戳。 */
    private void freeze(PointEntity point, String kind, List<Band> grid, Instant sampledAt) {
        List<SpectrumBandSampleEntity> windowRows = samples
                .findByPoint_IdAndKindAndSampledAtBetweenOrderBySampledAtAsc(point.getId(), kind, sampledAt.minus(window), sampledAt);
        Instant lockedAt = Instant.now();
        for (Band band : grid) {
            int index = band.index();
            double[] values = windowRows.stream().map(sample -> sample.getBandValues().get(index))
                    .filter(Objects::nonNull).mapToDouble(Double::doubleValue).toArray();
            double[] cleaned = BaselineService.madClean(values);
            if (cleaned.length == 0) continue;
            double p50 = BaselineService.median(cleaned);
            states.save(new SpectrumBandStateEntity(point, kind, index, band.lowHz(), band.centerHz(), band.highHz(),
                    p50, medianAbsoluteDeviation(cleaned, p50), cleaned.length, lockedAt));
        }
    }

    /** 清洗后相对冻结中心的重算 MAD（清洗只去离群点，中心不变），供参考带上下限使用。 */
    private static double medianAbsoluteDeviation(double[] cleaned, double center) {
        double[] deviations = java.util.Arrays.stream(cleaned).map(value -> Math.abs(value - center)).toArray();
        return BaselineService.median(deviations);
    }

    /** NaN → null（JSONB 数组不能序列化 NaN），与网格 index 对齐。 */
    private static List<Double> toList(double[] values) {
        List<Double> list = new ArrayList<>(values.length);
        for (double value : values) list.add(Double.isNaN(value) ? null : value);
        return list;
    }

    /** 基线重置（confirmReset）时清空该测点的频谱样本与冻结参考带，回到学习态。 */
    @Transactional
    public void deleteByPoint(Long pointId) {
        samples.deleteByPoint_Id(pointId);
        states.deleteByPoint_Id(pointId);
    }

    @Transactional(readOnly = true)
    public SpectrumBaselineView get(Long pointId) {
        List<KindBaseline> kinds = new ArrayList<>(2);
        kinds.add(kindView(pointId, KIND_FFT, SpectrumBandGrid.fftBands()));
        kinds.add(kindView(pointId, KIND_ENVELOPE, SpectrumBandGrid.envelopeBands()));
        return new SpectrumBaselineView(pointId, minimumSamples, kinds);
    }

    private KindBaseline kindView(Long pointId, String kind, List<Band> grid) {
        List<SpectrumBandStateEntity> rows = states.findByPoint_IdAndKindOrderByBandIndex(pointId, kind);
        String status = rows.isEmpty() ? "LEARNING" : "ACTIVE";
        Instant lockedAt = rows.isEmpty() ? null : rows.getFirst().getLockedAt();
        List<BandView> bands = rows.stream().map(row -> {
            double limit = MAD_SIGMA * row.getMad();
            return new BandView(row.getBandCenterHz(), row.getBandLowHz(), row.getBandHighHz(),
                    row.getP50(), Math.max(0, row.getP50() - limit), row.getP50() + limit);
        }).toList();
        return new KindBaseline(kind, status, (int) samples.countByPoint_IdAndKind(pointId, kind), lockedAt,
                grid.size(), bands);
    }

    public record SpectrumBaselineView(Long pointId, int minimumSamples, List<KindBaseline> kinds) {}

    public record KindBaseline(String kind, String status, int sampleCount, Instant lockedAt,
            int gridBands, List<BandView> bands) {}

    /** 参考带视图：lower/upper = P50 ± 3×1.4826×MAD（lower 不为负）。 */
    public record BandView(double centerHz, double lowHz, double highHz, double p50, double lower, double upper) {}
}
