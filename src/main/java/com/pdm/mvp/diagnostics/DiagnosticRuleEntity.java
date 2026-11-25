package com.pdm.mvp.diagnostics;

import com.pdm.mvp.asset.DeviceTypeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "pdm_diagnostic_rule")
public class DiagnosticRuleEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "device_type_id", nullable = false)
    private DeviceTypeEntity deviceType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private DiagnosticMetric metric;
    @Column(name = "frequency_order")
    private Double frequencyOrder;
    @Enumerated(EnumType.STRING) @Column(name = "comparison_operator", nullable = false, length = 3)
    private DiagnosticOperator operator;
    @Column(nullable = false)
    private double threshold;
    @Column(nullable = false)
    private int priority;
    @Column(nullable = false, length = 128)
    private String factor;
    @Column(nullable = false, length = 512)
    private String evidence;
    @Column(nullable = false, length = 512)
    private String advice;
    @Column(nullable = false)
    private boolean enabled;
    @Version @Column(nullable = false)
    private long version;

    protected DiagnosticRuleEntity() {}

    DiagnosticRuleEntity(DeviceTypeEntity deviceType, DiagnosticRuleRequest request) {
        this.deviceType = deviceType;
        apply(request);
    }

    void apply(DiagnosticRuleRequest request) {
        metric = request.metric();
        frequencyOrder = request.frequencyOrder();
        operator = request.operator();
        threshold = request.threshold();
        priority = request.priority();
        factor = request.factor().strip();
        evidence = request.evidence().strip();
        advice = request.advice().strip();
        enabled = request.enabled();
    }

    Long getId() { return id; }
    DeviceTypeEntity getDeviceType() { return deviceType; }
    DiagnosticMetric getMetric() { return metric; }
    Double getFrequencyOrder() { return frequencyOrder; }
    DiagnosticOperator getOperator() { return operator; }
    double getThreshold() { return threshold; }
    int getPriority() { return priority; }
    String getFactor() { return factor; }
    String getEvidence() { return evidence; }
    String getAdvice() { return advice; }
    boolean isEnabled() { return enabled; }
    long getVersion() { return version; }
}
