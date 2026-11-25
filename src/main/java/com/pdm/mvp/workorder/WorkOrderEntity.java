package com.pdm.mvp.workorder;

import com.pdm.mvp.alert.AlertEntity;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.PointEntity;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "pdm_work_order")
public class WorkOrderEntity {
    /** CMMS 工单优先级。 */
    public enum Priority { LOW, MEDIUM, HIGH, URGENT }

    /** 工单来源：ALERT=告警转单（必须关联告警）；MANUAL=日常点检手工建单（可不关联告警）。 */
    public static final String SOURCE_ALERT = "ALERT";
    public static final String SOURCE_MANUAL = "MANUAL";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = true) @JoinColumn(name = "alert_id", nullable = true, unique = true)
    private AlertEntity alert;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "device_id", nullable = false)
    private DeviceEntity device;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "point_id")
    private PointEntity point;
    @Column(name = "source", nullable = false, length = 16)
    private String source = SOURCE_ALERT;
    @Column(name = "title", length = 128)
    private String title;
    @Column(nullable = false, length = 20)
    private String status = "OPEN";
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Priority priority = Priority.MEDIUM;
    @Column(name = "maintenance_action")
    private String maintenanceAction;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "spare_parts", columnDefinition = "jsonb")
    private List<SparePart> spareParts;
    @Column(length = 80)
    private String assignee;
    @Column(name = "resolution_note")
    private String resolutionNote;
    @Enumerated(EnumType.STRING)
    @Column(name = "diagnosis_verdict", length = 16)
    private DiagnosisVerdict diagnosisVerdict;
    @Column(name = "actual_finding")
    private String actualFinding;
    @Column(name = "action_taken")
    private String actionTaken;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
    @Column(name = "closed_at")
    private Instant closedAt;

    /** 所需备件条目（CMMS 派单建议清单）。 */
    public record SparePart(String name, String specification, Integer quantity) {}

    protected WorkOrderEntity() {}
    public WorkOrderEntity(AlertEntity alert) {
        this.alert = alert;
        if (alert != null) {
            this.priority = "DANGER".equals(alert.getAlertLevel()) ? Priority.HIGH : Priority.MEDIUM;
            this.maintenanceAction = alert.getDiagnosticAdvice();
            this.spareParts = List.of();
            this.device = alert.getDevice();
            this.point = alert.getPoint();
            this.source = SOURCE_ALERT;
            this.title = alert.getDominantFactor();
        }
    }

    /** 手工建单：不关联告警，直挂设备（可选精确到测点），用于日常点检发现的跑冒滴漏 / 异响等。 */
    public static WorkOrderEntity manual(DeviceEntity device, PointEntity point, String title,
            Priority priority, String maintenanceAction, String assignee, List<SparePart> spareParts) {
        WorkOrderEntity order = new WorkOrderEntity();
        order.alert = null;
        order.device = Objects.requireNonNull(device, "手工工单必须指定设备");
        order.point = point;
        order.source = SOURCE_MANUAL;
        order.title = title;
        order.priority = priority == null ? Priority.MEDIUM : priority;
        order.maintenanceAction = maintenanceAction;
        order.spareParts = spareParts == null ? List.of() : List.copyOf(spareParts);
        if (assignee != null && !assignee.isBlank()) order.assign(assignee.strip());
        return order;
    }

    public Long getId() { return id; }
    public AlertEntity getAlert() { return alert; }
    public DeviceEntity getDevice() { return device; }
    public PointEntity getPoint() { return point; }
    public String getSource() { return source; }
    public String getTitle() { return title; }
    public String getStatus() { return status; }
    public Priority getPriority() { return priority; }
    public String getMaintenanceAction() { return maintenanceAction; }
    public List<SparePart> getSpareParts() { return spareParts == null ? List.of() : spareParts; }
    public String getAssignee() { return assignee; }
    public String getResolutionNote() { return resolutionNote; }
    public DiagnosisVerdict getDiagnosisVerdict() { return diagnosisVerdict; }
    public String getActualFinding() { return actualFinding; }
    public String getActionTaken() { return actionTaken; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void assign(String username) { assignee = username; status = "IN_PROGRESS"; updatedAt = Instant.now(); }
    /** CMMS 派单建议：优先级、建议维修措施与备件清单。 */
    public void dispatchAdvice(Priority priorityValue, String suggestedAction, List<SparePart> parts) {
        if (priorityValue != null) priority = priorityValue;
        maintenanceAction = suggestedAction;
        spareParts = parts == null ? List.of() : List.copyOf(parts);
        updatedAt = Instant.now();
    }
    public void close(String note, boolean suppressed) {
        status = suppressed ? "SUPPRESSED" : "CLOSED"; resolutionNote = note; closedAt = Instant.now(); updatedAt = closedAt;
    }
    public void closeWithFeedback(String note, DiagnosisVerdict verdict, String finding, String action) {
        close(note, false);
        diagnosisVerdict = verdict == null ? DiagnosisVerdict.UNVERIFIED : verdict;
        actualFinding = finding;
        actionTaken = action;
    }
}
