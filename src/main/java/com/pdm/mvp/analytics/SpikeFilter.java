package com.pdm.mvp.analytics;

import com.pdm.mvp.modality.ModalityPlugin;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 滑动窗口尖峰抑制：按插件声明的特征键做 3 点中值窗口检测，
 * 中心样本任一键值超过邻均 5 倍判为尖峰，不进入评分。
 */
@Component
public class SpikeFilter {
    private final Map<Long, ArrayDeque<TelemetryEnvelope>> windows = new HashMap<>();
    private final Map<Long, Long> lastTimestamp = new HashMap<>();

    public synchronized Optional<FilteredSample> accept(Long pointId, TelemetryEnvelope sample, ModalityPlugin plugin) {
        Long previousTimestamp = lastTimestamp.get(pointId);
        if (previousTimestamp != null && sample.timestamp() <= previousTimestamp) return Optional.empty();
        lastTimestamp.put(pointId, sample.timestamp());
        // 事件型模态（如断路器动作）：样本天然稀疏，不做 3 点尖峰抑制，直接放行
        if (plugin.cadence() == ModalityPlugin.Cadence.EVENT) return Optional.of(new FilteredSample(sample, false));
        ArrayDeque<TelemetryEnvelope> window = windows.computeIfAbsent(pointId, ignored -> new ArrayDeque<>(3));
        if (!window.isEmpty()) {
            long gap = Math.abs(sample.timestamp() - window.getLast().timestamp());
            if (gap > 15 * 60_000L) window.clear();
        }
        window.addLast(sample);
        if (window.size() < 3) return Optional.empty();
        TelemetryEnvelope left = window.removeFirst();
        TelemetryEnvelope center = window.getFirst();
        TelemetryEnvelope right = window.getLast();
        boolean spike = false;
        for (String code : plugin.spikeFeatureCodes()) {
            double neighboringMean = (value(plugin, left, code) + value(plugin, right, code)) / 2.0;
            if (neighboringMean > 1e-9 && value(plugin, center, code) > neighboringMean * 5.0) spike = true;
        }
        return Optional.of(new FilteredSample(center, spike));
    }

    public synchronized void reset(Long pointId) {
        windows.remove(pointId);
        lastTimestamp.remove(pointId);
    }

    private static double value(ModalityPlugin plugin, TelemetryEnvelope envelope, String code) {
        Double value = plugin.spikeFeatureValue(envelope, code);
        return value == null ? 0.0 : value;
    }

    public record FilteredSample(TelemetryEnvelope sample, boolean spike) {
        public Instant timestamp() { return sample.timestampInstant(); }
    }
}
