package com.pdm.mvp.maintenance;

import com.pdm.mvp.asset.DeviceEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "pdm_maintenance_plan")
public class MaintenancePlanEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private DeviceEntity device;
    @Column(nullable = false, length = 128)
    private String title;
    @Column(name = "interval_days", nullable = false)
    private int intervalDays;
    @Column(name = "next_due_at", nullable = false)
    private Instant nextDueAt;
    @Column(length = 2000)
    private String instructions;
    @Column(length = 80)
    private String assignee;
    @Column(nullable = false)
    private boolean enabled;
    @Version
    private long version;

    protected MaintenancePlanEntity() {}

    MaintenancePlanEntity(DeviceEntity device, String title, int intervalDays, Instant nextDueAt,
            String instructions, String assignee, boolean enabled) {
        update(device, title, intervalDays, nextDueAt, instructions, assignee, enabled);
    }

    void update(DeviceEntity device, String title, int intervalDays, Instant nextDueAt,
            String instructions, String assignee, boolean enabled) {
        this.device = device;
        this.title = title;
        this.intervalDays = intervalDays;
        this.nextDueAt = nextDueAt.truncatedTo(ChronoUnit.MICROS);
        this.instructions = instructions;
        this.assignee = assignee;
        this.enabled = enabled;
    }

    void advance() {
        nextDueAt = nextDueAt.plus(intervalDays, ChronoUnit.DAYS);
    }

    public Long getId() { return id; }
    public DeviceEntity getDevice() { return device; }
    public String getTitle() { return title; }
    public int getIntervalDays() { return intervalDays; }
    public Instant getNextDueAt() { return nextDueAt; }
    public String getInstructions() { return instructions; }
    public String getAssignee() { return assignee; }
    public boolean isEnabled() { return enabled; }
    public long getVersion() { return version; }
}
