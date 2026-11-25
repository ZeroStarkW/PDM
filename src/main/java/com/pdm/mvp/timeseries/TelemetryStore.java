package com.pdm.mvp.timeseries;

import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.telemetry.TelemetryPoint;
import java.time.Instant;
import java.util.List;

public interface TelemetryStore {
    void write(TelemetryPoint point);
    List<TelemetryPoint> query(PointEntity point, Instant from, Instant to, String interval);

    /**
     * 特征历史查询（文档 §7.1 /api/v1/telemetry/{pointId}/features）：
     * 基础列 + 指定扩展特征聚合；默认退化为基础查询。
     */
    default List<TelemetryPoint> queryFeatures(PointEntity point, Instant from, Instant to, String interval,
            List<String> extraFeatureCodes) {
        return query(point, from, to, interval);
    }

    /** 通道时序样本（文档 §6.1 v2 跨模态统一查询）：metrics 仅含非空特征值（允许稀疏行）。 */
    record ChannelSample(Instant timestamp, Double healthIndex, int dataQuality, String status,
            java.util.Map<String, Double> metrics) {}

    /**
     * v2 通道查询：step ∈ 1m/5m/15m/1h/6h 按 date_bin 聚合，raw 返回原始样本；
     * featureCodes 为请求的特征列（列尚不存在时按实现退化）。结果按时间升序。
     */
    List<ChannelSample> queryChannel(PointEntity point, Instant from, Instant to, String step,
            List<String> featureCodes);

    /**
     * 带服务端行数上限的 v2 通道查询：超出上限时仅返回最新样本（截断不报错）。
     * 缺省回落到无上限重载；需要大窗口完整样本的调用方（回环迟滞）放大 limit 并据返回行数判断截断。
     */
    default List<ChannelSample> queryChannel(PointEntity point, Instant from, Instant to, String step,
            List<String> featureCodes, int limit) {
        return queryChannel(point, from, to, step, featureCodes);
    }
}
