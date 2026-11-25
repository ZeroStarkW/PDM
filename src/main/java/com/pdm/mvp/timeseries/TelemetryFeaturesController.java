package com.pdm.mvp.timeseries;

import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.telemetry.TelemetryPoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 特征时序查询接口（文档 §7.1 GET /api/v1/telemetry/{pointId}/features）：
 * 按 start_ts/end_ts/step 返回文档命名的特征时序（RMS、1X/2X/3X、温度、包络与健康度）。
 */
@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryFeaturesController {
    /** 允许聚合的扩展特征（协议 2.0.0 附加字段白名单）。 */
    private static final List<String> EXTRA_CODES = List.of("feat_3x_amp", "feat_sub_amp", "envelope_bpfo_amp",
            "env_bpfi_amp", "sei_index", "crest_factor", "temp_ambient", "temp_rise", "envelope_rms");

    private final TelemetryStore store;
    private final PointRepository points;

    public TelemetryFeaturesController(TelemetryStore store, PointRepository points) {
        this.store = store;
        this.points = points;
    }

    @GetMapping("/{pointId}/features")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> features(@PathVariable Long pointId,
            @RequestParam(required = false) Long startTs,
            @RequestParam(required = false) Long endTs,
            @RequestParam(defaultValue = "5m") String step) {
        PointEntity point = points.findById(pointId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "测点不存在"));
        Instant end = endTs == null ? Instant.now() : Instant.ofEpochMilli(endTs);
        Instant start = startTs == null ? end.minus(java.time.Duration.ofHours(24)) : Instant.ofEpochMilli(startTs);
        if (!start.isBefore(end)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "start_ts 必须早于 end_ts");
        List<TelemetryPoint> rows = store.queryFeatures(point, start, end, step, EXTRA_CODES);
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (TelemetryPoint row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("timestamp", row.timestamp().toEpochMilli());
            item.put("rpm", row.rpm());
            item.put("temp_bearing", row.temperature());
            item.put("v_rms", row.vRms());
            item.put("a_peak", row.aPeak());
            item.put("a_kurtosis", row.aKurtosis());
            item.put("feat_1x", row.freq1xAmp());
            item.put("feat_2x", row.freq2xAmp());
            item.put("env_rms", row.envelopeRms());
            item.put("env_bpfo", row.envelopeBpfoAmp());
            item.put("health_index", row.healthIndex());
            for (String code : EXTRA_CODES) {
                Double value = row.features() == null ? null : row.features().get(code);
                if (value != null) item.put(docName(code), value);
            }
            result.add(item);
        }
        return result;
    }

    /** 平台特征码 → 协议 2.0.0 指标名。 */
    private static String docName(String code) {
        return switch (code) {
            case "feat_3x_amp" -> "feat_3x";
            case "feat_sub_amp" -> "feat_sub";
            case "envelope_bpfo_amp" -> "env_bpfo";
            case "envelope_bpfi_amp" -> "env_bpfi";
            case "envelope_rms" -> "env_rms";
            default -> code;
        };
    }
}
