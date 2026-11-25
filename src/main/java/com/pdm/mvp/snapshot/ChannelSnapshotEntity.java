package com.pdm.mvp.snapshot;

import com.pdm.mvp.asset.PointEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 多模态快速事件通道快照（协议 2.1.0 §2.2）：断路器分合闸行程曲线、调节阀回环曲线等突发点阵。
 * preview 存边缘回传点阵，curves 存多曲线映射（time_axis_ms / coil_current_a / travel_stroke_mm / cv_pct / fb_pct），
 * annotations 存平台侧曲线标注（行程标注或回环分析，见 StrokeAnnotationCalculator / ValveLoopAnalyzer）。
 */
@Entity
@Table(name = "pdm_channel_snapshot")
public class ChannelSnapshotEntity {
    public static final String TRIGGER_TRIP_ACTION_BURST = "TRIP_ACTION_BURST";
    public static final String TRIGGER_THRESHOLD = "THRESHOLD_EXCEEDED";
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_ON_DEMAND = "ON_DEMAND";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "point_id", nullable = false)
    private PointEntity point;
    @Column(name = "trigger_type", nullable = false, length = 32)
    private String triggerType;
    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;
    /** 采样率（Hz），事件型快照可空（缺省取测点 sampling_rate，否则 0）。 */
    @Column(name = "sampling_rate")
    private Double samplingRate;
    @Column(name = "points_count")
    private Integer pointsCount;
    @Column(name = "storage_type", nullable = false, length = 32)
    private String storageType = "LOCAL_VOLUME";
    @Column(name = "storage_ref", length = 512)
    private String storageRef;
    /** 边缘侧对象存储引用（如 s3://phm-vault/...），平台归档引用存 storage_ref。 */
    @Column(name = "snapshot_url", length = 512)
    private String snapshotUrl;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Double> preview;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, List<Double>> curves;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "curve_units", columnDefinition = "jsonb")
    private Map<String, String> curveUnits;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Double> metrics;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> annotations;
    @Column(name = "health_index")
    private Double healthIndex;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ChannelSnapshotEntity() {}

    public ChannelSnapshotEntity(PointEntity point, String triggerType, Instant sampledAt,
            Double samplingRate, Integer pointsCount, List<Double> preview, Double healthIndex) {
        this.point = point; this.triggerType = triggerType; this.sampledAt = sampledAt;
        this.samplingRate = samplingRate; this.pointsCount = pointsCount;
        this.preview = preview == null ? new ArrayList<>() : preview;
        this.healthIndex = healthIndex;
    }

    public Long getId() { return id; }
    public PointEntity getPoint() { return point; }
    public String getTriggerType() { return triggerType; }
    public Instant getSampledAt() { return sampledAt; }
    public Double getSamplingRate() { return samplingRate; }
    public Integer getPointsCount() { return pointsCount; }
    public String getStorageType() { return storageType; }
    public void setStorageType(String value) { storageType = value; }
    public String getStorageRef() { return storageRef; }
    public void setStorageRef(String value) { storageRef = value; }
    public String getSnapshotUrl() { return snapshotUrl; }
    public void setSnapshotUrl(String value) { snapshotUrl = value; }
    /** 预览点阵（DDL NOT NULL，永不为 null）。 */
    public List<Double> getPreview() { return preview == null ? List.of() : preview; }
    public Map<String, List<Double>> getCurves() { return curves; }
    public void setCurves(Map<String, List<Double>> value) { curves = value; }
    public Map<String, String> getCurveUnits() { return curveUnits; }
    public void setCurveUnits(Map<String, String> value) { curveUnits = value; }
    public Map<String, Double> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Double> value) { metrics = value; }
    public Map<String, Object> getAnnotations() { return annotations; }
    public void setAnnotations(Map<String, Object> value) { annotations = value; }
    public Double getHealthIndex() { return healthIndex; }
    public Instant getCreatedAt() { return createdAt; }
}
