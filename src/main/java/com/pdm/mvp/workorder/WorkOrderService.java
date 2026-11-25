package com.pdm.mvp.workorder;

import com.pdm.mvp.alert.AlertEntity;
import com.pdm.mvp.alert.AlertRepository;
import com.pdm.mvp.analytics.DeviceHealthRollup;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.DeviceRepository;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkOrderService {
    private final WorkOrderRepository workOrders;
    private final AlertRepository alerts;
    /** 手工建单/分页所需资产仓库；存量单测双参构造器下为 null（仅告警链路使用）。 */
    private final DeviceRepository devices;
    private final PointRepository points;
    /** 可选注入：工单闭环联动设备基线重置；单元测试可不装配该依赖（保持双参构造器无感调用）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.pdm.mvp.analytics.BaselineService baselines;

    /** 兼容红线：存量单测直接 `new WorkOrderService(workOrders, alerts)`，双参构造器必须保留。 */
    public WorkOrderService(WorkOrderRepository workOrders, AlertRepository alerts) {
        this(workOrders, alerts, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WorkOrderService(WorkOrderRepository workOrders, AlertRepository alerts, DeviceRepository devices,
            PointRepository points) {
        this.workOrders = workOrders; this.alerts = alerts; this.devices = devices; this.points = points;
    }

    public WorkOrderEntity createFromAlert(AlertEntity alert) {
        WorkOrderEntity order = new WorkOrderEntity(alert);
        order.dispatchAdvice(levelPriority(alert.getAlertLevel()), alert.getDiagnosticAdvice(), List.of());
        return workOrders.save(order);
    }

    /** CMMS 一键派单（文档 §8.3）：从告警生成/更新流转工单，写入建议维修措施、优先级与备件清单。 */
    @Transactional
    public WorkOrderView dispatch(Long alertId, String action, String priority, List<WorkOrderEntity.SparePart> spareParts) {
        AlertEntity alert = alerts.findByIdForUpdate(alertId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "告警不存在"));
        if (alert.getStatus() == 3) throw new ResponseStatusException(HttpStatus.CONFLICT, "已抑制告警不能派单");
        WorkOrderEntity order = workOrders.findByAlert_IdForUpdate(alertId).orElse(null);
        if (order == null) {
            order = workOrders.save(new WorkOrderEntity(alert));
        } else if ("CLOSED".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "对应工单已闭环，不能重复派单");
        }
        WorkOrderEntity.Priority resolved = priority == null || priority.isBlank()
                ? levelPriority(alert.getAlertLevel())
                : parsePriority(priority);
        String resolvedAction = optionalText(action);
        if (resolvedAction == null) resolvedAction = order.getMaintenanceAction() != null
                ? order.getMaintenanceAction() : alert.getDiagnosticAdvice();
        order.dispatchAdvice(resolved, resolvedAction, spareParts == null ? List.of() : spareParts);
        return WorkOrderView.from(order);
    }

    static WorkOrderEntity.Priority levelPriority(String alertLevel) {
        return "DANGER".equals(alertLevel) ? WorkOrderEntity.Priority.HIGH : WorkOrderEntity.Priority.MEDIUM;
    }

    static WorkOrderEntity.Priority parsePriority(String value) {
        try { return WorkOrderEntity.Priority.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "优先级只能是 LOW/MEDIUM/HIGH/URGENT");
        }
    }

    @Transactional(readOnly = true)
    public List<WorkOrderView> list() { return workOrders.findTop200ByOrderByCreatedAtDesc().stream().map(WorkOrderView::from).toList(); }

    /**
     * 手工建单（POST /api/v1/work-orders/manual）：日常点检发现跑冒滴漏/异响时直接为设备创建独立维修工单。
     * 整机级（pointId=null）或测点级；带指派则直接 IN_PROGRESS，否则 OPEN 待分派。
     */
    @Transactional
    public WorkOrderView createManual(Long deviceId, Long pointId, String title, String priority,
            String maintenanceAction, String assignee, List<WorkOrderEntity.SparePart> spareParts) {
        if (devices == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "手工建单未装配设备仓库");
        if (deviceId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备不能为空");
        DeviceEntity device = devices.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"));
        PointEntity point = null;
        if (pointId != null) {
            if (points == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "手工建单未装配测点仓库");
            point = points.findById(pointId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "测点不存在"));
            if (point.getDevice() == null || !Objects.equals(point.getDevice().getId(), device.getId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选测点不属于该设备");
        }
        if (title == null || title.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工单标题不能为空");
        String cleanTitle = title.strip();
        if (cleanTitle.length() > 128) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工单标题不能超过 128 个字符");
        WorkOrderEntity.Priority resolved = priority == null || priority.isBlank()
                ? WorkOrderEntity.Priority.MEDIUM : parsePriority(priority);
        WorkOrderEntity order = WorkOrderEntity.manual(device, point, cleanTitle, resolved,
                optionalText(maintenanceAction), optionalText(assignee), spareParts);
        return WorkOrderView.from(workOrders.save(order));
    }

    @Transactional
    public WorkOrderView assign(Long id, String assignee) {
        WorkOrderEntity order = requireForUpdate(id);
        if ("CLOSED".equals(order.getStatus()) || "SUPPRESSED".equals(order.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已终结工单不能重新分派");
        order.assign(assignee.trim());
        return WorkOrderView.from(order);
    }

    @Transactional
    public WorkOrderView close(Long id, String note, DiagnosisVerdict verdict, String actualFinding, String actionTaken) {
        return close(id, note, verdict, actualFinding, actionTaken, false);
    }

    /**
     * 工单闭环；requestBaselineReset=true 时联动发起设备基线重置申请（换轴承/对中找正后基线已变）。
     * 仅当工单实际从非 CLOSED 状态完成闭环才触发；无测点等 409 异常静默忽略，不阻断闭环。
     * findAlertId 为空 ≠ 工单不存在：手工工单 alert_id IS NULL，同样返回 Optional.empty()，
     * 需走无告警分支（requireForUpdate 才是“工单不存在”的 404 判定点）。
     */
    @Transactional
    public WorkOrderView close(Long id, String note, DiagnosisVerdict verdict, String actualFinding, String actionTaken,
            boolean requestBaselineReset) {
        Optional<Long> maybeAlertId = workOrders.findAlertId(id);
        if (maybeAlertId.isPresent()) {
            // Closing and suppressing both lock the alert before the related work order.
            Long alertId = maybeAlertId.get();
            AlertEntity alert = alerts.findByIdForUpdate(alertId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "关联告警不存在"));
            WorkOrderEntity order = requireForUpdate(id);
            if ("CLOSED".equals(order.getStatus())) return WorkOrderView.from(order);
            if ("SUPPRESSED".equals(order.getStatus()) || alert.getStatus() == 3)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "已抑制工单不能闭环");
            order.closeWithFeedback(note, verdict, optionalText(actualFinding), optionalText(actionTaken));
            alert.setStatus((short) 2);
            if (requestBaselineReset && baselines != null) requestDeviceResetQuietly(alert.getDevice().getId());
            return WorkOrderView.from(order);
        }
        // 手工工单：不查/不锁告警；工单真不存在时 requireForUpdate 抛 404
        WorkOrderEntity order = requireForUpdate(id);
        if ("CLOSED".equals(order.getStatus())) return WorkOrderView.from(order);
        if ("SUPPRESSED".equals(order.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已抑制工单不能闭环");
        AlertEntity attached = order.getAlert(); // 防卫：并发/测试桩中工单实际挂有告警时，同步闭环并沿用告警锁定语义
        if (attached != null) {
            if (attached.getStatus() == 3) throw new ResponseStatusException(HttpStatus.CONFLICT, "已抑制工单不能闭环");
            attached.setStatus((short) 2);
        }
        order.closeWithFeedback(note, verdict, optionalText(actualFinding), optionalText(actionTaken));
        if (requestBaselineReset && baselines != null && order.getDevice() != null)
            requestDeviceResetQuietly(order.getDevice().getId());
        return WorkOrderView.from(order);
    }

    /** 设备基线重学联动：无测点等 409 冲突静默忽略，非 409 异常照常抛出交由事务回滚。 */
    private void requestDeviceResetQuietly(Long deviceId) {
        try {
            baselines.requestDeviceReset(deviceId, null);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() != HttpStatus.CONFLICT.value()) throw ex;
            // 设备还没有测点等冲突不阻断工单闭环
        }
    }

    private WorkOrderEntity requireForUpdate(Long id) {
        return workOrders.findByIdForUpdate(id).orElseThrow(WorkOrderService::orderNotFound);
    }

    private static ResponseStatusException orderNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static final Sort WORK_ORDER_PAGE_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /** 工单服务端分页 + 多维筛选（GET /api/v1/work-orders/page）；pendingCount 为同范围（不含状态裁剪）下的待办总数。 */
    @Transactional(readOnly = true)
    public WorkOrderPageView listPage(int page, int size, String factoryCode, Long deviceId, String statusFilter,
            String priority, String source, String keyword) {
        String normalizedStatus = normalizeStatusFilter(statusFilter);
        String normalizedPriority = normalizePriorityFilter(priority);
        String normalizedSource = normalizeSourceFilter(source);
        Page<WorkOrderEntity> result = workOrders.findAll(
                workOrderSpec(factoryCode, deviceId, normalizedStatus, normalizedPriority, normalizedSource, keyword),
                PageRequest.of(page, size, WORK_ORDER_PAGE_SORT));
        List<WorkOrderView> items = result.getContent().stream().map(WorkOrderView::from).toList();
        long pendingCount = workOrders.count(
                workOrderSpec(factoryCode, deviceId, "PENDING", normalizedPriority, normalizedSource, keyword));
        return new WorkOrderPageView(items, result.getNumber(), result.getSize(), result.getTotalPages(),
                result.getTotalElements(), pendingCount);
    }

    private static String normalizeStatusFilter(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.strip())) return null;
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "PENDING", "ACTIVE", "OPEN", "IN_PROGRESS", "CLOSED", "SUPPRESSED" ->
                    value.strip().toUpperCase(Locale.ROOT);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的工单状态筛选");
        };
    }

    private static String normalizePriorityFilter(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.strip())) return null;
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "LOW", "MEDIUM", "HIGH", "URGENT" -> value.strip().toUpperCase(Locale.ROOT);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "优先级只能是 LOW/MEDIUM/HIGH/URGENT");
        };
    }

    private static String normalizeSourceFilter(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.strip())) return null;
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "ALERT", "MANUAL" -> value.strip().toUpperCase(Locale.ROOT);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "来源只能是 ALERT/MANUAL");
        };
    }

    /**
     * 工单筛选 Specification。红线：
     * 1. 手工工单 alert 为 NULL、整机检修工单 point 为 NULL——关联 alert/point/device 一律 JoinType.LEFT（INNER 会漏单）；
     * 2. count 查询禁止 fetch（本 Specification 不做 fetch，视图装配在只读事务内懒加载）。
     */
    private static Specification<WorkOrderEntity> workOrderSpec(String factoryCode, Long deviceId, String status,
            String priority, String source, String keyword) {
        String factory = factoryCode == null || factoryCode.isBlank() ? null
                : factoryCode.strip().toUpperCase(Locale.ROOT);
        String term = keyword == null || keyword.isBlank() ? null
                : "%" + keyword.strip().toLowerCase(Locale.ROOT) + "%";
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (deviceId != null) predicates.add(cb.equal(root.get("device").get("id"), deviceId));
            if (factory != null)
                predicates.add(cb.equal(root.join("device", jakarta.persistence.criteria.JoinType.LEFT)
                        .join("factory", jakarta.persistence.criteria.JoinType.LEFT).<String>get("factoryCode"), factory));
            if (term != null) {
                var device = root.join("device", jakarta.persistence.criteria.JoinType.LEFT);
                var alert = root.join("alert", jakarta.persistence.criteria.JoinType.LEFT);
                predicates.add(cb.or(
                        cb.like(cb.lower(device.<String>get("deviceCode")), term),
                        cb.like(cb.lower(device.<String>get("deviceName")), term),
                        cb.like(cb.lower(root.<String>get("title")), term),
                        cb.like(cb.lower(root.<String>get("assignee")), term),
                        cb.like(cb.lower(alert.<String>get("dominantFactor")), term)));
            }
            if (status != null) {
                switch (status) {
                    case "PENDING", "ACTIVE" -> predicates.add(root.get("status").in("OPEN", "IN_PROGRESS"));
                    case "OPEN", "IN_PROGRESS", "CLOSED", "SUPPRESSED" ->
                            predicates.add(cb.equal(root.get("status"), status));
                    default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的工单状态筛选");
                }
            }
            if (priority != null)
                predicates.add(cb.equal(root.get("priority"), WorkOrderEntity.Priority.valueOf(priority)));
            if (source != null) predicates.add(cb.equal(root.<String>get("source"), source));
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    public record WorkOrderPageView(List<WorkOrderView> items, int page, int size, int totalPages, long totalItems,
            long pendingCount) {}

    public record WorkOrderView(Long id, Long alertId, Long pointId, Long deviceId, String factoryCode, String deviceCode, String deviceName,
            String pointCode, String alertLevel, Double healthIndex, String dominantFactor, String status,
            List<DeviceHealthRollup.Evidence> evidencePoints,
            String assignee, String resolutionNote, Instant createdAt, Instant closedAt,
            DiagnosisVerdict diagnosisVerdict, String actualFinding, String actionTaken,
            String priority, String maintenanceAction, List<WorkOrderEntity.SparePart> spareParts,
            String source, String title) {
        /** 空安全装配：手工工单（alert==null）从 order 自带的 device/point/title 读取，绝不 NPE。 */
        static WorkOrderView from(WorkOrderEntity order) {
            AlertEntity alert = order.getAlert();
            DeviceEntity device = order.getDevice() != null ? order.getDevice() : (alert != null ? alert.getDevice() : null);
            PointEntity point = order.getPoint() != null ? order.getPoint() : (alert != null ? alert.getPoint() : null);
            String dominantFactor = alert != null ? alert.getDominantFactor()
                    : (order.getTitle() != null ? order.getTitle() : "人工创建检修工单");
            // 注意：不能写成 `cond ? double : (Double) null` 三元——double 分支会把另一分支提升为基本类型并对 null 拆箱 NPE
            Double healthIndex = null;
            if (alert != null && alert.getHealthIndex() != null) healthIndex = alert.getHealthIndex().doubleValue();
            else if (point != null) healthIndex = point.getLastHealthIndex();
            String source = order.getSource() != null ? order.getSource()
                    : (alert != null ? WorkOrderEntity.SOURCE_ALERT : WorkOrderEntity.SOURCE_MANUAL);
            return new WorkOrderView(order.getId(), alert != null ? alert.getId() : null,
                    point != null ? point.getId() : null, device != null ? device.getId() : null,
                    device != null && device.getFactory() != null ? device.getFactory().getFactoryCode() : null,
                    device != null ? device.getDeviceCode() : null, device != null ? device.getDeviceName() : null,
                    point != null ? point.getPointCode() : null,
                    alert != null ? alert.getAlertLevel() : null, healthIndex, dominantFactor, order.getStatus(),
                    alert != null ? alert.getEvidenceSnapshot() : List.of(),
                    order.getAssignee(), order.getResolutionNote(), order.getCreatedAt(), order.getClosedAt(),
                    order.getDiagnosisVerdict(), order.getActualFinding(), order.getActionTaken(),
                    order.getPriority() == null ? null : order.getPriority().name(),
                    order.getMaintenanceAction(), order.getSpareParts(),
                    source, order.getTitle() != null ? order.getTitle() : dominantFactor);
        }
    }
}
