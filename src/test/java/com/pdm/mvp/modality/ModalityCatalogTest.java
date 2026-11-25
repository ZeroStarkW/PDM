package com.pdm.mvp.modality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pdm.mvp.analytics.DegradationTracker;
import com.pdm.mvp.analytics.HealthAssessment;
import com.pdm.mvp.analytics.HealthIndexCalculator;
import com.pdm.mvp.analytics.RotatingVibrationPlugin;
import com.pdm.mvp.asset.DeviceTypeEntity;
import com.pdm.mvp.asset.DeviceTypeService;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.controlvalve.ControlValvePlugin;
import com.pdm.mvp.controlvalve.ControlValveProperties;
import com.pdm.mvp.diagnostics.DiagnosticMatch;
import com.pdm.mvp.mcsa.McsaPlugin;
import com.pdm.mvp.mcsa.McsaProperties;
import com.pdm.mvp.switchgear.CircuitBreakerPlugin;
import com.pdm.mvp.switchgear.SwitchgearProperties;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 模态插件目录：真实四插件注册后的目录映射（排序、事件型节律、学习策略、机理参数、告警开关与触发/趋势特征契约）。
 */
class ModalityCatalogTest {

    @Test
    void catalogListsAllRegisteredPluginsSortedByModalityType() {
        var registry = new ModalityPluginRegistry(List.of(vibrationPlugin(), breakerPlugin(),
                valvePlugin(), mcsaPlugin()));
        List<ModalityCatalogController.ModalityCatalogEntry> catalog = ModalityCatalogController.catalog(
                registry.all(),
                modality -> "SWITCHGEAR_EVENT".equals(modality) ? Duration.ofDays(30) : Duration.ofMinutes(5),
                plugin -> {
                    Duration window = plugin.baselineLearning().window();
                    return window != null ? window : Duration.ofMinutes(6);
                });

        assertEquals(List.of("CONTROL_VALVE_PROCESS", "MCSA", "ROTATING_VIB", "SWITCHGEAR_EVENT"),
                catalog.stream().map(ModalityCatalogController.ModalityCatalogEntry::modalityType).toList(),
                "目录必须按 modalityType 升序稳定输出");
    }

    @Test
    void breakerEntryDeclaresEventCadencePooledLearningAndLongFreshness() {
        var registry = new ModalityPluginRegistry(List.of(breakerPlugin()));
        var entry = catalogEntry(registry, ModalityTypes.SWITCHGEAR_EVENT);

        assertEquals("switchgearEventPlugin", entry.pluginIdentifier());
        assertEquals("EVENT", entry.cadence());
        assertTrue(entry.baselineLearning().pooled());
        assertEquals(Duration.ofDays(365).toSeconds(), entry.baselineLearning().windowSeconds());
        assertEquals(Duration.ofDays(30).toSeconds(), entry.staleAfterSeconds());
        assertEquals("trip_duration_ms", entry.primaryFeatureCode());
        assertTrue(entry.alertsEnabled());
    }

    @Test
    void valveEntryCarriesFourMetadataFieldsWithDefaults() {
        var registry = new ModalityPluginRegistry(List.of(valvePlugin()));
        var entry = catalogEntry(registry, ModalityTypes.CONTROL_VALVE_PROCESS);

        assertEquals("controlValvePlugin", entry.pluginIdentifier());
        assertEquals(Set.of("deadband_attention_ratio", "deadband_danger_ratio", "leak_attention_rms",
                        "leak_danger_rms"),
                entry.metadataFields().stream()
                        .map(ModalityCatalogController.ModalityCatalogEntry.MetadataFieldView::key)
                        .collect(java.util.stream.Collectors.toSet()));
        var attention = metadataField(entry, "deadband_attention_ratio");
        assertEquals(2.5, attention.defaultValue(), 1e-9);
        assertEquals(5.0, metadataField(entry, "deadband_danger_ratio").defaultValue(), 1e-9);
        assertEquals(0.2, metadataField(entry, "leak_attention_rms").defaultValue(), 1e-9);
        assertEquals(0.5, metadataField(entry, "leak_danger_rms").defaultValue(), 1e-9);
        assertEquals("hysteresis_deadband_pct", entry.primaryFeatureCode());
        assertEquals(Duration.ofHours(1).toSeconds(), entry.baselineLearning().windowSeconds());
        assertEquals(Duration.ofMinutes(5).toSeconds(), entry.staleAfterSeconds(), "连续型模态沿用默认新鲜度");
    }

    @Test
    void vibrationEntryDeclaresTriggerAndTrendFeaturesWithDefaultLearningWindow() {
        var registry = new ModalityPluginRegistry(List.of(vibrationPlugin()));
        var entry = catalogEntry(registry, ModalityTypes.ROTATING_VIB);

        assertEquals("freq_1x_amp", entry.primaryFeatureCode(), "旋转振动告警触发幅值取 1X 幅值（triggerFeatureCode）");
        assertEquals("v_rms", entry.trendFeatureCode(), "旋转振动趋势图默认特征为 v_rms");
        assertTrue(entry.alertsEnabled());
        assertTrue(entry.lowerIsWorseFeatures().isEmpty());
        assertEquals(Duration.ofMinutes(6).toSeconds(), entry.baselineLearning().windowSeconds(),
                "插件未声明窗口时应回落全局学习时长");
        assertEquals(Duration.ofMinutes(5).toSeconds(), entry.staleAfterSeconds());
        assertTrue(entry.seriesFeatureCodes().containsAll(entry.requiredFeatures()));
        assertTrue(entry.seriesFeatureCodes().containsAll(entry.baselineFeatureCodes()));
    }

    /** 前端契约：primaryFeatureCode = 插件 triggerFeatureCode，trendFeatureCode = 插件 trendFeatureCode（JSON 键名一致）。 */
    @Test
    void entriesExposeTriggerAndTrendFeatureCodesPerPlugin() {
        var mcsa = catalogEntry(new ModalityPluginRegistry(List.of(mcsaPlugin())), ModalityTypes.MCSA);
        assertEquals("sideband_ratio", mcsa.primaryFeatureCode());
        assertEquals("sideband_amp_db", mcsa.trendFeatureCode());

        var breaker = catalogEntry(new ModalityPluginRegistry(List.of(breakerPlugin())), ModalityTypes.SWITCHGEAR_EVENT);
        assertEquals("trip_duration_ms", breaker.primaryFeatureCode());
        assertEquals("trip_duration_ms", breaker.trendFeatureCode(), "机理策略插件的触发/趋势特征均取主特征");

        var valve = catalogEntry(new ModalityPluginRegistry(List.of(valvePlugin())), ModalityTypes.CONTROL_VALVE_PROCESS);
        assertEquals("hysteresis_deadband_pct", valve.primaryFeatureCode());
        assertEquals("hysteresis_deadband_pct", valve.trendFeatureCode());

        String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(breaker);
        assertTrue(json.contains("\"primaryFeatureCode\":\"trip_duration_ms\""), json);
        assertTrue(json.contains("\"trendFeatureCode\":\"trip_duration_ms\""), "trendFeatureCode 必须按该键名输出: " + json);
    }

    @Test
    void pluginWithoutDeclarationLeavesTriggerAndTrendNull() {
        var entry = catalogEntry(new ModalityPluginRegistry(List.of(new BarePlugin())), "BARE_MODALITY");
        assertNull(entry.primaryFeatureCode());
        assertNull(entry.trendFeatureCode());
    }

    @Test
    void mcsaEntryKeepsAlertsDisabledByDefault() {
        var registry = new ModalityPluginRegistry(List.of(mcsaPlugin()));
        var entry = catalogEntry(registry, ModalityTypes.MCSA);

        assertTrue(!entry.alertsEnabled(), "MCSA 默认关闭告警");
        assertEquals("McsaPlugin", entry.pluginIdentifier());
        assertEquals("CONTINUOUS", entry.cadence());
    }

    private ModalityCatalogController.ModalityCatalogEntry catalogEntry(ModalityPluginRegistry registry,
            String modalityType) {
        List<ModalityCatalogController.ModalityCatalogEntry> catalog = ModalityCatalogController.catalog(
                registry.all(),
                modality -> "SWITCHGEAR_EVENT".equals(modality) ? Duration.ofDays(30) : Duration.ofMinutes(5),
                plugin -> {
                    Duration window = plugin.baselineLearning().window();
                    return window != null ? window : Duration.ofMinutes(6);
                });
        return catalog.stream().filter(entry -> entry.modalityType().equals(modalityType)).findFirst().orElseThrow();
    }

    private ModalityCatalogController.ModalityCatalogEntry.MetadataFieldView metadataField(
            ModalityCatalogController.ModalityCatalogEntry entry, String key) {
        return entry.metadataFields().stream().filter(field -> field.key().equals(key)).findFirst().orElseThrow();
    }

    private RotatingVibrationPlugin vibrationPlugin() {
        return new RotatingVibrationPlugin(new HealthIndexCalculator(), null,
                new DegradationTracker(Duration.ofHours(24)));
    }

    private CircuitBreakerPlugin breakerPlugin() {
        return new CircuitBreakerPlugin(new SwitchgearProperties(true, 1.10, 1.20, 0.80,
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)));
    }

    private ControlValvePlugin valvePlugin() {
        return new ControlValvePlugin(new ControlValveProperties(true, 2.5, 5.0, 0.2, 0.5, Duration.ofHours(1)), Duration.ofHours(24));
    }

    private McsaPlugin mcsaPlugin() {
        DeviceTypeService deviceTypes = mock(DeviceTypeService.class);
        var general = mock(DeviceTypeEntity.class);
        when(general.getId()).thenReturn(1L);
        when(general.getTypeName()).thenReturn("通用设备");
        when(deviceTypes.general()).thenReturn(general);
        return new McsaPlugin(new McsaProperties(false, 20, 100, 15, 54, 40), deviceTypes);
    }

    /** 未声明触发/趋势特征的最小插件。 */
    private static final class BarePlugin implements ModalityPlugin {
        @Override public String modalityType() { return "BARE_MODALITY"; }
        @Override public Set<String> requiredFeatures() { return Set.of("f1"); }
        @Override public ConditionGate gate(TelemetryEnvelope envelope) { return ConditionGate.accept(); }
        @Override public Set<String> spikeFeatureCodes() { return Set.of("f1"); }
        @Override public Set<String> baselineFeatureCodes() { return Set.of("f1"); }
        @Override public Set<String> scoringBaselineFeatures() { return Set.of("f1"); }
        @Override public Map<String, Double> baselineFeatureValues(TelemetryEnvelope envelope) { return Map.of(); }
        @Override public DiagnosticMatch diagnose(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline) { return null; }
        @Override public HealthAssessment score(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline,
                DiagnosticMatch diagnosis) { return null; }
        @Override public AlertTrigger alertTrigger(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline) { return null; }
        @Override public boolean alertsEnabled() { return false; }
    }
}
