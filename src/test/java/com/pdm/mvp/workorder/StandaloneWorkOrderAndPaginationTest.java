package com.pdm.mvp.workorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pdm.mvp.alert.AlertController;
import com.pdm.mvp.alert.AlertEntity;
import com.pdm.mvp.alert.AlertRepository;
import com.pdm.mvp.alert.AlertService;
import com.pdm.mvp.alert.DeviceHealthWindow;
import com.pdm.mvp.alert.NotificationRepository;
import com.pdm.mvp.analytics.BaselineService;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.DeviceRepository;
import com.pdm.mvp.asset.DeviceTypeEntity;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.diagnostics.DiagnosticMatch;
import com.pdm.mvp.diagnostics.DiagnosticMetric;
import com.pdm.mvp.modality.DiagnosticMicrokernel;
import com.pdm.mvp.modality.ModalityPluginRegistry;
import com.pdm.mvp.modality.TelemetryFreshnessPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * 任务单 1：独立手工维修工单 + 告警/工单服务端分页筛选（纯单元测试，mock 仓库层）。
 * 覆盖：手工建单（整机级 / 测点级 / 非本设备测点 400 拒绝 / 带指派直接 IN_PROGRESS）、
 * 手工工单闭环（不查不锁告警、正常写反馈闭环；requestBaselineReset 联动 requestDeviceReset(deviceId, null) 且 409 静默忽略）、
 * 分页参数校验（非法 status/priority/source/level 与超大 offset 一律 400）。
 */
class StandaloneWorkOrderAndPaginationTest {

    private static DeviceEntity device(long id, String code) {
        FactoryEntity factory = new FactoryEntity("FACT_01", "一号车间");
        ReflectionTestUtils.setField(factory, "id", 1L);
        DeviceTypeEntity type = mock(DeviceTypeEntity.class);
        when(type.getId()).thenReturn(1L);
        when(type.getTypeCode()).thenReturn("GENERAL");
        when(type.getTypeName()).thenReturn("通用设备");
        DeviceEntity device = new DeviceEntity(factory, code, "循环水泵" + code, new BigDecimal("1500.00"),
                new BigDecimal("55.00"), type, "CLASS_II");
        ReflectionTestUtils.setField(device, "id", id);
        return device;
    }

    private static PointEntity point(long id, DeviceEntity owner, String pointCode, Double lastHealthIndex) {
        PointEntity point = new PointEntity(owner, pointCode, "驱动端", null);
        ReflectionTestUtils.setField(point, "id", id);
        point.setLastHealthIndex(lastHealthIndex);
        return point;
    }

    private static WorkOrderRepository savingWorkOrders() {
        WorkOrderRepository workOrders = mock(WorkOrderRepository.class);
        when(workOrders.save(any(WorkOrderEntity.class))).thenAnswer(invocation -> {
            WorkOrderEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) ReflectionTestUtils.setField(entity, "id", 501L);
            return entity;
        });
        return workOrders;
    }

    // ==================== 手工建单 ====================

    @Test
    void manualWorkOrderOnWholeDeviceIsStandaloneAndDefaultsToMediumOpen() {
        DeviceEntity device = device(10L, "PUMP_01");
        DeviceRepository devices = mock(DeviceRepository.class);
        when(devices.findById(10L)).thenReturn(Optional.of(device));
        AlertRepository alerts = mock(AlertRepository.class);
        WorkOrderRepository workOrders = savingWorkOrders();
        WorkOrderService service = new WorkOrderService(workOrders, alerts, devices, mock(PointRepository.class));

        WorkOrderService.WorkOrderView view = service.createManual(10L, null, "  阀体法兰跑冒滴漏，需紧固并补脂  ",
                null, "使用扭矩扳手按 120N·m 紧固法兰螺栓", null, List.of(new WorkOrderEntity.SparePart("密封垫", "DN50", 2)));

        assertEquals(501L, view.id());
        assertNull(view.alertId(), "手工工单不得关联告警");
        assertEquals("MANUAL", view.source());
        assertEquals(10L, view.deviceId());
        assertEquals("FACT_01", view.factoryCode());
        assertEquals("PUMP_01", view.deviceCode());
        assertNull(view.pointId(), "整机级工单无关联测点");
        assertEquals("阀体法兰跑冒滴漏，需紧固并补脂", view.title(), "标题必须去首尾空白");
        assertEquals("阀体法兰跑冒滴漏，需紧固并补脂", view.dominantFactor());
        assertEquals("MEDIUM", view.priority(), "缺省优先级必须为 MEDIUM");
        assertEquals("OPEN", view.status());
        assertNull(view.assignee());
        assertNull(view.alertLevel());
        assertNull(view.healthIndex(), "无告警且无测点时 HI 必须为 null，不得 NPE");
        verify(workOrders).save(any(WorkOrderEntity.class));
        verify(alerts, never()).findByIdForUpdate(any());
    }

    @Test
    void manualWorkOrderOnDevicePointLinksPointAndCarriesLastHealthIndex() {
        DeviceEntity device = device(10L, "PUMP_01");
        PointEntity point = point(21L, device, "DE", 63.5);
        DeviceRepository devices = mock(DeviceRepository.class);
        PointRepository points = mock(PointRepository.class);
        when(devices.findById(10L)).thenReturn(Optional.of(device));
        when(points.findById(21L)).thenReturn(Optional.of(point));
        WorkOrderService service = new WorkOrderService(savingWorkOrders(), mock(AlertRepository.class), devices, points);

        WorkOrderService.WorkOrderView view = service.createManual(10L, 21L, "驱动端异响", "HIGH", null, null, null);

        assertEquals(21L, view.pointId());
        assertEquals("DE", view.pointCode());
        assertEquals("HIGH", view.priority());
        assertEquals(63.5, view.healthIndex(), "手工工单 HI 回退取测点最近健康指数");
        assertEquals("MANUAL", view.source());
    }

    @Test
    void manualWorkOrderRejectsPointOfForeignDeviceWith400() {
        DeviceEntity device = device(10L, "PUMP_01");
        PointEntity foreign = point(99L, device(11L, "PUMP_02"), "DE", null);
        DeviceRepository devices = mock(DeviceRepository.class);
        PointRepository points = mock(PointRepository.class);
        when(devices.findById(10L)).thenReturn(Optional.of(device));
        when(points.findById(99L)).thenReturn(Optional.of(foreign));
        WorkOrderService service = new WorkOrderService(savingWorkOrders(), mock(AlertRepository.class), devices, points);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createManual(10L, 99L, "错挂测点", null, null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("所选测点不属于该设备", ex.getReason());
    }

    @Test
    void manualWorkOrderWithAssigneeStartsInProgressAndValidatesTitleAndMissingRows() {
        DeviceEntity device = device(10L, "PUMP_01");
        DeviceRepository devices = mock(DeviceRepository.class);
        when(devices.findById(10L)).thenReturn(Optional.of(device));
        WorkOrderService service = new WorkOrderService(savingWorkOrders(), mock(AlertRepository.class), devices,
                mock(PointRepository.class));

        WorkOrderService.WorkOrderView assigned = service.createManual(10L, null, "基础地脚螺栓松动", "URGENT", null, "  王班长 ", null);
        assertEquals("IN_PROGRESS", assigned.status());
        assertEquals("王班长", assigned.assignee());

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.createManual(10L, null, "   ", null, null, null, null)).getStatusCode());
        String tooLong = "超".repeat(129);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.createManual(10L, null, tooLong, null, null, null, null)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.createManual(77L, null, "不存在的设备", null, null, null, null)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.createManual(10L, 88L, "不存在的测点", null, null, null, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.createManual(10L, null, "非法优先级", "CRITICAL", null, null, null)).getStatusCode());
    }

    // ==================== 手工工单闭环 ====================

    @Test
    void closingManualWorkOrderNeverLocksAlertAndSyncsFeedback() {
        WorkOrderRepository workOrders = mock(WorkOrderRepository.class);
        AlertRepository alerts = mock(AlertRepository.class);
        WorkOrderService service = new WorkOrderService(workOrders, alerts, mock(DeviceRepository.class),
                mock(PointRepository.class));
        WorkOrderEntity order = WorkOrderEntity.manual(device(10L, "PUMP_01"), null, "联轴器护罩变形",
                WorkOrderEntity.Priority.HIGH, "更换护罩", null, List.of());
        ReflectionTestUtils.setField(order, "id", 60L);
        when(workOrders.findAlertId(60L)).thenReturn(Optional.empty());
        when(workOrders.findByIdForUpdate(60L)).thenReturn(Optional.of(order));

        WorkOrderService.WorkOrderView closed = service.close(60L, "已更换护罩并复测振动合格", DiagnosisVerdict.CONFIRMED,
                "护罩碰磨", "更换防护罩", false);

        assertEquals("CLOSED", closed.status());
        assertNull(closed.alertId());
        assertEquals("MANUAL", closed.source());
        assertEquals("已更换护罩并复测振动合格", order.getResolutionNote());
        assertEquals("已更换护罩并复测振动合格", closed.resolutionNote());
        assertEquals(DiagnosisVerdict.CONFIRMED, order.getDiagnosisVerdict());
        verify(alerts, never()).findByIdForUpdate(any());
    }

    @Test
    void manualWorkOrderCloseRequestsBaselineResetAndSwallowsConflict() {
        WorkOrderRepository workOrders = mock(WorkOrderRepository.class);
        AlertRepository alerts = mock(AlertRepository.class);
        BaselineService baselines = mock(BaselineService.class);
        WorkOrderService service = new WorkOrderService(workOrders, alerts, mock(DeviceRepository.class),
                mock(PointRepository.class));
        ReflectionTestUtils.setField(service, "baselines", baselines);

        WorkOrderEntity order = WorkOrderEntity.manual(device(10L, "PUMP_01"), null, "轴承位温升异常",
                WorkOrderEntity.Priority.MEDIUM, null, null, List.of());
        ReflectionTestUtils.setField(order, "id", 61L);
        when(workOrders.findAlertId(61L)).thenReturn(Optional.empty());
        when(workOrders.findByIdForUpdate(61L)).thenReturn(Optional.of(order));
        service.close(61L, "更换轴承后复测合格", DiagnosisVerdict.CONFIRMED, "保持架磨损", "更换 SKF 6310", true);
        verify(baselines).requestDeviceReset(10L, null);

        // 409（如设备还没有测点）必须静默忽略，不阻断闭环
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "设备还没有测点")).when(baselines)
                .requestDeviceReset(anyLong(), any());
        WorkOrderEntity second = WorkOrderEntity.manual(device(12L, "PUMP_03"), null, "密封渗漏",
                WorkOrderEntity.Priority.LOW, null, null, List.of());
        ReflectionTestUtils.setField(second, "id", 62L);
        when(workOrders.findAlertId(62L)).thenReturn(Optional.empty());
        when(workOrders.findByIdForUpdate(62L)).thenReturn(Optional.of(second));
        WorkOrderService.WorkOrderView closed = service.close(62L, "更换机械密封", null, null, null, true);
        assertEquals("CLOSED", closed.status());

        // 工单确实不存在时仍必须 404（findAlertId 为空的另一语义），且绝不触碰告警锁
        when(workOrders.findAlertId(63L)).thenReturn(Optional.empty());
        when(workOrders.findByIdForUpdate(63L)).thenReturn(Optional.empty());
        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> service.close(63L, "note", null, null, null, false));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        verify(alerts, never()).findByIdForUpdate(any());

        // 已抑制手工工单闭环 → 409
        WorkOrderEntity suppressed = WorkOrderEntity.manual(device(13L, "PUMP_04"), null, "误报点检", null, null, null, List.of());
        ReflectionTestUtils.setField(suppressed, "id", 64L);
        suppressed.close("抑制说明", true);
        when(workOrders.findAlertId(64L)).thenReturn(Optional.empty());
        when(workOrders.findByIdForUpdate(64L)).thenReturn(Optional.of(suppressed));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.close(64L, "note", null, null, null, false)).getStatusCode());
    }

    /** 回归：alert 链路闭环仍保持“先锁告警、再锁工单”的原顺序与告警状态同步。 */
    @Test
    void alertWorkOrderCloseKeepsLockOrderingAndAlertStatusSync() {
        WorkOrderRepository workOrders = mock(WorkOrderRepository.class);
        AlertRepository alerts = mock(AlertRepository.class);
        PointEntity point = point(31L, device(10L, "PUMP_01"), "DE", null);
        AlertEntity alert = new AlertEntity(point, "DANGER", 22.0,
                new DiagnosticMatch(1L, "通用设备", null, null, DiagnosticMetric.ENVELOPE_RMS, null, null, null, null,
                        null, null, "轴承内圈点蚀损伤", "BPFI 包络显著恶化", "更换轴承"), 2.4, 0.45, List.of());
        ReflectionTestUtils.setField(alert, "id", 70L);
        WorkOrderEntity order = new WorkOrderEntity(alert);
        ReflectionTestUtils.setField(order, "id", 71L);
        when(workOrders.findAlertId(71L)).thenReturn(Optional.of(70L));
        when(alerts.findByIdForUpdate(70L)).thenReturn(Optional.of(alert));
        when(workOrders.findByIdForUpdate(71L)).thenReturn(Optional.of(order));

        WorkOrderService.WorkOrderView closed = new WorkOrderService(workOrders, alerts)
                .close(71L, "处理完成", DiagnosisVerdict.UNVERIFIED, null, null, false);

        assertEquals("CLOSED", closed.status());
        assertEquals(70L, closed.alertId());
        assertEquals("ALERT", closed.source());
        assertEquals((short) 2, alert.getStatus());
        assertNotNull(order.getDevice(), "告警转单必须冗余设备");
    }

    // ==================== 分页参数校验 ====================

    @Test
    void workOrderPageRejectsIllegalFiltersWith400() {
        WorkOrderRepository illegalStub = mock(WorkOrderRepository.class);
        WorkOrderService service = new WorkOrderService(illegalStub, mock(AlertRepository.class),
                mock(DeviceRepository.class), mock(PointRepository.class));
        assertEquals("非法的工单状态筛选", assertThrows(ResponseStatusException.class,
                () -> service.listPage(0, 20, null, null, "DONE", null, null, null)).getReason());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.listPage(0, 20, null, null, null, "CRITICAL", null, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.listPage(0, 20, null, null, null, null, "AUTO", null)).getStatusCode());
        verifyNoInteractions(illegalStub);

        WorkOrderRepository workOrders = mock(WorkOrderRepository.class);
        when(workOrders.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        WorkOrderService legal = new WorkOrderService(workOrders, mock(AlertRepository.class),
                mock(DeviceRepository.class), mock(PointRepository.class));
        var emptyPage = legal.listPage(0, 20, " FACT_01 ", 10L, "PENDING", "HIGH", "MANUAL", "泵");
        assertEquals(0, emptyPage.items().size());
        assertEquals(0, emptyPage.page());
        assertEquals(20, emptyPage.size());
        assertEquals(0L, emptyPage.totalItems());
        assertEquals(0L, emptyPage.pendingCount());
    }

    @Test
    void alertPageRejectsIllegalLevelAndStatusWith400() {
        AlertRepository alerts = mock(AlertRepository.class);
        when(alerts.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(alerts.count(any(Specification.class))).thenReturn(7L);
        AlertService service = new AlertService(new DeviceHealthWindow(), alerts, mock(WorkOrderRepository.class),
                mock(PointRepository.class), mock(NotificationRepository.class), mock(TelemetryFreshnessPolicy.class),
                mock(DiagnosticMicrokernel.class), new ModalityPluginRegistry(List.of()));

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.listPage(0, 20, null, null, "CRITICAL", null, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.listPage(0, 20, null, null, null, "UNKNOWN", null)).getStatusCode());

        AlertService.AlertPageView page = service.listPage(0, 20, "fact_01", null, "DANGER", "OPEN", "泵");
        assertEquals(0, page.items().size());
        assertEquals(7L, page.dangerOpenCount());
        assertEquals(7L, page.warningOpenCount());
        assertEquals(7L, page.resolvedCount());
        assertEquals(20, page.size());
    }

    @Test
    void controllersRejectExcessivePagingOffsetWith400() {
        AlertService alertService = mock(AlertService.class);
        ResponseStatusException alertOffset = assertThrows(ResponseStatusException.class,
                () -> new AlertController(alertService).page(1001, 100, null, null, null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, alertOffset.getStatusCode());
        verifyNoInteractions(alertService);

        WorkOrderService workOrderService = mock(WorkOrderService.class);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> new WorkOrderController(workOrderService).page(2000, 100, null, null, null, null, null, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> new WorkOrderController(workOrderService).page(1_000_000, 20, null, null, null, null, null, null)).getStatusCode());
        verifyNoInteractions(workOrderService);
    }
}
