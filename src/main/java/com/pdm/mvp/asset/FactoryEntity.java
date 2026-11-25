package com.pdm.mvp.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "pdm_factory")
public class FactoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "factory_code", nullable = false, unique = true, length = 32)
    private String factoryCode;
    @Column(name = "factory_name", nullable = false, length = 128)
    private String factoryName;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected FactoryEntity() {}
    public FactoryEntity(String factoryCode, String factoryName) { this.factoryCode = factoryCode; this.factoryName = factoryName; }
    public Long getId() { return id; }
    public String getFactoryCode() { return factoryCode; }
    public String getFactoryName() { return factoryName; }
    public Instant getCreatedAt() { return createdAt; }
    public void setFactoryName(String factoryName) { this.factoryName = factoryName; }
}
