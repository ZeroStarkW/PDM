package com.pdm.mvp.analytics;

import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.modality.ModalityPlugin;
import com.pdm.mvp.modality.ModalityPluginRegistry;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通道基线查询（文档 §6.1 GET /api/v2/channels/{code}/baseline）：
 * 输出多模基线矩阵（各特征 P50/CV/样本数/锁定状态）与通道模型元数据。
 */
@RestController
public class ChannelBaselineController {
    private final AssetLookup assets;
    private final BaselineService baselines;
    private final ModalityPluginRegistry plugins;

    public ChannelBaselineController(AssetLookup assets, BaselineService baselines, ModalityPluginRegistry plugins) {
        this.assets = assets;
        this.baselines = baselines;
        this.plugins = plugins;
    }

    @GetMapping("/api/v2/channels/{code}/baseline")
    @Transactional(readOnly = true)
    public ChannelBaselineView baseline(@PathVariable String code) {
        PointEntity point = assets.resolvePoint(code);
        var plugin = plugins.require(point.getModalityType());
        // 学习期预览：无持久化行的插件基线特征以当前窗口样本现算 PROVISIONAL 行（只读），锁定前工作台不再空白
        return view(point, plugin, baselines.getProvisional(point.getId(), plugin));
    }

    /** 视图组装（包可见便于单测）：学习窗口为按模态解析后的窗口秒数，pooled 为插件学习策略原样输出。 */
    ChannelBaselineView view(PointEntity point, ModalityPlugin plugin, BaselineService.BaselineState state) {
        var device = point.getDevice();
        List<ChannelBaselineView.FeatureState> features = state.featureStates().stream()
                .map(f -> new ChannelBaselineView.FeatureState(f.featureCode(), f.p50(), f.cv(),
                        f.sampleCount(), f.status(), f.lockedAt(), f.binIndex(), f.regimeLabel()))
                .toList();
        return new ChannelBaselineView(point.getPointCode(), point.getId(), device.getId(), device.getDeviceCode(),
                device.getFactory().getFactoryCode(), plugin.modalityType(), plugin.identifier(),
                plugin.cadence().name(), state.status(), state.learnedSamples(), state.minimumSamples(),
                state.lockedAt(), state.warning(), baselines.learningWindow(plugin).toSeconds(),
                plugin.baselineLearning().pooled(), features);
    }

    /** 通道基线视图（前端契约字段名与示例一致；特征编码保持 snake_case）。 */
    public record ChannelBaselineView(String channelCode, Long pointId, Long deviceId, String deviceCode,
            String factoryCode, String modalityType, String pluginIdentifier, String cadence, String status,
            int learnedSamples, int minimumSamples, Instant lockedAt, String warning, long learningWindowSeconds,
            boolean pooled, List<FeatureState> featureStates) {

        public record FeatureState(String featureKey, Double baselineP50, Double cvValue, int sampleCount,
                String status, Instant lockedAt, short binIndex, String regimeLabel) {

            /** 兼容构造器：单工况行默认额定/默认工况箱（存量调用方零改动）。 */
            public FeatureState(String featureKey, Double baselineP50, Double cvValue, int sampleCount,
                    String status, Instant lockedAt) {
                this(featureKey, baselineP50, cvValue, sampleCount, status, lockedAt, (short) 0, "默认/额定工况");
            }
        }
    }
}
