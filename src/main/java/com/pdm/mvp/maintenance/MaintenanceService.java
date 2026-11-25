package com.pdm.mvp.maintenance;

import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.DeviceRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MaintenanceService {
    private static final int MAX_OCCURRENCES_PER_PLAN_PER_RUN = 100;
    private static final Sort TASK_DUE_ORDER = Sort.by("dueAt", "id");
    private static final Sort TASK_HISTORY_ORDER = Sort.by(Sort.Direction.DESC, "updatedAt", "id");

    private final MaintenancePlanRepository plans;
    private final MaintenanceTaskRepository tasks;
    private final DeviceRepository devices;

    public MaintenanceService(MaintenancePlanRepository plans, MaintenanceTaskRepository tasks, DeviceRepository devices) {
        this.plans = plans;
        this.tasks = tasks;
        this.devices = devices;
    }

    @Transactional(readOnly = true)
    public PageView<PlanView> listPlans(int page, int size) {
        Instant now = Instant.now();
        return PageView.from(plans.findAllByOrderByNextDueAtAscIdAsc(PageRequest.of(page, size))
                .map(plan -> PlanView.from(plan, now)));
    }

    @Transactional(readOnly = true)
    public PlanView getPlan(Long id) {
        MaintenancePlanEntity plan = plans.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "维护计划不存在"));
        return PlanView.from(plan, Instant.now());
    }

    @Transactional(readOnly = true)
    public PageView<TaskView> listTasks(TaskFilter filter, int page, int size) {
        Instant now = Instant.now();
        Page<MaintenanceTaskEntity> result = switch (filter) {
            case ALL -> tasks.findAll(PageRequest.of(page, size, TASK_DUE_ORDER));
            case ACTIVE -> tasks.findActive(PageRequest.of(page, size, TASK_DUE_ORDER));
            case HISTORY -> tasks.findHistory(PageRequest.of(page, size, TASK_HISTORY_ORDER));
        };
        return PageView.from(result.map(task -> TaskView.from(task, now)));
    }

    @Transactional
    public PlanView createPlan(MaintenanceController.PlanRequest request) {
        Instant now = Instant.now();
        MaintenancePlanEntity plan = plans.save(new MaintenancePlanEntity(requireDevice(request.deviceId()),
                request.title().strip(), request.intervalDays(), request.nextDueAt(),
                optionalText(request.instructions()), optionalText(request.assignee()), request.enabled()));
        tasks.save(new MaintenanceTaskEntity(plan, now));
        return PlanView.from(plan, now);
    }

    @Transactional
    public PlanView updatePlan(Long id, MaintenanceController.PlanRequest request) {
        Long expectedVersion = request.expectedVersion();
        if (expectedVersion == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "更新维护计划时必须提供 expectedVersion");
        }
        MaintenancePlanEntity plan = plans.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "维护计划不存在"));
        if (plan.getVersion() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "维护计划已更新，请刷新后重试");
        }
        plan.update(requireDevice(request.deviceId()), request.title().strip(), request.intervalDays(), request.nextDueAt(),
                optionalText(request.instructions()), optionalText(request.assignee()), request.enabled());
        plans.flush();
        return PlanView.from(plan, Instant.now());
    }

    @Transactional
    public void materializeDueOccurrences(Long planId, Instant now) {
        // Recheck the schedule under the same lock used by plan updates and other scheduler instances.
        MaintenancePlanEntity plan = plans.findByIdForUpdate(planId).orElse(null);
        if (plan == null || !plan.isEnabled()) {
            return;
        }
        for (int i = 0; i < MAX_OCCURRENCES_PER_PLAN_PER_RUN && !plan.getNextDueAt().isAfter(now); i++) {
            if (!tasks.existsByPlan_IdAndDueAt(planId, plan.getNextDueAt())) {
                tasks.save(new MaintenanceTaskEntity(plan, now));
            }
            plan.advance();
        }
    }

    @Transactional
    public TaskView assign(Long id, String assignee) {
        MaintenanceTaskEntity task = requireTaskForUpdate(id);
        requireActive(task);
        Instant now = Instant.now();
        task.assign(assignee.strip(), now);
        return TaskView.from(task, now);
    }

    @Transactional
    public TaskView start(Long id) {
        MaintenanceTaskEntity task = requireTaskForUpdate(id);
        requireActive(task);
        Instant now = Instant.now();
        if (task.getStatus() == MaintenanceTaskStatus.OPEN) {
            task.start(now);
        }
        return TaskView.from(task, now);
    }

    @Transactional
    public TaskView complete(Long id, String findings, String action) {
        MaintenanceTaskEntity task = requireTaskForUpdate(id);
        Instant now = Instant.now();
        String normalizedFindings = findings.strip();
        String normalizedAction = action.strip();
        if (task.getStatus() == MaintenanceTaskStatus.COMPLETED) {
            if (!normalizedFindings.equals(task.getFindings()) || !normalizedAction.equals(task.getAction())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "维护任务已完成，提交的现场发现或措施与记录不一致");
            }
        } else {
            requireActive(task);
            task.complete(normalizedFindings, normalizedAction, now);
        }
        return TaskView.from(task, now);
    }

    @Transactional
    public TaskView cancel(Long id) {
        MaintenanceTaskEntity task = requireTaskForUpdate(id);
        Instant now = Instant.now();
        if (task.getStatus() != MaintenanceTaskStatus.CANCELLED) {
            requireActive(task);
            task.cancel(now);
        }
        return TaskView.from(task, now);
    }

    private DeviceEntity requireDevice(Long id) {
        return devices.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"));
    }

    private MaintenanceTaskEntity requireTaskForUpdate(Long id) {
        return tasks.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "维护任务不存在"));
    }

    private static void requireActive(MaintenanceTaskEntity task) {
        if (!task.getStatus().isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "维护任务已结束");
        }
    }

    private static String optionalText(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    public enum TaskFilter {
        ALL, ACTIVE, HISTORY
    }

    public record PageView<T>(List<T> items, int page, int totalPages, long totalItems) {
        static <T> PageView<T> from(Page<T> page) {
            return new PageView<>(page.getContent(), page.getNumber(), page.getTotalPages(), page.getTotalElements());
        }
    }

    public enum DueState {
        UPCOMING, DUE, OVERDUE, INACTIVE;

        static DueState from(Instant dueAt, Instant now, boolean active) {
            if (!active) return INACTIVE;
            if (dueAt.isBefore(now)) return OVERDUE;
            return dueAt.isAfter(now.plus(1, ChronoUnit.DAYS)) ? UPCOMING : DUE;
        }
    }

    public record PlanView(Long id, Long deviceId, String deviceCode, String deviceName, String title, int intervalDays,
            Instant nextDueAt, String instructions, String assignee, boolean enabled, long version, DueState dueState) {
        static PlanView from(MaintenancePlanEntity plan, Instant now) {
            DeviceEntity device = plan.getDevice();
            return new PlanView(plan.getId(), device.getId(), device.getDeviceCode(), device.getDeviceName(), plan.getTitle(),
                    plan.getIntervalDays(), plan.getNextDueAt(), plan.getInstructions(), plan.getAssignee(), plan.isEnabled(),
                    plan.getVersion(), DueState.from(plan.getNextDueAt(), now, plan.isEnabled()));
        }
    }

    public record TaskView(Long id, Long planId, String planTitle, String instructions, Long deviceId, String deviceCode,
            String deviceName, Instant dueAt, DueState dueState, MaintenanceTaskStatus status, String assignee,
            String findings, String action, Instant createdAt, Instant updatedAt, Instant completedAt) {
        static TaskView from(MaintenanceTaskEntity task, Instant now) {
            DeviceEntity device = task.getDevice();
            MaintenancePlanEntity plan = task.getPlan();
            return new TaskView(task.getId(), plan == null ? null : plan.getId(), task.getPlanTitle(), task.getInstructions(),
                    device.getId(), device.getDeviceCode(), device.getDeviceName(), task.getDueAt(),
                    DueState.from(task.getDueAt(), now, task.getStatus().isActive()), task.getStatus(), task.getAssignee(),
                    task.getFindings(), task.getAction(), task.getCreatedAt(), task.getUpdatedAt(), task.getCompletedAt());
        }
    }
}
