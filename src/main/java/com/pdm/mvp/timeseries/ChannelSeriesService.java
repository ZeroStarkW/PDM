package com.pdm.mvp.timeseries;

import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.modality.ModalityPluginRegistry;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.telemetry.VibrationContractAdapter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * v2 跨模态通道时序查询（文档 §6.1 GET /api/v2/telemetry/{code}/series）：
 * 聚合步长或 raw 原始值，特征列按模态插件声明（振动请求全部已知振动码），行内指标稀疏输出。
 */
@Service
public class ChannelSeriesService {
    static final Set<String> STEPS = Set.of("1m", "5m", "15m", "1h", "6h", "raw");
    /** start_ts/end_ts 允许的毫秒 epoch 下界（1971-01-01，防止 Long.MIN_VALUE 溢出与库端无法表达的边界）。 */
    static final long MIN_EPOCH_MS = 31_536_000_000L;
    /** start_ts/end_ts 允许的上界相对量：服务器时间 + 1 天。 */
    static final java.time.Duration MAX_FUTURE = java.time.Duration.ofDays(1);
    private final AssetLookup assets;
    private final ModalityPluginRegistry plugins;
    private final TelemetryStore store;

    public ChannelSeriesService(AssetLookup assets, ModalityPluginRegistry plugins, TelemetryStore store) {
        this.assets = assets;
        this.plugins = plugins;
        this.store = store;
    }

    @Transactional(readOnly = true)
    public ChannelSeriesView series(String code, Long startTs, Long endTs, String step) {
        PointEntity point = assets.resolvePoint(code);
        String normalizedStep = step == null || step.isBlank() ? "5m" : step;
        if (!STEPS.contains(normalizedStep)) throw bad("step 只能是 1m、5m、15m、1h、6h 或 raw");
        Instant now = Instant.now();
        Instant end = endTs == null ? now : clamp(endTs);
        Instant start = startTs == null ? end.minus(24, ChronoUnit.HOURS) : clamp(startTs);
        if (!start.isBefore(end)) throw bad("start_ts 必须早于 end_ts");
        var plugin = plugins.require(point.getModalityType());
        List<String> featureCodes = ModalityTypes.ROTATING_VIB.equals(plugin.modalityType())
                ? VibrationContractAdapter.KNOWN_CODES : List.copyOf(plugin.seriesFeatureCodes());
        int limit = "raw".equals(normalizedStep)
                ? InfluxTelemetryStore.RAW_ROW_LIMIT : InfluxTelemetryStore.AGGREGATED_ROW_LIMIT;
        List<TelemetryStore.ChannelSample> samples = store.queryChannel(point, start, end, normalizedStep, featureCodes);
        return toView(point, normalizedStep, samples, limit);
    }

    /** 毫秒 epoch 边界校验：1971-01-01 之后、服务器时间 + 1 天之前，越界 400（防 Long.MIN/MAX 溢出 500）。 */
    private static Instant clamp(Long epochMs) {
        if (epochMs == null || epochMs < MIN_EPOCH_MS
                || epochMs > Instant.now().plus(MAX_FUTURE).toEpochMilli())
            throw bad("start_ts/end_ts 必须为 1971-01-01 之后且不超过服务器时间 + 1 天的毫秒 epoch");
        return Instant.ofEpochMilli(epochMs);
    }

    /** 视图组装：features 为行指标键的有序并集，行按时间升序。truncated = 行数触及服务端上限。包可见便于单测。 */
    static ChannelSeriesView toView(PointEntity point, String step, List<TelemetryStore.ChannelSample> samples,
            int limit) {
        Set<String> features = new TreeSet<>();
        for (var sample : samples) features.addAll(sample.metrics().keySet());
        List<TelemetryStore.ChannelSample> ascending = samples.stream()
                .sorted(java.util.Comparator.comparing(TelemetryStore.ChannelSample::timestamp)).toList();
        List<ChannelSeriesView.Row> rows = ascending.stream()
                .map(sample -> new ChannelSeriesView.Row(sample.timestamp().toEpochMilli(), sample.healthIndex(),
                        sample.status(), sample.dataQuality(), sample.metrics()))
                .toList();
        return new ChannelSeriesView(point.getPointCode(), point.getId(), point.getModalityType(), step,
                List.copyOf(features), rows, rows.size() >= limit);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 通道时序视图（前端契约字段名与示例一致；特征编码保持 snake_case）。
     * rows 超过服务端上限（聚合 5000 箱 / raw 2000 行）时仅保留最新样本且 truncated = true
     * （行按时间升序，被截断丢弃的是最旧的一端）；前端据此提示而非静默丢史。
     */
    public record ChannelSeriesView(String channelCode, Long pointId, String modalityType, String step,
            List<String> features, List<Row> rows, boolean truncated) {

        /** 行契约键：timestamp 为毫秒 epoch（数字），health_index / data_quality 保持 snake_case 原样序列化。 */
        public record Row(long timestamp, Double health_index, String status, int data_quality,
                java.util.Map<String, Double> metrics) {}
    }
}
