package com.pdm.mvp.maintenance;

import com.pdm.mvp.asset.DeviceEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "pdm_maintenance_task")
public class MaintenanceTaskEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private MaintenancePlanEntity plan;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private DeviceEntity device;
    @Column(name = "plan_title", length = 128)
    private String planTitle;
    @Column(length = 2000)
    private String instructions;
    @Column(name = "due_at", nullable = false)
    private Instant dueAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MaintenanceTaskStatus status = MaintenanceTaskStatus.OPEN;
    @Column(length = 80)
    private String assignee;
    @Column(length = 2000)
    private String findings;
    @Column(length = 2000)
    private String action;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    protected MaintenanceTaskEntity() {}

    MaintenanceTaskEntity(MaintenancePlanEntity plan, Instant now) {
        this.plan = plan;
        this.device = plan.getDevice();
        this.planTitle = plan.getTitle();
        this.instructions = plan.getInstructions();
        this.dueAt = plan.getNextDueAt();
        this.assignee = plan.getAssignee();
        this.createdAt = now;
        this.updatedAt = now;
    }

    void assign(String assignee, Instant now) {
        this.assignee = assignee;
        this.updatedAt = now;
    }

    void start(Instant now) {
        status = MaintenanceTaskStatus.IN_PROGRESS;
        updatedAt = now;
    }

    void complete(String findings, String action, Instant now) {
        this.findings = findings;
        this.action = action;
        status = MaintenanceTaskStatus.COMPLETED;
        completedAt = now;
        updatedAt = now;
    }

    void cancel(Instant now) {
        status = MaintenanceTaskStatus.CANCELLED;
        updatedAt = now;
    }

    public Long getId() { return id; }
    public MaintenancePlanEntity getPlan() { return plan; }
    public DeviceEntity getDevice() { return device; }
    public String getPlanTitle() { return planTitle; }
    public String getInstructions() { return instructions; }
    public Instant getDueAt() { return dueAt; }
    public MaintenanceTaskStatus getStatus() { return status; }
    public String getAssignee() { return assignee; }
    public String getFindings() { return findings; }
    public String getAction() { return action; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
