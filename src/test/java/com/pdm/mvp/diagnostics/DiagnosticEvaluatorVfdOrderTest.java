package com.pdm.mvp.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.DeviceTypeEntity;
import com.pdm.mvp.asset.DeviceTypeService;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.telemetry.TelemetryRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 变频实时转速阶次规则解算与零/空额定转速防御。 */
class DiagnosticEvaluatorVfdOrderTest {

    @Test
    void orderPeakRuleUsesRealtimeRpmOverNominalAndHandlesNullRpm() {
        DiagnosticRuleRepository rules = mock(DiagnosticRuleRepository.class);
        DiagnosticRuleEntity orderRule = mock(DiagnosticRuleEntity.class);
        when(orderRule.getMetric()).thenReturn(DiagnosticMetric.ORDER_PEAK_AMPLITUDE);
        when(orderRule.getFrequencyOrder()).thenReturn(1.0);
        when(orderRule.getOperator()).thenReturn(DiagnosticOperator.GT);
        when(orderRule.getThreshold()).thenReturn(0.3);
        when(orderRule.getFactor()).thenReturn("变频 1X 峰值超标");
        when(orderRule.getEvidence()).thenReturn("1X 超限");
        when(orderRule.getAdvice()).thenReturn("复核动平衡");
        when(rules.findByDeviceType_IdAndEnabledTrueOrderByPriorityDescIdAsc(anyLong())).thenReturn(List.of(orderRule));

        DeviceTypeEntity type = mock(DeviceTypeEntity.class);
        when(type.getId()).thenReturn(1L);
        when(type.getTypeName()).thenReturn("通用设备");
        DeviceEntity device = new DeviceEntity(new FactoryEntity("FACT_01", "一车间"), "PUMP_01", "变频泵",
                new BigDecimal("1500.00"), null, type);
        PointEntity point = new PointEntity(device, "DE", "驱动端", null);

        DiagnosticEvaluator evaluator = new DiagnosticEvaluator(rules, mock(DeviceTypeService.class));
        // 实时转速 1200 RPM (20Hz)，峰值在 20.0Hz（若仍按额定 1500 RPM=25Hz ±10% 搜索则会漏检）
        TelemetryRequest vfdSample = new TelemetryRequest(1732262400000L, "FACT_01", "PUMP_01", "DE", 0,
                new TelemetryRequest.TelemetryValues(1.0, 2.0, 3.0, 0.5, 0.1, 0.1, 0.02, 1200.0, 45.0, null),
                List.of(new TelemetryRequest.TopPeak(20.0, 0.45, 12.0)));
        DiagnosticMatch match = evaluator.evaluate(point, vfdSample, 0.4);
        assertEquals("变频 1X 峰值超标", match.factor());
        assertEquals(20.0, match.targetFrequencyHz(), 1e-6);

        // 非旋转设备 rpmNominal=null 且遥测无 rpm 时安全跳过阶次规则，不抛 NPE
        DeviceEntity nullRpmDevice = mock(DeviceEntity.class);
        when(nullRpmDevice.getDeviceType()).thenReturn(type);
        when(nullRpmDevice.getRpmNominal()).thenReturn(null);
        PointEntity nonRotating = new PointEntity(nullRpmDevice, "TRIP", "分闸", null);
        TelemetryRequest noRpmSample = new TelemetryRequest(1732262400000L, "FACT_01", "BKR_01", "TRIP", 0,
                new TelemetryRequest.TelemetryValues(1.0, 2.0, 3.0, 0.5, 0.1, 0.1, 0.02, null, 45.0, null),
                List.of(new TelemetryRequest.TopPeak(20.0, 0.45, 12.0)));
        DiagnosticMatch fallback = evaluator.evaluate(nonRotating, noRpmSample, 0.4);
        assertNull(fallback.ruleId());
    }
}
