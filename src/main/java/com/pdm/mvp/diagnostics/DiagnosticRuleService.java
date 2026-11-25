package com.pdm.mvp.diagnostics;

import com.pdm.mvp.asset.DeviceTypeService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class DiagnosticRuleService {
    private final DeviceTypeService deviceTypes;
    private final DiagnosticRuleRepository rules;

    public DiagnosticRuleService(DeviceTypeService deviceTypes, DiagnosticRuleRepository rules) {
        this.deviceTypes = deviceTypes;
        this.rules = rules;
    }

    @Transactional(readOnly = true)
    public List<DiagnosticRuleView> list(Long typeId) {
        deviceTypes.require(typeId);
        return rules.findByDeviceType_IdOrderByPriorityDescIdAsc(typeId).stream()
                .map(DiagnosticRuleView::from)
                .toList();
    }

    public DiagnosticRuleView create(Long typeId, DiagnosticRuleRequest request) {
        var type = deviceTypes.require(typeId);
        validate(type, request);
        return DiagnosticRuleView.from(rules.save(new DiagnosticRuleEntity(type, request)));
    }

    public DiagnosticRuleView update(Long typeId, Long id, DiagnosticRuleUpdateRequest request) {
        var settings = request.settings();
        var type = deviceTypes.require(typeId);
        validate(type, settings);
        var rule = rules.findByIdAndDeviceType_Id(id, typeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "此设备类型下没有该诊断规则"));
        if (rule.getVersion() != request.expectedVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "诊断规则已被修改，请重新加载后再编辑");
        }
        rule.apply(settings);
        rules.flush();
        return DiagnosticRuleView.from(rule);
    }

    /** Seed defaults only for a new device type; existing user-authored (including disabled) rules are untouched. */
    public void seedDefaults(Long typeId, List<DiagnosticRuleRequest> defaults) {
        var type = deviceTypes.require(typeId);
        if (!rules.findByDeviceType_IdOrderByPriorityDescIdAsc(typeId).isEmpty()) return;
        for (var request : defaults) {
            validate(type, request);
            rules.save(new DiagnosticRuleEntity(type, request));
        }
    }

    private static void validate(com.pdm.mvp.asset.DeviceTypeEntity type, DiagnosticRuleRequest request) {
        if (!DiagnosticMetric.supportedForDeviceType(type.getTypeCode()).contains(request.metric())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "诊断指标不适用于设备类型 " + type.getTypeCode());
        }
        double threshold = request.threshold();
        if (!Double.isFinite(threshold) || (request.metric() != DiagnosticMetric.TEMPERATURE && threshold < 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "阈值必须为有限数值，仅温度允许负值");
        }
        if (request.metric() == DiagnosticMetric.CONTACT_WEAR_RATIO && threshold > 1.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "触头磨损率阈值必须在 0–1 区间");
        }
        Double order = request.frequencyOrder();
        if (request.metric().requiresFrequencyOrder()) {
            if (order == null || !Double.isFinite(order) || order < 0.25 || order > 20) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "峰值规则必须指定 0.25 至 20 之间的转频阶次");
            }
        } else if (order != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只有阶次峰值规则支持指定转频阶次");
        }
    }

    public record DiagnosticRuleView(Long id, Long deviceTypeId, DiagnosticMetric metric, Double frequencyOrder,
            DiagnosticOperator operator, double threshold, int priority, String factor, String evidence, String advice,
            boolean enabled, long version) {
        static DiagnosticRuleView from(DiagnosticRuleEntity rule) {
            return new DiagnosticRuleView(rule.getId(), rule.getDeviceType().getId(), rule.getMetric(), rule.getFrequencyOrder(),
                    rule.getOperator(), rule.getThreshold(), rule.getPriority(), rule.getFactor(), rule.getEvidence(),
                    rule.getAdvice(), rule.isEnabled(), rule.getVersion());
        }
    }
}
