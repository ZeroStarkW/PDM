package com.pdm.mvp.alert;

import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.analytics.DeviceHealthRollup;
import com.pdm.mvp.diagnostics.DiagnosticMatch;
import com.pdm.mvp.diagnostics.DiagnosticMetric;
import com.pdm.mvp.diagnostics.DiagnosticOperator;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "pdm_alert_event")
public class AlertEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "point_id", nullable = false)
    private PointEntity point;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "device_id", nullable = false)
    private DeviceEntity device;
    @Column(name = "alert_level", nullable = false, length = 16)
    private String alertLevel;
    @Column(name = "health_index", nullable = false, precision = 5, scale = 2)
    private BigDecimal healthIndex;
    @Column(name = "dominant_factor", nullable = false, length = 128, updatable = false)
    private String dominantFactor;
    @Column(name = "diagnostic_device_type_id", updatable = false)
    private Long diagnosticDeviceTypeId;
    @Column(name = "diagnostic_device_type_name", length = 128, updatable = false)
    private String diagnosticDeviceTypeName;
    @Column(name = "diagnostic_rule_id", updatable = false)
    private Long diagnosticRuleId;
    @Column(name = "diagnostic_rule_version", updatable = false)
    private Long diagnosticRuleVersion;
    @Enumerated(EnumType.STRING) @Column(name = "diagnostic_metric", length = 32, updatable = false)
    private DiagnosticMetric diagnosticMetric;
    @Enumerated(EnumType.STRING) @Column(name = "diagnostic_operator", length = 3, updatable = false)
    private DiagnosticOperator diagnosticOperator;
    @Column(name = "diagnostic_threshold", updatable = false)
    private Double diagnosticThreshold;
    @Column(name = "diagnostic_observed_value", updatable = false)
    private Double diagnosticObservedValue;
    @Column(name = "diagnostic_frequency_order", updatable = false)
    private Double diagnosticFrequencyOrder;
    @Column(name = "diagnostic_observed_frequency_hz", updatable = false)
    private Double diagnosticObservedFrequencyHz;
    @Column(name = "diagnostic_target_frequency_hz", updatable = false)
    private Double diagnosticTargetFrequencyHz;
    @Column(name = "diagnostic_evidence", length = 512, updatable = false)
    private String diagnosticEvidence;
    @Column(name = "diagnostic_advice", length = 512, updatable = false)
    private String diagnosticAdvice;
    @Column(name = "trigger_amp", nullable = false, precision = 10, scale = 4)
    private BigDecimal triggerAmp;
    @Column(name = "base_amp", nullable = false, precision = 10, scale = 4)
    private BigDecimal baseAmp;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "evidence_snapshot", columnDefinition = "jsonb")
    private List<DeviceHealthRollup.Evidence> evidenceSnapshot;
    /** 确定性根因明细：主因、dk/dt、η_dyn 与各特征贡献率。 */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "defect_detail", columnDefinition = "jsonb")
    private java.util.Map<String, Object> defectDetail;
    @Column(name = "raw_snapshot_url", length = 512)
    private String rawSnapshotUrl;
    @Column(nullable = false)
    private short status = 0;
    @Column(nullable = false)
    private boolean notified = false;
    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount = 1;
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "suppressed_at")
    private Instant suppressedAt;

    protected AlertEntity() {}
    public AlertEntity(PointEntity point, String level, double healthIndex, DiagnosticMatch diagnosis,
            double triggerAmp, double baseAmp, List<DeviceHealthRollup.Evidence> evidenceSnapshot) {
        this.point = point; this.device = point.getDevice(); this.alertLevel = level;
        this.healthIndex = BigDecimal.valueOf(healthIndex);
        this.triggerAmp = BigDecimal.valueOf(triggerAmp); this.baseAmp = BigDecimal.valueOf(baseAmp);
        this.evidenceSnapshot = evidenceSnapshot;
        dominantFactor = diagnosis.factor();
        diagnosticDeviceTypeId = diagnosis.deviceTypeId();
        diagnosticDeviceTypeName = diagnosis.deviceTypeName();
        diagnosticRuleId = diagnosis.ruleId();
        diagnosticRuleVersion = diagnosis.ruleVersion();
        diagnosticMetric = diagnosis.metric();
        diagnosticOperator = diagnosis.operator();
        diagnosticThreshold = diagnosis.threshold();
        diagnosticObservedValue = diagnosis.observedValue();
        diagnosticFrequencyOrder = diagnosis.frequencyOrder();
        diagnosticObservedFrequencyHz = diagnosis.observedFrequencyHz();
        diagnosticTargetFrequencyHz = diagnosis.targetFrequencyHz();
        diagnosticEvidence = diagnosis.evidence();
        diagnosticAdvice = diagnosis.advice();
    }
    public Long getId() { return id; }
    public PointEntity getPoint() { return point; }
    public DeviceEntity getDevice() { return device; }
    public String getAlertLevel() { return alertLevel; }
    public BigDecimal getHealthIndex() { return healthIndex; }
    public String getDominantFactor() { return dominantFactor; }
    public Long getDiagnosticDeviceTypeId() { return diagnosticDeviceTypeId; }
    public String getDiagnosticDeviceTypeName() { return diagnosticDeviceTypeName; }
    public Long getDiagnosticRuleId() { return diagnosticRuleId; }
    public Long getDiagnosticRuleVersion() { return diagnosticRuleVersion; }
    public DiagnosticMetric getDiagnosticMetric() { return diagnosticMetric; }
    public DiagnosticOperator getDiagnosticOperator() { return diagnosticOperator; }
    public Double getDiagnosticThreshold() { return diagnosticThreshold; }
    public Double getDiagnosticObservedValue() { return diagnosticObservedValue; }
    public Double getDiagnosticFrequencyOrder() { return diagnosticFrequencyOrder; }
    public Double getDiagnosticObservedFrequencyHz() { return diagnosticObservedFrequencyHz; }
    public Double getDiagnosticTargetFrequencyHz() { return diagnosticTargetFrequencyHz; }
    public String getDiagnosticEvidence() { return diagnosticEvidence; }
    public String getDiagnosticAdvice() { return diagnosticAdvice; }
    public BigDecimal getTriggerAmp() { return triggerAmp; }
    public BigDecimal getBaseAmp() { return baseAmp; }
    public List<DeviceHealthRollup.Evidence> getEvidenceSnapshot() { return evidenceSnapshot == null ? List.of() : evidenceSnapshot; }
    public java.util.Map<String, Object> getDefectDetail() { return defectDetail; }
    public void setDefectDetail(java.util.Map<String, Object> value) { defectDetail = value; }
    public String getRawSnapshotUrl() { return rawSnapshotUrl; }
    public void setRawSnapshotUrl(String value) { rawSnapshotUrl = value; }
    public short getStatus() { return status; }
    public boolean isNotified() { return notified; }
    public int getOccurrenceCount() { return occurrenceCount; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSuppressedAt() { return suppressedAt; }
    public void updateOccurrence(double health, List<DeviceHealthRollup.Evidence> evidence) {
        // Keep the original trigger point and its 1X amplitude/baseline aligned for the life of the device alert.
        healthIndex = BigDecimal.valueOf(health);
        evidenceSnapshot = evidence;
        occurrenceCount++; lastSeenAt = Instant.now();
    }
    public void setStatus(short value) { status = value; }
    public void suppress() {
        status = 3;
        suppressedAt = Instant.now();
    }
}
