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
 * 频谱基线带状态：逐带 P50 + MAD（参考带 = P50 ± 3×1.4826×MAD，与标量特征 MAD 清洗同口径）。
 * 该表有行 = 该测点该谱型的参考带已冻结（达到样本下限）；基线重置时整表清空重学。
 */
@Entity
@Table(name = "pdm_spectrum_band_state", uniqueConstraints = @UniqueConstraint(
        name = "uk_spectrum_band_state", columnNames = {"point_id", "kind", "band_index"}))
public class SpectrumBandStateEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "point_id", nullable = false)
    private PointEntity point;
    @Column(nullable = false, length = 16)
    private String kind;
    @Column(name = "band_index", nullable = false)
    private int bandIndex;
    @Column(name = "band_low_hz", nullable = false)
    private double bandLowHz;
    @Column(name = "band_center_hz", nullable = false)
    private double bandCenterHz;
    @Column(name = "band_high_hz", nullable = false)
    private double bandHighHz;
    @Column(nullable = false)
    private Double p50;
    @Column(nullable = false)
    private Double mad;
    @Column(name = "sample_count", nullable = false)
    private int sampleCount;
    @Column(name = "locked_at", nullable = false)
    private Instant lockedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SpectrumBandStateEntity() {}
    public SpectrumBandStateEntity(PointEntity point, String kind, int bandIndex, double bandLowHz,
            double bandCenterHz, double bandHighHz, double p50, double mad, int sampleCount, Instant lockedAt) {
        this.point = point; this.kind = kind; this.bandIndex = bandIndex;
        this.bandLowHz = bandLowHz; this.bandCenterHz = bandCenterHz; this.bandHighHz = bandHighHz;
        this.p50 = p50; this.mad = mad; this.sampleCount = sampleCount; this.lockedAt = lockedAt;
    }

    public Long getId() { return id; }
    public PointEntity getPoint() { return point; }
    public String getKind() { return kind; }
    public int getBandIndex() { return bandIndex; }
    public double getBandLowHz() { return bandLowHz; }
    public double getBandCenterHz() { return bandCenterHz; }
    public double getBandHighHz() { return bandHighHz; }
    public Double getP50() { return p50; }
    public Double getMad() { return mad; }
    public int getSampleCount() { return sampleCount; }
    public Instant getLockedAt() { return lockedAt; }

    public void touch() { this.updatedAt = Instant.now(); }
}
