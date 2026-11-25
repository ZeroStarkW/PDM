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
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "pdm_device")
public class DeviceEntity {
    public static final short STATUS_ACTIVE = 1;
    public static final short STATUS_MAINTENANCE = 2;
    public static final short STATUS_RETIRED = 3;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "factory_id", nullable = false)
    private FactoryEntity factory;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "device_type_id", nullable = false)
    private DeviceTypeEntity deviceType;
    @Column(name = "device_code", nullable = false, length = 64)
    private String deviceCode;
    @Column(name = "device_name", nullable = false, length = 128)
    private String deviceName;
    @Column(name = "rpm_nominal", nullable = false, precision = 8, scale = 2)
    private BigDecimal rpmNominal = new BigDecimal("1500.00");
    @Column(name = "power_kw", precision = 8, scale = 2)
    private BigDecimal powerKw;
    @Column(name = "iso_class", nullable = false, length = 16)
    private String isoClass = "CLASS_II";
    @Column(nullable = false)
    private short status = 1;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected DeviceEntity() {}
    public DeviceEntity(FactoryEntity factory, String deviceCode, String deviceName, BigDecimal rpmNominal,
            BigDecimal powerKw, DeviceTypeEntity deviceType) {
        this(factory, deviceCode, deviceName, rpmNominal, powerKw, deviceType, null);
    }
    public DeviceEntity(FactoryEntity factory, String deviceCode, String deviceName, BigDecimal rpmNominal,
            BigDecimal powerKw, DeviceTypeEntity deviceType, String isoClass) {
        this.factory = factory; this.deviceCode = deviceCode; this.deviceName = deviceName;
        if (rpmNominal != null) this.rpmNominal = rpmNominal;
        this.powerKw = powerKw;
        this.deviceType = deviceType;
        if (isoClass != null && !isoClass.isBlank()) this.isoClass = isoClass;
    }
    public Long getId() { return id; }
    public FactoryEntity getFactory() { return factory; }
    public DeviceTypeEntity getDeviceType() { return deviceType; }
    public String getDeviceCode() { return deviceCode; }
    public String getDeviceName() { return deviceName; }
    public BigDecimal getRpmNominal() { return rpmNominal; }
    public BigDecimal getPowerKw() { return powerKw; }
    public String getIsoClass() { return isoClass; }
    public short getStatus() { return status; }

    /** 设备是否处于非在役静默态（2:检修中, 3:已报废/停用）；未桩化的测试 mock status=0 视为在役。 */
    public static boolean isOutOfService(DeviceEntity device) {
        if (device == null) return false;
        short current = device.getStatus();
        return current == STATUS_MAINTENANCE || current == STATUS_RETIRED;
    }

    /** 非在役状态标签（MAINTENANCE / RETIRED），在役返回 null。 */
    public static String outOfServiceStatus(DeviceEntity device) {
        if (device == null) return null;
        return switch (device.getStatus()) {
            case STATUS_MAINTENANCE -> "MAINTENANCE";
            case STATUS_RETIRED -> "RETIRED";
            default -> null;
        };
    }

    public void assignDeviceType(DeviceTypeEntity value) {
        this.deviceType = value;
        this.updatedAt = Instant.now();
    }
    public void update(String name, BigDecimal rpm, BigDecimal power, short status, DeviceTypeEntity type) {
        update(name, rpm, power, status, type, null);
    }
    public void update(String name, BigDecimal rpm, BigDecimal power, short status, DeviceTypeEntity type,
            String isoClassValue) {
        this.deviceName = name;
        this.rpmNominal = rpm == null ? BigDecimal.ZERO : rpm;
        this.powerKw = power;
        this.status = status;
        this.deviceType = type;
        if (isoClassValue != null && !isoClassValue.isBlank()) this.isoClass = isoClassValue;
        this.updatedAt = Instant.now();
    }
}
