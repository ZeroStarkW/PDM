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
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 频谱基线带学习样本：一帧波形快照按 {@link SpectrumBandGrid} 聚合出的逐带能量
 * （数组与网格 index 对齐；无 bin 覆盖的带为 null，不参与该带统计）。
 * 振动波形快照稀疏（THRESHOLD/SCHEDULED/ON_DEMAND 触发），与标量特征样本表分开管理。
 */
@Entity
@Table(name = "pdm_spectrum_band_sample", uniqueConstraints = @UniqueConstraint(
        name = "uk_spectrum_band_sample", columnNames = {"point_id", "kind", "sampled_at"}))
public class SpectrumBandSampleEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "point_id", nullable = false)
    private PointEntity point;
    @Column(nullable = false, length = 16)
    private String kind;
    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "band_values", columnDefinition = "jsonb", nullable = false)
    private List<Double> bandValues = new ArrayList<>();
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SpectrumBandSampleEntity() {}
    public SpectrumBandSampleEntity(PointEntity point, String kind, Instant sampledAt, List<Double> bandValues) {
        this.point = point; this.kind = kind; this.sampledAt = sampledAt; this.bandValues = bandValues;
    }

    public Long getId() { return id; }
    public PointEntity getPoint() { return point; }
    public String getKind() { return kind; }
    public Instant getSampledAt() { return sampledAt; }
    public List<Double> getBandValues() { return bandValues; }
}
