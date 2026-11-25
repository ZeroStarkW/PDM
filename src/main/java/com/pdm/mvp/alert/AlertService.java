package com.pdm.mvp.alert;

import com.pdm.mvp.analytics.HealthAssessment;
import com.pdm.mvp.analytics.DeviceHealthRollup;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.diagnostics.DiagnosticMetric;
import com.pdm.mvp.diagnostics.DiagnosticOperator;
import com.pdm.mvp.modality.DiagnosticMicrokernel;
import com.pdm.mvp.modality.ModalityPlugin;
import com.pdm.mvp.modality.ModalityPluginRegistry;
import com.pdm.mvp.modality.TelemetryFreshnessPolicy;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import com.pdm.mvp.workorder.WorkOrderEntity;
import com.pdm.mvp.workorder.WorkOrderRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertService {
    private final DeviceHealthWindow healthWindow;
    private final AlertRepository alerts;
    private final WorkOrderRepository workOrders;
    private final PointRepository points;
    private final NotificationRepository notifications;
    private final TelemetryFreshnessPolicy freshness;
    private final DiagnosticMicrokernel kernel;
    private final ModalityPluginRegistry plugins;

    public AlertService(DeviceHealthWindow healthWindow, AlertRepository alerts, WorkOrderRepository workOrders,
            PointRepository points, NotificationRepository notifications, TelemetryFreshnessPolicy freshness,
            DiagnosticMicrokernel kernel, ModalityPluginRegistry plugins) {
        this.healthWindow = healthWindow; this.alerts = alerts; this.workOrders = workOrders;
        this.points = points; this.notifications = notifications; this.freshness = freshness; this.kernel = kernel;
        this.plugins = plugins;
    }

    @Transactional
    public Optional<AlertEntity> observe(Long pointId, TelemetryEnvelope sample, HealthAssessment assessment,
            double triggerValue, double baseValue, boolean eventCadence) {
        PointEntity point = points.findById(pointId).orElseThrow();
        if (assessment == null) return Optional.empty();
        if (com.pdm.mvp.asset.DeviceEntity.isOutOfService(point.getDevice())) {
            healthWindow.reset(point.getDevice().getId(), pointId);
            return Optional.empty();
        }
        var devicePoints = points.findByDevice_IdOrderByPointCode(point.getDevice().getId());
        var deviceHealth = DeviceHealthRollup.assess(point.getDevice(), devicePoints, Instant.now(), freshness.byModality());
        Double deviceIndex = deviceHealth.healthIndex();
        if (deviceIndex == null) return Optional.empty();
        boolean alertConfirmed = kernel.shouldTriggerAlarm(point.getDevice().getId(), pointId, sample.timestamp(),
                assessment, eventCadence);
        if (deviceIndex >= 50.0 || !alertConfirmed) return Optional.empty();
        // 绝对物理限值击穿（如调节阀超声泄漏超危险阈值）直接判 DANGER：此时静态分恰为 30.0，按 HI 分档只会落到 WARNING
        String level = assessment.hardLimitBreached() || deviceIndex < 30 ? "DANGER" : "WARNING";
        Instant since = Instant.now().minus(4, ChronoUnit.HOURS);
        var diagnosis = assessment.diagnosis();
        var existing = alerts.findOpenOrSuppressed(point.getDevice().getId(), level, since, diagnosis.deviceTypeId(), diagnosis.ruleId(),
                diagnosis.ruleVersion(), PageRequest.of(0, 1));
        if (!existing.isEmpty()) {
            AlertEntity alert = existing.getFirst();
            if (alert.getStatus() == 3) return Optional.empty();
            alert.updateOccurrence(deviceIndex, deviceHealth.points());
            return Optional.of(alert);
        }
        AlertEntity alert = alerts.save(new AlertEntity(point, level, deviceIndex,
                diagnosis, triggerValue, baseValue, deviceHealth.points()));
        alert.setDefectDetail(defectDetail(alert, assessment, deviceIndex));
        alerts.saveAndFlush(alert);
        workOrders.save(new WorkOrderEntity(alert));
        notifications.enqueueAlert(alert.getId());
        return Optional.of(alert);
    }

    @Transactional(readOnly = true)
    public void resetDebounce(Long pointId) {
        points.findById(pointId).ifPresent(point -> healthWindow.reset(point.getDevice().getId(), pointId));
    }


    @Transactional(readOnly = true)
    public List<AlertView> list() {
        var latest = alerts.findTop200ByOrderByCreatedAtDesc();
        if (latest.isEmpty()) return List.of();
        Map<Long, List<NotificationDelivery>> byAlert = notifications.deliveries(latest.stream().map(AlertEntity::getId).toList())
                .stream().collect(Collectors.groupingBy(NotificationDelivery::alertId));
        return latest.stream().map(alert -> view(alert, byAlert.getOrDefault(alert.getId(), List.of()))).toList();
    }

    private static final Sort ALERT_PAGE_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /**
     * 告警服务端分页 + 多维筛选（GET /api/v1/alerts/page）。
     * 顶部统计卡片返回不受 level/statusFilter 裁剪的当前车间/设备/关键字范围内全局计数。
     */
    @Transactional(readOnly = true)
    public AlertPageView listPage(int page, int size, String factoryCode, Long deviceId, String level,
            String statusFilter, String keyword) {
        String normalizedLevel = normalizeLevel(level);
        String normalizedStatus = normalizeStatusFilter(statusFilter);
        Specification<AlertEntity> scope = alertSpec(factoryCode, deviceId, null, null, keyword);
        Page<AlertEntity> result = alerts.findAll(
                alertSpec(factoryCode, deviceId, normalizedLevel, normalizedStatus, keyword),
                PageRequest.of(page, size, ALERT_PAGE_SORT));
        List<Long> ids = result.getContent().stream().map(AlertEntity::getId).toList();
        Map<Long, List<NotificationDelivery>> byAlert = ids.isEmpty() ? Map.of()
                : notifications.deliveries(ids).stream().collect(Collectors.groupingBy(NotificationDelivery::alertId));
        List<AlertView> items = result.getContent().stream()
                .map(alert -> view(alert, byAlert.getOrDefault(alert.getId(), List.of()))).toList();
        long dangerOpenCount = alerts.count(scope.and((root, query, cb) -> cb.and(
                cb.lessThan(root.<Short>get("status"), (short) 2), cb.equal(root.get("alertLevel"), "DANGER"))));
        long warningOpenCount = alerts.count(scope.and((root, query, cb) -> cb.and(
                cb.lessThan(root.<Short>get("status"), (short) 2), cb.equal(root.get("alertLevel"), "WARNING"))));
        long resolvedCount = alerts.count(scope.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.<Short>get("status"), (short) 2)));
        return new AlertPageView(items, result.getNumber(), result.getSize(), result.getTotalPages(),
                result.getTotalElements(), dangerOpenCount, warningOpenCount, resolvedCount);
    }

    private static String normalizeLevel(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.strip())) return null;
        String level = value.strip().toUpperCase(java.util.Locale.ROOT);
        if (!"DANGER".equals(level) && !"WARNING".equals(level))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "告警级别只能是 DANGER/WARNING");
        return level;
    }

    private static String normalizeStatusFilter(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.strip())) return null;
        return switch (value.strip().toUpperCase(java.util.Locale.ROOT)) {
            case "OPEN", "UNACKNOWLEDGED", "ACKNOWLEDGED", "CLOSED", "SUPPRESSED", "RESOLVED", "0", "1", "2", "3" ->
                    value.strip().toUpperCase(java.util.Locale.ROOT);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的告警状态筛选");
        };
    }

    /**
     * 告警筛选 Specification。红线：
     * 1. count 查询（Hibernate 6/7）禁止 fetch——必须按 query.getResultType() 判断为 Long 时跳过预取，否则分页总数查询直接报错；
     * 2. 全部筛选走 LEFT JOIN（告警的 device/point 恒非空，但 count 复用同一 Specification 时语义必须一致）；
     * 3. factoryCode 归一为大写精确匹配，keyword 忽略大小写模糊匹配设备编码/名称、测点编码/名称与主导因素。
     */
    private static Specification<AlertEntity> alertSpec(String factoryCode, Long deviceId, String level,
            String statusFilter, String keyword) {
        String factory = factoryCode == null || factoryCode.isBlank() ? null
                : factoryCode.strip().toUpperCase(java.util.Locale.ROOT);
        String term = keyword == null || keyword.isBlank() ? null
                : "%" + keyword.strip().toLowerCase(java.util.Locale.ROOT) + "%";
        return (root, query, cb) -> {
            List<Predicate> predicates = new java.util.ArrayList<>();
            // Hibernate 6/7 红线：count 查询（getResultType()==Long）绝不允许 fetch，否则分页统计查询直接报错
            boolean contentQuery = query == null || query.getResultType() == null
                    || (Long.class != query.getResultType() && long.class != query.getResultType());
            if (deviceId != null) predicates.add(cb.equal(root.get("device").get("id"), deviceId));
            if (factory != null || term != null) {
                var device = root.join("device", jakarta.persistence.criteria.JoinType.LEFT);
                if (factory != null)
                    predicates.add(cb.equal(device.join("factory", jakarta.persistence.criteria.JoinType.LEFT).<String>get("factoryCode"), factory));
                if (term != null) {
                    var point = root.join("point", jakarta.persistence.criteria.JoinType.LEFT);
                    predicates.add(cb.or(
                            cb.like(cb.lower(device.<String>get("deviceCode")), term),
                            cb.like(cb.lower(device.<String>get("deviceName")), term),
                            cb.like(cb.lower(point.<String>get("pointCode")), term),
                            cb.like(cb.lower(point.<String>get("pointName")), term),
                            cb.like(cb.lower(root.<String>get("dominantFactor")), term)));
                }
            } else if (contentQuery) {
                // 无 join 筛选的内容页：预取 point/device/factory 消除 N+1；count 查询（非 contentQuery）绝不进入该分支
                root.fetch("point", jakarta.persistence.criteria.JoinType.LEFT);
                var deviceFetch = root.fetch("device", jakarta.persistence.criteria.JoinType.LEFT);
                deviceFetch.fetch("factory", jakarta.persistence.criteria.JoinType.LEFT);
            }
            if (level != null) predicates.add(cb.equal(root.get("alertLevel"), level));
            if (statusFilter != null) {
                switch (statusFilter) {
                    case "OPEN" -> predicates.add(cb.lessThan(root.<Short>get("status"), (short) 2));
                    case "UNACKNOWLEDGED", "0" -> predicates.add(cb.equal(root.get("status"), (short) 0));
                    case "ACKNOWLEDGED", "1" -> predicates.add(cb.equal(root.get("status"), (short) 1));
                    case "CLOSED", "2" -> predicates.add(cb.equal(root.get("status"), (short) 2));
                    case "SUPPRESSED", "3" -> predicates.add(cb.equal(root.get("status"), (short) 3));
                    default -> predicates.add(cb.greaterThanOrEqualTo(root.<Short>get("status"), (short) 2));
                }
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** 告警分页视图：items 为当前筛选页数据；三个统计计数为当前车间/设备/关键字范围内（不含级别与状态裁剪）的全局数量。 */
    public record AlertPageView(List<AlertView> items, int page, int size, int totalPages, long totalItems,
            long dangerOpenCount, long warningOpenCount, long resolvedCount) {}

    @Transactional(readOnly = true)
    public AlertView get(Long id) { return view(alerts.findById(id).orElseThrow(AlertService::notFound)); }

    @Transactional
    public AlertView acknowledge(Long id) {
        AlertEntity alert = requireForUpdate(id);
        if (alert.getStatus() == 0) alert.setStatus((short) 1);
        return view(alert);
    }

    @Transactional
    public AlertView suppress(Long id, String note) {
        AlertEntity alert = requireForUpdate(id);
        if (alert.getStatus() == 3) return view(alert);
        if (alert.getStatus() == 2) throw new ResponseStatusException(HttpStatus.CONFLICT, "已闭环告警不能抑制");
        workOrders.findByAlert_IdForUpdate(id).ifPresent(order -> {
            if ("CLOSED".equals(order.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "对应工单已闭环，不能抑制告警");
            }
            if (!"SUPPRESSED".equals(order.getStatus())) order.close(note, true);
        });
        alert.suppress();
        return view(alert);
    }

    private AlertView view(AlertEntity alert) {
        return view(alert, notifications.deliveries(List.of(alert.getId())));
    }

    private AlertView view(AlertEntity alert, List<NotificationDelivery> deliveries) {
        return AlertView.from(alert, deliveries, plugins);
    }

    private AlertEntity requireForUpdate(Long id) {
        return alerts.findByIdForUpdate(id).orElseThrow(AlertService::notFound);
    }

    /** 确定性根因明细：主因、综合评分、dk/dt、η_dyn 与各特征贡献率（前端诊断卡片数据源）。 */
    private static java.util.Map<String, Object> defectDetail(AlertEntity alert, HealthAssessment assessment,
            double deviceIndex) {
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("primary_defect", alert.getDominantFactor());
        detail.put("device_health_index", deviceIndex);
        detail.put("point_health_index", assessment.healthIndex());
        detail.put("slope_dk_dt_per_day", assessment.slopeDkDt());
        detail.put("eta_dyn", assessment.etaDyn());
        detail.put("contributions", assessment.contributions().stream()
                .map(c -> {
                    java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("feature", c.feature());
                    item.put("current", c.current());
                    item.put("baseline", c.base());
                    // 非有限倍率（零基线等）不能进 JSONB：置 null
                    Double ratio = c.ratio();
                    item.put("ratio", ratio != null && Double.isFinite(ratio) ? ratio : null);
                    item.put("score", c.score());
                    return item;
                })
                .toList());
        return detail;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "告警不存在");
    }

    public record AlertView(Long id, Long pointId, Long deviceId, String factoryCode, String deviceCode, String deviceName,
            String pointCode, String pointName, String level, double healthIndex, String dominantFactor,
            List<DeviceHealthRollup.Evidence> evidencePoints,
            double triggerAmplitude, double baselineAmplitude, short status, boolean notified, int occurrenceCount,
            Instant createdAt, Instant lastSeenAt, Long deviceTypeId, String deviceTypeName, Long diagnosticRuleId,
            Long diagnosticRuleVersion, DiagnosticMetric diagnosticMetric, DiagnosticOperator diagnosticOperator,
            Double diagnosticThreshold, Double diagnosticObservedValue, Double diagnosticFrequencyOrder,
            Double diagnosticObservedFrequencyHz, Double diagnosticTargetFrequencyHz,
            String diagnosticEvidence, String diagnosticAdvice, Instant suppressedAt, List<NotificationDelivery> notifications,
            java.util.Map<String, Object> defectDetail, String rawSnapshotUrl,
            /** 测点的模态类型（如 CONTROL_VALVE_PROCESS / ROTATING_VIB）。 */
            String modalityType,
            /** 告警触发幅值（triggerAmplitude / baselineAmplitude）对应的特征编码；模态未注册或插件未声明时为 null。 */
            String triggerFeatureCode) {
        static AlertView from(AlertEntity alert, List<NotificationDelivery> notifications,
                ModalityPluginRegistry plugins) {
            PointEntity point = alert.getPoint();
            var device = alert.getDevice();
            return new AlertView(alert.getId(), point.getId(), device.getId(), device.getFactory().getFactoryCode(), device.getDeviceCode(),
                    device.getDeviceName(), point.getPointCode(), point.getPointName(), alert.getAlertLevel(),
                    alert.getHealthIndex().doubleValue(), alert.getDominantFactor(), alert.getEvidenceSnapshot(), alert.getTriggerAmp().doubleValue(),
                    alert.getBaseAmp().doubleValue(), alert.getStatus(), alert.isNotified(), alert.getOccurrenceCount(),
                    alert.getCreatedAt(), alert.getLastSeenAt(), alert.getDiagnosticDeviceTypeId(),
                    alert.getDiagnosticDeviceTypeName(), alert.getDiagnosticRuleId(), alert.getDiagnosticRuleVersion(),
                    alert.getDiagnosticMetric(), alert.getDiagnosticOperator(), alert.getDiagnosticThreshold(),
                    alert.getDiagnosticObservedValue(), alert.getDiagnosticFrequencyOrder(),
                    alert.getDiagnosticObservedFrequencyHz(), alert.getDiagnosticTargetFrequencyHz(),
                    alert.getDiagnosticEvidence(), alert.getDiagnosticAdvice(), alert.getSuppressedAt(), notifications,
                    alert.getDefectDetail(), alert.getRawSnapshotUrl(), point.getModalityType(),
                    plugins.find(point.getModalityType())
                            .map(ModalityPlugin::triggerFeatureCode).orElse(null));
        }
    }
}
