package com.pdm.mvp.analytics;

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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * 按测点 + 模态 + 特征编码管理的基线状态：P50、变异系数、样本数与状态。
 * 振动模态与旧基线列双写；其他模态以此表为唯一基线状态存储。
 */
@Entity
@Table(name = "pdm_baseline_feature_state", uniqueConstraints = @UniqueConstraint(
        name = "uk_baseline_feature_state", columnNames = {"point_id", "modality_type", "bin_index", "feature_code"}))
public class BaselineFeatureStateEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "point_id", nullable = false)
    private PointEntity point;
    @Column(name = "modality_type", nullable = false, length = 32)
    private String modalityType;
    @Column(name = "feature_code", nullable = false, length = 64)
    private String featureCode;
    /** 工况分箱号：0 = 额定/默认工况箱（与旧列双写同箱），非 0 = 独立学习的转速段 / 负载段箱（V21）。 */
    @Column(name = "bin_index", nullable = false)
    private short binIndex = 0;
    @Column(name = "p50")
    private Double p50;
    @Column(name = "cv")
    private Double cv;
    @Column(name = "sample_count", nullable = false)
    private int sampleCount;
    @Column(name = "status", nullable = false)
    private short status;
    @Column(name = "locked_at")
    private Instant lockedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected BaselineFeatureStateEntity() {}
    public BaselineFeatureStateEntity(PointEntity point, String modalityType, String featureCode) {
        this(point, modalityType, (short) 0, featureCode);
    }
    public BaselineFeatureStateEntity(PointEntity point, String modalityType, short binIndex, String featureCode) {
        this.point = point; this.modalityType = modalityType; this.featureCode = featureCode; this.binIndex = binIndex;
    }

    public Long getId() { return id; }
    public PointEntity getPoint() { return point; }
    public String getModalityType() { return modalityType; }
    public String getFeatureCode() { return featureCode; }
    public short getBinIndex() { return binIndex; }
    public Double getP50() { return p50; }
    public Double getCv() { return cv; }
    public int getSampleCount() { return sampleCount; }
    public short getStatus() { return status; }
    public Instant getLockedAt() { return lockedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setP50(Double value) { p50 = value; }
    public void setCv(Double value) { cv = value; }
    public void setSampleCount(int value) { sampleCount = value; }
    public void setStatus(short value) { status = value; }
    public void setLockedAt(Instant value) { lockedAt = value; }
    public void touch() { updatedAt = Instant.now(); }
}
