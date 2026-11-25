package com.pdm.mvp.waveform;

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
import java.util.List;

/**
 * 原始高频波形快照（文档 §2.2）：越限触发 THRESHOLD_EXCEEDED / 定时巡检 SCHEDULED /
 * 专家召唤 ON_DEMAND 三类触发；preview 存放边缘回传的波形点阵（供谱计算与工作站展示）。
 */
@Entity
@Table(name = "pdm_waveform_snapshot")
public class WaveformSnapshotEntity {
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
    @Column(name = "sampling_rate", nullable = false)
    private double samplingRate;
    @Column(name = "points_count", nullable = false)
    private int pointsCount;
    @Column(nullable = false, length = 16)
    private String unit = "m/s^2";
    @Column(name = "storage_type", nullable = false, length = 32)
    private String storageType = "LOCAL_VOLUME";
    @Column(name = "storage_ref", length = 512)
    private String storageRef;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Double> preview;
    @Column(name = "health_index")
    private Double healthIndex;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected WaveformSnapshotEntity() {}

    public WaveformSnapshotEntity(PointEntity point, String triggerType, Instant sampledAt,
            double samplingRate, int pointsCount, String unit, List<Double> preview, Double healthIndex) {
        this.point = point; this.triggerType = triggerType; this.sampledAt = sampledAt;
        this.samplingRate = samplingRate; this.pointsCount = pointsCount;
        this.unit = unit == null || unit.isBlank() ? "m/s^2" : unit;
        this.preview = preview; this.healthIndex = healthIndex;
    }

    public Long getId() { return id; }
    public PointEntity getPoint() { return point; }
    public String getTriggerType() { return triggerType; }
    public Instant getSampledAt() { return sampledAt; }
    public double getSamplingRate() { return samplingRate; }
    public int getPointsCount() { return pointsCount; }
    public String getUnit() { return unit; }
    public String getStorageType() { return storageType; }
    public void setStorageType(String value) { storageType = value; }
    public String getStorageRef() { return storageRef; }
    public void setStorageRef(String value) { storageRef = value; }
    public List<Double> getPreview() { return preview == null ? List.of() : preview; }
    public Double getHealthIndex() { return healthIndex; }
    public Instant getCreatedAt() { return createdAt; }
}
