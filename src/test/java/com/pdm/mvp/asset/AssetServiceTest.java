package com.pdm.mvp.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pdm.mvp.analytics.DegradationTracker;
import com.pdm.mvp.analytics.HealthIndexCalculator;
import com.pdm.mvp.analytics.RotatingVibrationPlugin;
import com.pdm.mvp.bearing.BearingLibraryService;
import com.pdm.mvp.controlvalve.ControlValvePlugin;
import com.pdm.mvp.controlvalve.ControlValveProperties;
import com.pdm.mvp.mcsa.McsaPlugin;
import com.pdm.mvp.mcsa.McsaProperties;
import com.pdm.mvp.modality.ModalityPluginRegistry;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.modality.TelemetryFreshnessPolicy;
import com.pdm.mvp.switchgear.CircuitBreakerPlugin;
import com.pdm.mvp.switchgear.SwitchgearProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 通道模型校验与资产视图容错：建点时模态标识归一化、metadata_config 阈值约束（建点与更新）、
 * 模态未注册的测点不得让测点列表 / 资产树整体 400。
 */
class AssetServiceTest {
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final PointRepository points = mock(PointRepository.class);
    private final FactoryRepository factories = mock(FactoryRepository.class);
    private final BearingLibraryService bearings = mock(BearingLibraryService.class);
    private final ModalityPluginRegistry plugins = new ModalityPluginRegistry(List.of(
            vibrationPlugin(), breakerPlugin(), valvePlugin(), mcsaPlugin()));
    private final AssetService service = new AssetService(factories, devices, points, mock(DeviceTypeService.class),
            plugins, bearings);

    /** 建点时模态标识与遥测入口同一套归一化：别名 ELECTRICAL_MCSA、小写、首尾空白均可被接受。 */
    @Test
    void createPointNormalizesModalityIdentifierLikeTelemetry() {
        stubDevice();
        var mcsa = service.createPoint(5L, "current", "定子电流", null, "electrical_mcsa", null, null, null, null, null);
        assertEquals("MCSA", mcsa.modalityType());
        assertEquals("McsaPlugin", mcsa.pluginIdentifier());
        assertEquals("CONTINUOUS", mcsa.cadence());

        var breaker = service.createPoint(5L, "trip_unit", "分闸特性", null, " switchgear_event ", null, null, null,
                null, "switchgearEventPlugin");
        assertEquals("SWITCHGEAR_EVENT", breaker.modalityType());
        assertEquals("switchgearEventPlugin", breaker.pluginIdentifier());
        assertEquals("EVENT", breaker.cadence());

        var defaulted = service.createPoint(5L, "DE_RADIAL", "振动", null, "  ", null, null, null, null, null);
        assertEquals("ROTATING_VIB", defaulted.modalityType(), "未填模态默认旋转振动");
    }

    /** 通道级 metadata_config 不得绕过全局阈值约束：duration_danger_ratio=0.5 之类会让每次动作都硬限告警。 */
    @Test
    void createPointRejectsBreakerMetadataThatViolatesThresholdOrdering() {
        stubDevice();
        assertBadMetadata("SWITCHGEAR_EVENT", Map.of("duration_danger_ratio", 0.5), "duration_danger_ratio");
        // 只覆盖一个键时，按实际生效值（未填写的键沿用全局配置）校验
        assertBadMetadata("SWITCHGEAR_EVENT", Map.of("duration_danger_ratio", 1.05), "duration_danger_ratio");
        assertBadMetadata("SWITCHGEAR_EVENT", Map.of("duration_attention_ratio", 1.3), "duration_attention_ratio");
        assertBadMetadata("SWITCHGEAR_EVENT", Map.of("current_attention_ratio", 1.5), "current_attention_ratio");
        assertBadMetadata("SWITCHGEAR_EVENT", Map.of("current_attention_ratio", 0.0), "current_attention_ratio");
        assertBadMetadata("SWITCHGEAR_EVENT", Map.of("duration_danger_ratio", Double.NaN), "有限数值");
        assertBadMetadata("SWITCHGEAR_EVENT", Map.of("duration_danger_ratio", "1.2"), "必须为数值");
        verify(points, never()).save(any(PointEntity.class));

        var valid = service.createPoint(5L, "TRIP_UNIT", "分闸特性", null, "SWITCHGEAR_EVENT", null, null, null,
                Map.of("duration_attention_ratio", 1.05, "duration_danger_ratio", 1.15, "current_attention_ratio", 0.9),
                null);
        assertEquals(1.15, valid.metadataConfig().get("duration_danger_ratio"));
    }

    @Test
    void createPointRejectsValveMetadataThatViolatesThresholdOrdering() {
        stubDevice();
        assertBadMetadata("CONTROL_VALVE_PROCESS", Map.of("leak_danger_rms", 0.1), "leak_danger_rms");
        assertBadMetadata("CONTROL_VALVE_PROCESS", Map.of("leak_attention_rms", 0.0), "leak_attention_rms");
        assertBadMetadata("CONTROL_VALVE_PROCESS", Map.of("leak_attention_rms", 0.6), "leak_attention_rms");
        assertBadMetadata("CONTROL_VALVE_PROCESS", Map.of("deadband_attention_ratio", 0.9), "deadband_attention_ratio");
        assertBadMetadata("CONTROL_VALVE_PROCESS", Map.of("deadband_danger_ratio", 2.0), "deadband_danger_ratio");
        assertBadMetadata("CONTROL_VALVE_PROCESS", Map.of("leak_danger_rms", Double.POSITIVE_INFINITY), "有限数值");
        verify(points, never()).save(any(PointEntity.class));

        var valid = service.createPoint(5L, "LOOP", "回环路", null, "CONTROL_VALVE_PROCESS", null, null, null,
                Map.of("deadband_attention_ratio", 1.5, "leak_attention_rms", 0.3), null);
        assertEquals(0.3, valid.metadataConfig().get("leak_attention_rms"));
    }

    /** 更新测点的 metadata_config 走同一套约束。 */
    @Test
    void updatePointRevalidatesMetadataAgainstEffectiveThresholds() {
        PointEntity point = persistedPoint("SWITCHGEAR_EVENT");
        when(points.findById(1L)).thenReturn(Optional.of(point));

        var rejected = assertThrows(ResponseStatusException.class, () -> service.updatePoint(1L, "分闸特性", null, null,
                null, null, Map.of("duration_danger_ratio", 0.5), null));
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        assertTrue(rejected.getReason().contains("duration_danger_ratio"));
        assertNull(point.getMetadataConfig(), "被拒绝的更新不得落库");

        var updated = service.updatePoint(1L, "分闸特性", null, null, null, null, Map.of("duration_danger_ratio", 1.15), null);
        assertEquals(1.15, updated.metadataConfig().get("duration_danger_ratio"));
        assertEquals("switchgearEventPlugin", updated.pluginIdentifier());
    }

    /** 个别测点的模态未注册插件：测点列表与资产树照常返回，pluginIdentifier / cadence 为 null。 */
    @Test
    void pointListAndAssetTreeToleratePointsWithUnregisteredModality() {
        PointEntity unknown = persistedPoint("ULTRASONIC_HIVE");
        PointEntity known = persistedPoint(ModalityTypes.SWITCHGEAR_EVENT);
        when(points.findByDevice_IdOrderByPointCode(5L)).thenReturn(List.of(unknown, known));

        var views = service.points(5L);

        assertEquals(2, views.size());
        assertEquals("ULTRASONIC_HIVE", views.get(0).modalityType());
        assertNull(views.get(0).pluginIdentifier());
        assertNull(views.get(0).cadence());
        assertNotNull(views.get(1).pluginIdentifier());

        // 资产树：设备/工厂用 mock（真实实体的 id 在未持久化时为 null，groupingBy 不接受 null 键）
        DeviceEntity treeDevice = mock(DeviceEntity.class);
        when(treeDevice.getId()).thenReturn(3L);
        when(treeDevice.getDeviceCode()).thenReturn("BKR_10KV_02");
        when(treeDevice.getDeviceName()).thenReturn("10kV进线开关");
        when(treeDevice.getRpmNominal()).thenReturn(null);
        FactoryEntity treeFactory = mock(FactoryEntity.class);
        when(treeFactory.getId()).thenReturn(2L);
        when(treeFactory.getFactoryCode()).thenReturn("FACT_01");
        when(treeDevice.getFactory()).thenReturn(treeFactory);
        DeviceTypeEntity type = mock(DeviceTypeEntity.class);
        when(type.getId()).thenReturn(21L);
        when(type.getTypeName()).thenReturn("10kV开关");
        when(treeDevice.getDeviceType()).thenReturn(type);
        PointEntity hive = new PointEntity(treeDevice, "HIVE", "超声", null);
        hive.setModalityType("ULTRASONIC_HIVE");
        PointEntity trip = new PointEntity(treeDevice, "TRIP_UNIT", "分闸特性", null);
        trip.setModalityType(ModalityTypes.SWITCHGEAR_EVENT);
        when(factories.findAll()).thenReturn(List.of(treeFactory));
        when(devices.findAll()).thenReturn(List.of(treeDevice));
        when(points.findAllWithDeviceContext()).thenReturn(List.of(hive, trip));
        TelemetryFreshnessPolicy freshness = mock(TelemetryFreshnessPolicy.class);
        when(freshness.byModality()).thenReturn(modality -> Duration.ofMinutes(5));

        var tree = new AssetTreeController(factories, devices, points, freshness, plugins).tree();

        assertEquals(1, tree.size());
        var pointNodes = tree.getFirst().children().getFirst().children();
        assertEquals(2, pointNodes.size());
        assertEquals("ULTRASONIC_HIVE", pointNodes.get(0).modalityType());
        assertNull(pointNodes.get(0).pluginIdentifier(), "模态未注册的测点：插件标识为 null");
        assertNull(pointNodes.get(0).cadence());
        assertEquals("switchgearEventPlugin", pointNodes.get(1).pluginIdentifier());
        assertEquals("EVENT", pointNodes.get(1).cadence());
    }

    private void assertBadMetadata(String modality, Map<String, Object> metadata, String expectedText) {
        var exception = assertThrows(ResponseStatusException.class, () -> service.createPoint(5L, "CHANNEL", "通道", null,
                modality, null, null, null, metadata, null));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertTrue(exception.getReason().contains(expectedText),
                "应点名违规参数 " + expectedText + ": " + exception.getReason());
    }

    /** 建点前置：设备存在、编码未占用、保存原样返回。 */
    private void stubDevice() {
        when(devices.findById(5L)).thenReturn(Optional.of(device()));
        when(points.existsByDevice_IdAndPointCode(any(), any())).thenReturn(false);
        when(points.save(any(PointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 已存在的测点（未持久化，id 为 null）。 */
    private PointEntity persistedPoint(String modality) {
        var device = device();
        when(devices.findById(5L)).thenReturn(Optional.of(device));
        when(points.existsByDevice_IdAndPointCode(any(), any())).thenReturn(false);
        when(points.save(any(PointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        PointEntity point = new PointEntity(device, "CHANNEL", "通道", null);
        point.setModalityType(modality);
        return point;
    }

    private static DeviceEntity device() {
        return new DeviceEntity(new FactoryEntity("FACT_01", "一号工厂"), "BKR_10KV_02", "10kV进线开关", null, null, null);
    }

    private static RotatingVibrationPlugin vibrationPlugin() {
        return new RotatingVibrationPlugin(new HealthIndexCalculator(), null,
                new DegradationTracker(Duration.ofHours(24)));
    }

    private static CircuitBreakerPlugin breakerPlugin() {
        return new CircuitBreakerPlugin(new SwitchgearProperties(true, 1.10, 1.20, 0.80, Duration.ofDays(30),
                Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)));
    }

    private static ControlValvePlugin valvePlugin() {
        return new ControlValvePlugin(new ControlValveProperties(true, 2.5, 5.0, 0.2, 0.5, Duration.ofHours(1)),
                Duration.ofHours(24));
    }

    private static McsaPlugin mcsaPlugin() {
        return new McsaPlugin(new McsaProperties(false, 20, 100, 15, 54, 40), mock(DeviceTypeService.class));
    }
}
