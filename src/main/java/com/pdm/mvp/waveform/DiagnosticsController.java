package com.pdm.mvp.waveform;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 诊断资源接口（文档 §7.1）：
 * GET /api/v1/diagnostics/{pointId}/waveform —— 最近一帧高频波形快照与谱；
 * GET /api/v1/diagnostics/{pointId}/spectrum-baseline —— 频谱基线参考带（FFT/包络逐带 P50±3σMAD）；
 * POST /api/v1/diagnostics/{pointId}/capture —— 专家召唤波形抓取。
 */
@RestController
@RequestMapping("/api/v1/diagnostics")
public class DiagnosticsController {
    private final WaveformService waveforms;
    private final com.pdm.mvp.asset.PointRepository points;
    private final com.pdm.mvp.analytics.SpectrumBaselineService spectrumBaseline;

    public DiagnosticsController(WaveformService waveforms, com.pdm.mvp.asset.PointRepository points,
            com.pdm.mvp.analytics.SpectrumBaselineService spectrumBaseline) {
        this.waveforms = waveforms;
        this.points = points;
        this.spectrumBaseline = spectrumBaseline;
    }

    @GetMapping("/{pointId}/waveform")
    public WaveformService.WaveformView waveform(@PathVariable Long pointId) {
        return waveforms.latest(pointId);
    }

    /** 频谱基线参考带；测点不存在时 404，未冻结谱型以 LEARNING 空带返回。 */
    @GetMapping("/{pointId}/spectrum-baseline")
    public com.pdm.mvp.analytics.SpectrumBaselineService.SpectrumBaselineView spectrumBaseline(@PathVariable Long pointId) {
        if (!points.existsById(pointId))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "测点不存在");
        return spectrumBaseline.get(pointId);
    }

    @PostMapping("/{pointId}/capture")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public Map<String, Object> capture(@PathVariable Long pointId) {
        if (!waveforms.captureEnabled())
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                    "MQTT 未启用，无法下发波形召唤指令");
        boolean dispatched = waveforms.requestCapture(pointId);
        if (!dispatched)
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                    "召唤指令发布失败，请检查 MQTT 连接");
        return Map.of("dispatched", true, "message", "已向边缘下发 ON_DEMAND 波形抓取指令，请稍后刷新工作站");
    }
}
