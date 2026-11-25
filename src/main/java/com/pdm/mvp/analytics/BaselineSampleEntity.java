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
import java.time.Instant;

@Entity
@Table(name = "pdm_baseline_sample")
public class BaselineSampleEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "point_id", nullable = false)
    private PointEntity point;
    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;
    @Column(name = "rpm")
    private Double rpm;
    @Column(name = "freq_1x_amp", nullable = false)
    private double freq1xAmp;
    @Column(name = "freq_2x_amp")
    private double freq2xAmp;
    @Column(name = "envelope_bpfo_amp")
    private Double envelopeBpfoAmp;
    @Column(name = "v_rms", nullable = false)
    private double vRms;
    /** 加速度峰值：协议 2.1.0 旋转振动样本可不带 a_peak，故可空（V15 放宽列约束）。 */
    @Column(name = "a_peak")
    private Double aPeak;
    @Column(name = "a_kurtosis")
    private Double aKurtosis;
    @Column(name = "temperature")
    private Double temperature;
    @Column(name = "quality_code", nullable = false)
    private short qualityCode;
    /** 工况分箱号：0 = 额定/默认工况箱（存量行为），非 0 = 变频转速段箱（V21 多工况基线）。 */
    @Column(name = "bin_index", nullable = false)
    private short binIndex = 0;

    protected BaselineSampleEntity() {}
    public BaselineSampleEntity(PointEntity point, Instant sampledAt, Double rpm, double freq1xAmp, double vRms,
            Double aPeak, double freq2xAmp, Double envelopeBpfoAmp, Double aKurtosis, Double temperature) {
        this(point, sampledAt, rpm, freq1xAmp, vRms, aPeak, freq2xAmp, envelopeBpfoAmp, aKurtosis, temperature, (short) 0);
    }
    public BaselineSampleEntity(PointEntity point, Instant sampledAt, Double rpm, double freq1xAmp, double vRms,
            Double aPeak, double freq2xAmp, Double envelopeBpfoAmp, Double aKurtosis, Double temperature,
            short binIndex) {
        this.point = point; this.sampledAt = sampledAt; this.rpm = rpm; this.freq1xAmp = freq1xAmp;
        this.freq2xAmp = freq2xAmp; this.envelopeBpfoAmp = envelopeBpfoAmp;
        this.vRms = vRms; this.aPeak = aPeak; this.aKurtosis = aKurtosis; this.temperature = temperature; this.qualityCode = 0;
        this.binIndex = binIndex;
    }
    public double getFreq1xAmp() { return freq1xAmp; }
    public Double getRpm() { return rpm; }
    public double getFreq2xAmp() { return freq2xAmp; }
    public Double getEnvelopeBpfoAmp() { return envelopeBpfoAmp; }
    public double getVRms() { return vRms; }
    public Double getAPeak() { return aPeak; }
    public Double getAKurtosis() { return aKurtosis; }
    public Double getTemperature() { return temperature; }
    public Instant getSampledAt() { return sampledAt; }
    public short getBinIndex() { return binIndex; }
}
