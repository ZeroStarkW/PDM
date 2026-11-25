package com.pdm.mvp.snapshot;

import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.snapshot.ChannelSnapshotViews.LoopPoint;
import com.pdm.mvp.snapshot.ChannelSnapshotViews.ValveLoop;
import com.pdm.mvp.timeseries.TelemetryStore;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 调节阀回环迟滞查询（文档 §6.1 GET /api/v2/channels/{code}/hysteresis?window=PT1H）：
 * 从通道时序 raw 样本中取同时携带 cv_pct/fb_pct 的行做回环分析。
 * window 为 ISO-8601 时长：非法 400；非正 400；超过 P7D 按 P7D 截断（不报错）。
 * 非调节阀模态返回 200 + UNSUPPORTED_MODALITY（前端展示说明，不报 4xx）。
 * 事务只读边界：时序层会解引用测点→设备的懒加载工厂链（open-in-view 关闭，缺事务即 500）。
 */
@RestController
public class ValveLoopController {
    /** window 上限（超过按此截断）。 */
    static final Duration MAX_WINDOW = Duration.ofDays(7);
    static final String DEFAULT_WINDOW = "PT1H";
    static final String STATUS_OK = "OK";
    static final String STATUS_NO_DATA = "NO_DATA";
    static final String STATUS_UNSUPPORTED = "UNSUPPORTED_MODALITY";
    /** 本查询专用 raw 行上限：大于 7 天窗口的常规样本量（时序端点仍用 2000，避免回环被截断）。 */
    static final int RAW_ROW_LIMIT = 20000;

    private final AssetLookup assets;
    private final TelemetryStore store;

    public ValveLoopController(AssetLookup assets, TelemetryStore store) {
        this.assets = assets;
        this.store = store;
    }

    @GetMapping("/api/v2/channels/{code}/hysteresis")
    @Transactional(readOnly = true)
    public ValveLoop hysteresis(@PathVariable String code,
            @RequestParam(value = "window", defaultValue = DEFAULT_WINDOW) String window) {
        Duration duration = parseWindow(window);
        PointEntity point = assets.resolvePoint(code);
        Instant to = Instant.now();
        Instant from = to.minus(duration);
        if (!ModalityTypes.CONTROL_VALVE_PROCESS.equals(point.getModalityType())) {
            return new ValveLoop(point.getPointCode(), point.getModalityType(), window, 0,
                    null, null, STATUS_UNSUPPORTED,
                    ValveLoopAnalyzer.ValveLoopAnalysis.empty(), List.of(), false, RAW_ROW_LIMIT);
        }
        List<TelemetryStore.ChannelSample> samples = store.queryChannel(point, from, to, "raw",
                List.of(com.pdm.mvp.controlvalve.ControlValvePlugin.CV_PCT,
                        com.pdm.mvp.controlvalve.ControlValvePlugin.FB_PCT),
                RAW_ROW_LIMIT);
        // 行数触及上限说明服务端截断（仅保留最新样本），如实回告前端而不是谎报完整窗口
        boolean truncated = samples.size() >= RAW_ROW_LIMIT;
        List<ValveLoopAnalyzer.ValveSample> pairs = new ArrayList<>();
        List<LoopPoint> points = new ArrayList<>();
        for (TelemetryStore.ChannelSample sample : samples) {
            Double cv = sample.metrics() == null ? null : sample.metrics().get("cv_pct");
            Double fb = sample.metrics() == null ? null : sample.metrics().get("fb_pct");
            if (cv == null || fb == null) continue;
            pairs.add(new ValveLoopAnalyzer.ValveSample(cv, fb));
            points.add(new LoopPoint(sample.timestamp().toEpochMilli(), cv, fb));
        }
        if (pairs.isEmpty()) {
            return new ValveLoop(point.getPointCode(), point.getModalityType(), window, 0,
                    null, null, STATUS_NO_DATA, ValveLoopAnalyzer.ValveLoopAnalysis.empty(), List.of(),
                    truncated, RAW_ROW_LIMIT);
        }
        // from/to 报告样本实际覆盖范围（首/末配对样本），不是请求窗口
        return new ValveLoop(point.getPointCode(), point.getModalityType(), window, pairs.size(),
                points.getFirst().timestamp(), points.getLast().timestamp(), STATUS_OK,
                ValveLoopAnalyzer.analyze(pairs), points, truncated, RAW_ROW_LIMIT);
    }

    /** window 解析：非法 ISO-8601 或非正值 400；超过 P7D 截断为 P7D。包可见便于单测。 */
    Duration parseWindow(String window) {
        Duration duration;
        try {
            duration = Duration.parse(window);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw bad("window 必须为 ISO-8601 时长，如 PT1H");
        }
        if (duration.isZero() || duration.isNegative()) throw bad("window 必须为正时长");
        return duration.compareTo(MAX_WINDOW) > 0 ? MAX_WINDOW : duration;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
