package com.pdm.mvp.workorder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping({"/api/v1/work-orders", "/api/v1/workorders", "/api/v2/workorders"})
public class WorkOrderController {
    private final WorkOrderService workOrders;
    public WorkOrderController(WorkOrderService workOrders) { this.workOrders = workOrders; }
    @GetMapping public List<WorkOrderService.WorkOrderView> list() { return workOrders.list(); }

    /** 服务端分页 + 多维筛选（车间/设备/状态/优先级/来源/关键字）；无参 list() 行为保持不变供存量调用方使用。 */
    @GetMapping("/page")
    public WorkOrderService.WorkOrderPageView page(
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String keyword) {
        validateOffset(page, size);
        return workOrders.listPage(page, size, factoryCode, deviceId, status, priority, source, keyword);
    }

    /** 手工建单：日常点检发现跑冒滴漏 / 异响等问题时直接为设备（可精确到测点）创建独立维修工单。 */
    @PostMapping("/manual")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public WorkOrderService.WorkOrderView createManual(@Valid @RequestBody ManualWorkOrderRequest request) {
        return workOrders.createManual(request.deviceId(), request.pointId(), request.title(), request.priority(),
                request.maintenanceAction(), request.assignee(), request.spareParts());
    }

    private static void validateOffset(int page, int size) {
        if ((long) page * size > 100_000L)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页偏移量超出允许范围");
    }

    /** CMMS 一键派单（文档 §7.1 POST /api/v1/workorders）：按告警生成/更新工单并写入建议措施。 */
    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public WorkOrderService.WorkOrderView dispatch(@jakarta.validation.Valid @RequestBody DispatchRequest request) {
        return workOrders.dispatch(request.alarmEventId(), request.action(), request.priority(), request.spareParts());
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public WorkOrderService.WorkOrderView assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request) {
        return workOrders.assign(id, request.assignee());
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public WorkOrderService.WorkOrderView close(@PathVariable Long id, @Valid @RequestBody CloseRequest request) {
        return workOrders.close(id, request.resolutionNote(), request.diagnosisVerdict(),
                request.actualFinding(), request.actionTaken(), Boolean.TRUE.equals(request.requestBaselineReset()));
    }

    public record AssignRequest(@NotBlank String assignee) {}
    /** requestBaselineReset：换轴承/对中找正等大修后设备振动基线已变，闭环时可选联动发起设备基线重置申请。 */
    public record CloseRequest(@NotBlank String resolutionNote, DiagnosisVerdict diagnosisVerdict,
            @Size(max = 2000) String actualFinding, @Size(max = 2000) String actionTaken, Boolean requestBaselineReset) {
        public CloseRequest(String resolutionNote, DiagnosisVerdict diagnosisVerdict, String actualFinding, String actionTaken) {
            this(resolutionNote, diagnosisVerdict, actualFinding, actionTaken, false);
        }
    }
    public record DispatchRequest(@NotNull Long alarmEventId,
            @Size(max = 2000) String action, String priority,
            List<WorkOrderEntity.SparePart> spareParts) {}
    /** 手工建单请求：pointId 省略即整机点检/检修；priority 缺省 MEDIUM；assignee 非空则直接置为处理中。 */
    public record ManualWorkOrderRequest(@NotNull Long deviceId, Long pointId,
            @NotBlank @Size(max = 128) String title, String priority,
            @Size(max = 2000) String maintenanceAction, @Size(max = 80) String assignee,
            List<WorkOrderEntity.SparePart> spareParts) {}
}
