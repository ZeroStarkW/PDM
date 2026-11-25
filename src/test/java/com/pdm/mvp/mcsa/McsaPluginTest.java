package com.pdm.mvp.mcsa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pdm.mvp.asset.DeviceTypeEntity;
import com.pdm.mvp.asset.DeviceTypeService;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.modality.ModalityPlugin.ConditionGate;
import com.pdm.mvp.telemetry.OperatingCondition;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * MCSA 合成电流样本验证：负载门禁、频率特征评分、候选阈值与确定性归因边界。
 */
class McsaPluginTest {
    private static final long NOW = 1_780_000_000_000L;

    @Test
    void unsteadyOrOutOfBandLoadIsNotAssessable() {
        var plugin = plugin(false);
        ConditionGate unsteady = plugin.gate(sample(-93.0, -33.0, 75.0, false));
        assertFalse(unsteady.assessable());
        assertEquals("CONDITION_REJECTED", unsteady.status());
        assertEquals(6, unsteady.statusCode());

        ConditionGate lowLoad = plugin.gate(sample(-93.0, -33.0, 10.0, true));
        assertFalse(lowLoad.assessable());
    }

    @Test
    void steadyLoadWithinBandIsAssessable() {
        var plugin = plugin(false);
        ConditionGate gate = plugin.gate(sample(-93.0, -33.0, 75.0, true));
        assertTrue(gate.assessable());
    }

    @Test
    void slipBeyondPhysicalRangeIsRejected() {
        var plugin = plugin(false);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> plugin.validateFeatures(sample(-93.0, -33.0, 75.0, true, 30.0)));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void healthySyntheticSampleScoresFullHealthWithoutCalibration() {
        var plugin = plugin(false);
        var baseline = baseline(0.001);
        var assessment = plugin.score(null, sample(-93.0, -33.0, 75.0, true), baseline, null);
        assertEquals(100.0, assessment.hiAbsolute(), 0.001, "未启用校准时绝对分不参与评分");
        assertEquals(100.0, assessment.healthIndex(), 0.001);
        assertEquals("HEALTHY", assessment.level());
        assertFalse(plugin.alertsEnabled(), "未验收前告警阈值不启用");
    }

    @Test
    void sidebandGrowthAgainstBaselineDropsHealthScore() {
        var plugin = plugin(false);
        var baseline = baseline(0.001);
        var assessment = plugin.score(null, sample(-63.0, -33.0, 75.0, true), baseline, null);
        assertTrue(assessment.healthIndex() < 30.0, "边频带显著增大应显著降分，实际 " + assessment.healthIndex());
        assertEquals("DANGER", assessment.level());
    }

    @Test
    void calibratedAbsoluteThresholdsUseCandidateDbValues() {
        var plugin = plugin(true);
        var baseline = baseline(0.001);
        assertEquals(100.0, plugin.score(null, sample(-93.0, -33.0, 75.0, true), baseline, null).hiAbsolute(), 0.001,
                "间距 60 dB（>54）应满分");
        assertEquals(50.0, plugin.score(null, sample(-83.0, -33.0, 75.0, true), baseline, null).hiAbsolute(), 0.001,
                "间距 50 dB（40~54）应落在关注档");
        assertEquals(20.0, plugin.score(null, sample(-72.0, -33.0, 75.0, true), baseline, null).hiAbsolute(), 0.001,
                "间距 39 dB（≤40）应高风险档");
        assertTrue(plugin.alertsEnabled());
    }

    @Test
    void calibratedDiagnosisAttributesSidebandGrowthToRotorBars() {
        var plugin = plugin(true);
        var match = plugin.diagnose(point(), sample(-83.0, -33.0, 75.0, true), baseline(0.001));
        assertTrue(match.factor().contains("转子导条"), "应输出转子导条疑似归因");
        assertEquals(Double.valueOf(54.0), match.threshold());
    }

    @Test
    void uncalibratedDiagnosisAvoidsDeterministicConclusion() {
        var plugin = plugin(false);
        var match = plugin.diagnose(point(), sample(-63.0, -33.0, 75.0, true), baseline(0.001));
        assertTrue(match.factor().contains("未启用确定性归因"));
        assertTrue(match.advice().contains("现场试点验收"));
    }

    @Test
    void baselineFeatureValuesContainDerivedSidebandRatio() {
        var plugin = plugin(false);
        var values = plugin.baselineFeatureValues(sample(-90.0, -30.0, 75.0, true));
        assertEquals(Math.pow(10.0, -3.0), values.get("sideband_ratio"), 1e-12);
        assertEquals(-30.0, values.get("carrier_amp_db"), 1e-9);
    }

    /** 时序查询特征列只含实际落库的指标：派生 sideband_ratio 仅存在于基线特征表，列入只会触发列缺失重试。 */
    @Test
    void seriesFeatureCodesExcludeDerivedSidebandRatioButKeepItAsTriggerFeature() {
        var plugin = plugin(false);
        assertEquals(java.util.Set.of("mains_freq_hz", "slip_pct", "load_pct", "sideband_amp_db", "carrier_amp_db"),
                plugin.seriesFeatureCodes());
        assertTrue(plugin.baselineFeatureCodes().contains("sideband_ratio"), "sideband_ratio 仍参与基线学习");
        assertEquals("sideband_ratio", plugin.triggerFeatureCode(), "告警触发幅值仍取派生边频比");
        assertEquals("sideband_amp_db", plugin.trendFeatureCode());
    }

    /** 负载只读工况：load_pct 指标恒为 100 而工况负载为 75 时，门禁按工况判定。 */
    @Test
    void loadGateReadsOperatingConditionNotTheMetric() {
        var plugin = plugin(false);
        var contradicted = new TelemetryEnvelope(2, "MCSA", NOW, "FACT_01", "MOTOR_M01", "CURRENT", 0,
                new OperatingCondition(1440.0, 75.0, true),
                java.util.Map.of("mains_freq_hz", 50.0, "slip_pct", 2.5, "load_pct", 100.0,
                        "sideband_amp_db", -93.0, "carrier_amp_db", -33.0),
                null, null);
        assertTrue(plugin.gate(contradicted).assessable());
        var outOfBand = new TelemetryEnvelope(2, "MCSA", NOW, "FACT_01", "MOTOR_M01", "CURRENT", 0,
                new OperatingCondition(1440.0, 10.0, true),
                java.util.Map.of("mains_freq_hz", 50.0, "slip_pct", 2.5, "load_pct", 100.0,
                        "sideband_amp_db", -93.0, "carrier_amp_db", -33.0),
                null, null);
        assertFalse(plugin.gate(outOfBand).assessable(), "工况负载 10% 低于下限，指标里的 load_pct 不参与门禁");
    }

    private McsaPlugin plugin(boolean alertsEnabled) {
        var properties = new McsaProperties(alertsEnabled, 20, 100, 15, 54, 40);
        DeviceTypeService deviceTypes = mock(DeviceTypeService.class);
        var general = mock(DeviceTypeEntity.class);
        when(general.getId()).thenReturn(1L);
        when(general.getTypeName()).thenReturn("通用设备");
        when(deviceTypes.general()).thenReturn(general);
        return new McsaPlugin(properties, deviceTypes);
    }

    private com.pdm.mvp.modality.BaselineSnapshot baseline(double ratio) {
        return new com.pdm.mvp.modality.BaselineSnapshot(1L, "MCSA", "ACTIVE", 200,
                java.time.Instant.ofEpochMilli(NOW),
                null, java.util.Map.of("sideband_ratio", ratio, "carrier_amp_db", -33.0),
                java.util.Map.of("sideband_ratio", 0.05));
    }

    private PointEntity point() {
        PointEntity point = mock(PointEntity.class);
        com.pdm.mvp.asset.DeviceEntity device = mock(com.pdm.mvp.asset.DeviceEntity.class);
        when(point.getDevice()).thenReturn(device);
        when(device.getDeviceType()).thenReturn(null);
        return point;
    }

    private TelemetryEnvelope sample(double sidebandDb, double carrierDb, double loadPct, boolean steadyState) {
        return sample(sidebandDb, carrierDb, loadPct, steadyState, 2.5);
    }

    private TelemetryEnvelope sample(double sidebandDb, double carrierDb, double loadPct, boolean steadyState,
            double slipPct) {
        return new TelemetryEnvelope(2, "MCSA", NOW, "FACT_01", "MOTOR_M01", "CURRENT", 0,
                new OperatingCondition(1440.0, loadPct, steadyState),
                java.util.Map.of("mains_freq_hz", 50.0, "slip_pct", slipPct,
                        "sideband_amp_db", sidebandDb, "carrier_amp_db", carrierDb),
                null, null);
    }
}
