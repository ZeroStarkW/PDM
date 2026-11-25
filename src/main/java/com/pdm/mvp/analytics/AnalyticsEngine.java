package com.pdm.mvp.analytics;

import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.diagnostics.DiagnosticEvaluator;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.modality.ModalityPlugin;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 共享分析内核：尖峰抑制 → 基线学习 → 模态插件诊断与评分。
 * 模态相关的特征定义与物理解释全部委托插件；分档与防抖留在共享链路。
 */
@Service
public class AnalyticsEngine {
    private final SpikeFilter spikeFilter;
    private final BaselineService baselineService;
    private final com.pdm.mvp.modality.ModalityPluginRegistry plugins;
    private final DiagnosticEvaluator diagnostics;

    public AnalyticsEngine(SpikeFilter spikeFilter, BaselineService baselineService,
            com.pdm.mvp.modality.ModalityPluginRegistry plugins) {
        this(spikeFilter, baselineService, plugins, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AnalyticsEngine(SpikeFilter spikeFilter, BaselineService baselineService,
            com.pdm.mvp.modality.ModalityPluginRegistry plugins, DiagnosticEvaluator diagnostics) {
        this.spikeFilter = spikeFilter;
        this.baselineService = baselineService;
        this.plugins = plugins;
        this.diagnostics = diagnostics;
    }

    public Optional<AnalyticsOutput> observe(PointEntity point, TelemetryEnvelope envelope, ModalityPlugin plugin) {
        Optional<SpikeFilter.FilteredSample> filtered = spikeFilter.accept(point.getId(), envelope, plugin);
        if (filtered.isEmpty()) return Optional.empty();
        TelemetryEnvelope sample = filtered.get().sample();
        if (filtered.get().spike()) return Optional.of(new AnalyticsOutput(sample, null, "SPIKE_SUPPRESSED", 0, null));
        BaselineSnapshot baseline = baselineService.observe(point, sample, plugin);
        if (!"ACTIVE".equals(baseline.status()) || !baseline.hasAll(plugin.scoringBaselineFeatures()))
            return Optional.of(new AnalyticsOutput(sample, null,
                    baseline.warning() == null ? "BASELINE_LEARNING" : baseline.warning(), 0, baseline));
        plugin.observeDegradation(point, sample, baseline);
        var diagnosis = plugin.diagnose(point, sample, baseline);
        HealthAssessment assessment = plugin.score(point, sample, baseline, diagnosis);
        if (diagnostics != null && !ModalityTypes.ROTATING_VIB.equals(plugin.modalityType())
                && diagnosis.ruleId() == null && !assessment.hardLimitBreached()) {
            var configured = diagnostics.evaluateConfigured(point, sample, baseline);
            if (configured.isPresent()) {
                diagnosis = configured.get();
                assessment = new HealthAssessment(assessment.hiRelative(), assessment.hiAbsolute(),
                        assessment.healthIndex(), assessment.level(), assessment.statusCode(), diagnosis,
                        assessment.slopeDkDt(), assessment.etaDyn(), assessment.contributions(),
                        assessment.hardLimitBreached());
            }
        }
        return Optional.of(new AnalyticsOutput(sample, assessment, assessment.level(), assessment.statusCode(), baseline));
    }

    public void reset(Long pointId) {
        spikeFilter.reset(pointId);
        for (var plugin : plugins.all()) plugin.resetRuntimeState(pointId);
    }

    public record AnalyticsOutput(TelemetryEnvelope sample, HealthAssessment assessment, String status,
            int statusCode, BaselineSnapshot baseline) {}
}
