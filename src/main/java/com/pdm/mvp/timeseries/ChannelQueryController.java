package com.pdm.mvp.timeseries;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * v2 通道时序查询入口（文档 §6.1 GET /api/v2/telemetry/{code}/series）：
 * {code} 为测点 ID 或全局唯一测点编码，与 /api/v2/telemetry 契约入口并行。
 */
@RestController
public class ChannelQueryController {
    private final ChannelSeriesService series;
    public ChannelQueryController(ChannelSeriesService series) { this.series = series; }

    @GetMapping("/api/v2/telemetry/{code}/series")
    public ChannelSeriesService.ChannelSeriesView series(@PathVariable String code,
            @RequestParam(value = "start_ts", required = false) Long startTs,
            @RequestParam(value = "end_ts", required = false) Long endTs,
            @RequestParam(value = "step", defaultValue = "5m") String step) {
        return series.series(code, startTs, endTs, step);
    }
}
