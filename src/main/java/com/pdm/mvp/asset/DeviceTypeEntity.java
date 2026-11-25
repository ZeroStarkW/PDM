package com.pdm.mvp.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pdm_device_type")
public class DeviceTypeEntity {
    static final String GENERAL_CODE = "GENERAL";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "type_code", nullable = false, unique = true, length = 32)
    private String typeCode;
    @Column(name = "type_name", nullable = false, length = 128)
    private String typeName;

    protected DeviceTypeEntity() {}

    DeviceTypeEntity(String typeCode, String typeName) {
        this.typeCode = typeCode;
        this.typeName = typeName;
    }

    public Long getId() { return id; }
    public String getTypeCode() { return typeCode; }
    public String getTypeName() { return typeName; }
    boolean isSystem() { return GENERAL_CODE.equals(typeCode); }
    void rename(String name) { typeName = name; }
}
