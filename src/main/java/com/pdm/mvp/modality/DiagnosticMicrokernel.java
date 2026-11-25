package com.pdm.mvp.modality;

import com.pdm.mvp.alert.DeviceHealthWindow;
import com.pdm.mvp.analytics.HealthAssessment;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import org.springframework.stereotype.Component;

/**
 * 统一微内核调度引擎（设计说明书 §4.4）：工况门禁 → 插件机理诊断与评分 → 统一分档
 * → M-of-N 滑动防抖（绝对物理限值击穿直通，事件型模态断档不清窗）。
 */
@Component
public class DiagnosticMicrokernel {
    static final int WINDOW_SIZE = 5;
    static final int MIN_LOW_SCORES = 4;
    static final double LOW_SCORE = 50.0;

    private final DeviceHealthWindow healthWindow;

    public DiagnosticMicrokernel(DeviceHealthWindow healthWindow) {
        this.healthWindow = healthWindow;
    }

    /** 一次调度结论：综合健康分、报警档位、确定性归因、是否触发外部告警与门禁/击穿标识。 */
    public record ExecutionOutcome(double finalHealthScore, String alarmLevel, String defectAttribution,
            boolean shouldTriggerAlarm, boolean hardLimitBreached, boolean gated) {}

    /** 门禁 → 插件诊断与评分 → 分档 → M-of-N 防抖（硬限击穿直通）。 */
    public ExecutionOutcome execute(PointEntity point, Long deviceId, ModalityPlugin plugin,
            TelemetryEnvelope sample, BaselineSnapshot baseline) {
        var gate = plugin.gate(sample);
        if (!gate.assessable())
            return new ExecutionOutcome(100.0, "STANDBY", gate.message(), false, false, true);
        var diagnosis = plugin.diagnose(point, sample, baseline);
        HealthAssessment assessment = plugin.score(point, sample, baseline, diagnosis);
        boolean trigger = shouldTriggerAlarm(deviceId, point.getId(), sample.timestamp(), assessment,
                plugin.cadence() == ModalityPlugin.Cadence.EVENT);
        // 报警档位沿用文档命名：健康（≥70）报 NORMAL，其余沿用统一分档名
        String alarmLevel = assessment.healthIndex() >= 70.0 ? "NORMAL" : assessment.level();
        return new ExecutionOutcome(assessment.healthIndex(), alarmLevel,
                diagnosis == null ? null : diagnosis.factor(),
                trigger, assessment.hardLimitBreached(), false);
    }

    /** 仅防抖判定：连续型超过 15 分钟断档清窗，事件型不清窗；硬限击穿直接放行。
     *  硬限击穿样本同样计入 M-of-N 窗口（否则击穿后的低分序列要多等一个样本才触发）。 */
    public boolean shouldTriggerAlarm(Long deviceId, Long pointId, long timestamp, HealthAssessment assessment,
            boolean eventCadence) {
        boolean debounced = healthWindow.observe(deviceId, pointId, timestamp, assessment.healthIndex(), !eventCadence);
        return assessment.hardLimitBreached() || debounced;
    }
}
