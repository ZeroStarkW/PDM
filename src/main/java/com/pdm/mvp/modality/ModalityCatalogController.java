package com.pdm.mvp.modality;

import com.pdm.mvp.analytics.BaselineService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模态插件目录（文档 §4.2/§6.1）：按 modalityType 稳定排序输出已注册机理插件的特征域、
 * 学习策略、新鲜度时限与通道可配置参数，供前端动态工作站自描述渲染。
 */
@RestController
@RequestMapping("/api/v1/modalities")
public class ModalityCatalogController {
    private final ModalityPluginRegistry plugins;
    private final TelemetryFreshnessPolicy freshness;
    private final BaselineService baselines;

    public ModalityCatalogController(ModalityPluginRegistry plugins, TelemetryFreshnessPolicy freshness,
            BaselineService baselines) {
        this.plugins = plugins;
        this.freshness = freshness;
        this.baselines = baselines;
    }

    @GetMapping
    public List<ModalityCatalogEntry> catalog() {
        return catalog(plugins.all(), freshness::staleAfter, baselines::learningWindow);
    }

    /** 目录映射（包可见便于单测）：列表按 modalityType 字典序稳定排序（与返回体一致），字段名即前端契约。 */
    static List<ModalityCatalogEntry> catalog(Collection<ModalityPlugin> found,
            Function<String, Duration> staleAfter, Function<ModalityPlugin, Duration> learningWindow) {
        List<ModalityPlugin> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparing(ModalityPlugin::modalityType));
        return sorted.stream().map(plugin -> entry(plugin, staleAfter, learningWindow)).toList();
    }

    private static ModalityCatalogEntry entry(ModalityPlugin plugin, Function<String, Duration> staleAfter,
            Function<ModalityPlugin, Duration> learningWindow) {
        MechanismPluginStrategy mechanism = plugin instanceof MechanismPluginStrategy strategy ? strategy : null;
        var policy = plugin.baselineLearning();
        Duration window = learningWindow.apply(plugin);
        return new ModalityCatalogEntry(plugin.modalityType(), plugin.identifier(), plugin.cadence().name(),
                List.copyOf(plugin.requiredFeatures()), List.copyOf(plugin.baselineFeatureCodes()),
                List.copyOf(plugin.seriesFeatureCodes()),
                mechanism == null ? List.of() : List.copyOf(mechanism.lowerIsWorseFeatures()),
                plugin.triggerFeatureCode(), plugin.trendFeatureCode(),
                plugin.metadataFields().stream()
                        .map(field -> new ModalityCatalogEntry.MetadataFieldView(field.key(), field.label(),
                                field.description(), field.defaultValue()))
                        .toList(),
                plugin.alertsEnabled(), staleAfter.apply(plugin.modalityType()).toSeconds(),
                new ModalityCatalogEntry.BaselineLearningView(window.toSeconds(), policy.pooled()));
    }

    public record ModalityCatalogEntry(String modalityType, String pluginIdentifier, String cadence,
            List<String> requiredFeatures, List<String> baselineFeatureCodes, List<String> seriesFeatureCodes,
            List<String> lowerIsWorseFeatures,
            /** 告警触发幅值对应的特征编码（triggerAmplitude / baselineAmplitude 的来源特征）。 */
            String primaryFeatureCode,
            /** 趋势图默认特征编码。 */
            String trendFeatureCode,
            List<MetadataFieldView> metadataFields,
            boolean alertsEnabled, long staleAfterSeconds, BaselineLearningView baselineLearning) {

        public record MetadataFieldView(String key, String label, String description, Double defaultValue) {}

        public record BaselineLearningView(long windowSeconds, boolean pooled) {}
    }
}
