package com.pdm.mvp.asset;

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
@Table(name = "pdm_measurement_point")
public class PointEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "device_id", nullable = false)
    private DeviceEntity device;
    @Column(name = "point_code", nullable = false, length = 64)
    private String pointCode;
    @Column(name = "point_name", nullable = false, length = 128)
    private String pointName;
    @Column(name = "sensor_mac", length = 32)
    private String sensorMac;
    @Column(name = "modality_type", nullable = false, length = 32)
    private String modalityType = com.pdm.mvp.modality.ModalityTypes.ROTATING_VIB;
    /** 采样率（Hz）：标量过程量填 1，事件型动作波形填采集卡速率，可空。 */
    @Column(name = "sampling_rate")
    private Integer samplingRate;
    /** 物理量工程单位（如 mm/s、ms、%），可空。 */
    @Column(name = "engineering_unit", length = 32)
    private String engineeringUnit;
    /** 通道机理专有参数（如断路器分级倍率、调节阀阈值），由模态插件读取。 */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "metadata_config", columnDefinition = "jsonb")
    private java.util.Map<String, Object> metadataConfig;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "bearing_model_id")
    private com.pdm.mvp.bearing.BearingLibraryEntity bearingModel;
    @Column(name = "freq_1x_base")
    private Double freq1xBase;
    @Column(name = "freq_2x_base")
    private Double freq2xBase;
    @Column(name = "envelope_bpfo_base")
    private Double envelopeBpfoBase;
    @Column(name = "baseline_cv")
    private Double baselineCv;
    @Column(name = "v_base_rms")
    private Double vBaseRms;
    @Column(name = "a_base_peak")
    private Double aBasePeak;
    @Column(name = "temp_base")
    private Double tempBase;
    @Column(name = "baseline_status", nullable = false)
    private short baselineStatus = 0;
    @Column(name = "learned_samples", nullable = false)
    private int learnedSamples;
    @Column(name = "baseline_locked_at")
    private Instant baselineLockedAt;
    @Column(name = "last_health_index")
    private Double lastHealthIndex;
    @Column(name = "last_status", length = 32)
    private String lastStatus;
    @Column(name = "last_telemetry_at")
    private Instant lastTelemetryAt;
    @Column(name = "last_received_at")
    private Instant lastReceivedAt;
    @Column(name = "quality_code")
    private Integer qualityCode;
    @Column(name = "ingested_at")
    private Instant ingestedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PointEntity() {}
    public PointEntity(DeviceEntity device, String pointCode, String pointName, String sensorMac) {
        this.device = device; this.pointCode = pointCode; this.pointName = pointName; this.sensorMac = sensorMac;
    }
    public Long getId() { return id; }
    public DeviceEntity getDevice() { return device; }
    public String getPointCode() { return pointCode; }
    public String getPointName() { return pointName; }
    public String getSensorMac() { return sensorMac; }
    public String getModalityType() { return modalityType; }
    public void setModalityType(String value) {
        String normalized = com.pdm.mvp.modality.ModalityTypes.normalize(value);
        modalityType = normalized == null ? com.pdm.mvp.modality.ModalityTypes.ROTATING_VIB : normalized;
    }
    public Integer getSamplingRate() { return samplingRate; }
    public void setSamplingRate(Integer value) { samplingRate = value; }
    public String getEngineeringUnit() { return engineeringUnit; }
    public void setEngineeringUnit(String value) { engineeringUnit = value; }
    public java.util.Map<String, Object> getMetadataConfig() { return metadataConfig; }
    public void setMetadataConfig(java.util.Map<String, Object> value) { metadataConfig = value; }
    /** 通道机理参数视图：未配置时返回空映射，供插件安全读取。 */
    public java.util.Map<String, Object> metadataConfig() {
        return metadataConfig == null ? java.util.Map.of() : metadataConfig;
    }
    public com.pdm.mvp.bearing.BearingLibraryEntity getBearingModel() { return bearingModel; }
    public void setBearingModel(com.pdm.mvp.bearing.BearingLibraryEntity value) { bearingModel = value; }
    public Double getFreq1xBase() { return freq1xBase; }
    public Double getFreq2xBase() { return freq2xBase; }
    public Double getEnvelopeBpfoBase() { return envelopeBpfoBase; }
    public Double getBaselineCv() { return baselineCv; }
    public Double getVBaseRms() { return vBaseRms; }
    public Double getABasePeak() { return aBasePeak; }
    public Double getTempBase() { return tempBase; }
    public short getBaselineStatus() { return baselineStatus; }
    public int getLearnedSamples() { return learnedSamples; }
    public Instant getBaselineLockedAt() { return baselineLockedAt; }
    public Double getLastHealthIndex() { return lastHealthIndex; }
    public String getLastStatus() { return lastStatus; }
    public Instant getLastTelemetryAt() { return lastTelemetryAt; }
    public Instant getLastReceivedAt() { return lastReceivedAt; }
    public Integer getQualityCode() { return qualityCode; }
    public Instant getIngestedAt() { return ingestedAt; }
    public void setFreq1xBase(Double value) { freq1xBase = value; }
    public void setFreq2xBase(Double value) { freq2xBase = value; }
    public void setEnvelopeBpfoBase(Double value) { envelopeBpfoBase = value; }
    public void setBaselineCv(Double value) { baselineCv = value; }
    public void setVBaseRms(Double value) { vBaseRms = value; }
    public void setABasePeak(Double value) { aBasePeak = value; }
    public void setTempBase(Double value) { tempBase = value; }
    public void setBaselineStatus(short value) { baselineStatus = value; }
    public void setLearnedSamples(int value) { learnedSamples = value; }
    public void setBaselineLockedAt(Instant value) { baselineLockedAt = value; }
    public void setLastHealthIndex(Double value) { lastHealthIndex = value; }
    public void setLastStatus(String value) { lastStatus = value; }
    public void setLastTelemetryAt(Instant value) { lastTelemetryAt = value; }
    public void recordIngest(Instant sampledAt, Instant serverTime, int qualityCode) {
        lastReceivedAt = sampledAt;
        ingestedAt = serverTime;
        this.qualityCode = qualityCode;
    }
    public void update(String name, String mac) { pointName = name; sensorMac = mac; updatedAt = Instant.now(); }
    public void touch() { updatedAt = Instant.now(); }
}
