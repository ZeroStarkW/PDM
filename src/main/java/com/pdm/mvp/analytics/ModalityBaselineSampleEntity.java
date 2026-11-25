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
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 新模态基线学习样本（振动模态仍使用 pdm_baseline_sample 旧表，互不影响）。
 */
@Entity
@Table(name = "pdm_baseline_sample_v2", uniqueConstraints = @UniqueConstraint(
        name = "uk_baseline_sample_v2", columnNames = {"point_id", "modality_type", "bin_index", "sampled_at"}))
public class ModalityBaselineSampleEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "point_id", nullable = false)
    private PointEntity point;
    @Column(name = "modality_type", nullable = false, length = 32)
    private String modalityType;
    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_values", columnDefinition = "jsonb", nullable = false)
    private Map<String, Double> featureValues = new LinkedHashMap<>();
    @Column(name = "quality_code", nullable = false)
    private short qualityCode;
    /** 工况分箱号：0 = 默认/额定工况箱，非 0 = 转速段 / 负载段独立学习箱（V21）。 */
    @Column(name = "bin_index", nullable = false)
    private short binIndex = 0;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ModalityBaselineSampleEntity() {}
    public ModalityBaselineSampleEntity(PointEntity point, String modalityType, Instant sampledAt,
            Map<String, Double> featureValues) {
        this(point, modalityType, sampledAt, featureValues, (short) 0);
    }
    public ModalityBaselineSampleEntity(PointEntity point, String modalityType, Instant sampledAt,
            Map<String, Double> featureValues, short binIndex) {
        this.point = point; this.modalityType = modalityType; this.sampledAt = sampledAt;
        this.featureValues = featureValues; this.qualityCode = 0; this.binIndex = binIndex;
    }

    public Long getId() { return id; }
    public PointEntity getPoint() { return point; }
    public String getModalityType() { return modalityType; }
    public Instant getSampledAt() { return sampledAt; }
    public short getBinIndex() { return binIndex; }
    public Map<String, Double> getFeatureValues() { return featureValues; }
}
