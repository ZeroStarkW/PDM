package com.pdm.mvp.bearing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** 标准滚动轴承几何机理字典：几何参数 + 预计算特征频率系数（BPFO/BPFI/BSF/FTF，单位 ×转频）。 */
@Entity
@Table(name = "pdm_bearing_library")
public class BearingLibraryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "model_name", nullable = false, unique = true, length = 64)
    private String modelName;
    @Column(name = "manufacturer", nullable = false, length = 32)
    private String manufacturer = "GENERIC";
    @Column(name = "ball_count", nullable = false)
    private int ballCount;
    @Column(name = "ball_diameter", nullable = false, precision = 6, scale = 3)
    private BigDecimal ballDiameter;
    @Column(name = "pitch_diameter", nullable = false, precision = 7, scale = 3)
    private BigDecimal pitchDiameter;
    @Column(name = "contact_angle_deg", nullable = false, precision = 4, scale = 1)
    private BigDecimal contactAngleDeg = BigDecimal.ZERO;
    @Column(name = "bpfo_factor", nullable = false, precision = 6, scale = 4)
    private BigDecimal bpfoFactor;
    @Column(name = "bpfi_factor", nullable = false, precision = 6, scale = 4)
    private BigDecimal bpfiFactor;
    @Column(name = "bsf_factor", nullable = false, precision = 6, scale = 4)
    private BigDecimal bsfFactor;
    @Column(name = "ftf_factor", nullable = false, precision = 6, scale = 4)
    private BigDecimal ftfFactor;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected BearingLibraryEntity() {}

    public BearingLibraryEntity(String modelName, String manufacturer, int ballCount,
            BigDecimal ballDiameter, BigDecimal pitchDiameter, BigDecimal contactAngleDeg,
            BigDecimal bpfoFactor, BigDecimal bpfiFactor, BigDecimal bsfFactor, BigDecimal ftfFactor) {
        this.modelName = modelName; this.manufacturer = manufacturer; this.ballCount = ballCount;
        this.ballDiameter = ballDiameter; this.pitchDiameter = pitchDiameter;
        this.contactAngleDeg = contactAngleDeg;
        this.bpfoFactor = bpfoFactor; this.bpfiFactor = bpfiFactor;
        this.bsfFactor = bsfFactor; this.ftfFactor = ftfFactor;
    }

    public Long getId() { return id; }
    public String getModelName() { return modelName; }
    public String getManufacturer() { return manufacturer; }
    public int getBallCount() { return ballCount; }
    public BigDecimal getBallDiameter() { return ballDiameter; }
    public BigDecimal getPitchDiameter() { return pitchDiameter; }
    public BigDecimal getContactAngleDeg() { return contactAngleDeg; }
    public BigDecimal getBpfoFactor() { return bpfoFactor; }
    public BigDecimal getBpfiFactor() { return bpfiFactor; }
    public BigDecimal getBsfFactor() { return bsfFactor; }
    public BigDecimal getFtfFactor() { return ftfFactor; }
}
