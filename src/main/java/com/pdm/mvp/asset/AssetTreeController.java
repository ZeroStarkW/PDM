package com.pdm.mvp.asset;

import com.pdm.mvp.analytics.DeviceHealthRollup;
import com.pdm.mvp.modality.TelemetryFreshnessPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资产树接口（文档 §7.1 GET /api/v1/assets/tree、§6.1 GET /api/v2/assets/tree）：
 * FACTORY → MACHINE → POINT 层级拓扑，节点带健康标签、基线状态、轴承型号与通道模型（模态插件/采样率/单位/机理参数）。
 */
@RestController
public class AssetTreeController {
    private final FactoryRepository factories;
    private final DeviceRepository devices;
    private final PointRepository points;
    private final TelemetryFreshnessPolicy freshness;
    private final com.pdm.mvp.modality.ModalityPluginRegistry plugins;

    public AssetTreeController(FactoryRepository factories, DeviceRepository devices, PointRepository points,
            TelemetryFreshnessPolicy freshness, com.pdm.mvp.modality.ModalityPluginRegistry plugins) {
        this.factories = factories;
        this.devices = devices;
        this.points = points;
        this.freshness = freshness;
        this.plugins = plugins;
    }

    /** v1 与 v2 同载荷：v2 面向多模态工作站，v2 路径复用同一逻辑（文档 §6.1）。 */
    @GetMapping({"/api/v1/assets/tree", "/api/v2/assets/tree"})
    @Transactional(readOnly = true)
    public List<FactoryNode> tree() {
        Instant now = Instant.now();
        List<PointEntity> allPoints = points.findAllWithDeviceContext();
        Map<Long, List<PointEntity>> pointsByDevice = allPoints.stream()
                .collect(Collectors.groupingBy(point -> point.getDevice().getId()));
        Map<Long, List<DeviceEntity>> devicesByFactory = devices.findAll().stream()
                .collect(Collectors.groupingBy(device -> device.getFactory().getId()));
        List<FactoryNode> tree = new ArrayList<>();
        for (FactoryEntity factory : factories.findAll()) {
            List<MachineNode> machines = new ArrayList<>();
            for (DeviceEntity device : devicesByFactory.getOrDefault(factory.getId(), List.of())) {
                List<PointEntity> devicePoints = pointsByDevice.getOrDefault(device.getId(), List.of())
                        .stream().sorted(Comparator.comparing(PointEntity::getPointCode)).toList();
                var summary = DeviceHealthRollup.assess(device, devicePoints, now, freshness.byModality());
                List<PointNode> pointNodes = devicePoints.stream()
                        .map(point -> PointNode.from(point, now, plugins)).toList();
                machines.add(new MachineNode(device.getId(), device.getDeviceCode(), device.getDeviceName(),
                        device.getRpmNominal(), device.getDeviceType() == null ? null : device.getDeviceType().getTypeName(),
                        summary.healthIndex(), summary.status(), pointNodes));
            }
            machines.sort(Comparator.comparing(MachineNode::code));
            tree.add(new FactoryNode(factory.getId(), factory.getFactoryCode(), factory.getFactoryName(), machines));
        }
        tree.sort(Comparator.comparing(FactoryNode::code));
        return tree;
    }

    public record FactoryNode(Long id, String code, String name, List<MachineNode> children) {
        public String getNodeType() { return "FACTORY"; }
    }

    public record MachineNode(Long id, String code, String name, java.math.BigDecimal rpmNominal,
            String deviceType, Double healthIndex, String status, List<PointNode> children) {
        public String getNodeType() { return "MACHINE"; }
    }

    public record PointNode(Long id, String code, String name, String modalityType,
            String baselineStatus, int learnedSamples, Double healthIndex, String lastStatus,
            Instant lastTelemetryAt, String bearingModel,
            String pluginIdentifier, Integer samplingRate, String engineeringUnit,
            java.util.Map<String, Object> metadataConfig, String cadence) {
        public String getNodeType() { return "POINT"; }

        /** 个别测点模态未注册插件时不得让整棵资产树 400：pluginIdentifier / cadence 置 null。 */
        static PointNode from(PointEntity point, Instant now, com.pdm.mvp.modality.ModalityPluginRegistry plugins) {
            var plugin = plugins.find(point.getModalityType()).orElse(null);
            return new PointNode(point.getId(), point.getPointCode(), point.getPointName(), point.getModalityType(),
                    BaselineStatusName.of(point.getBaselineStatus()), point.getLearnedSamples(),
                    point.getLastHealthIndex(), point.getLastStatus(), point.getLastTelemetryAt(),
                    point.getBearingModel() == null ? null : point.getBearingModel().getModelName(),
                    plugin == null ? null : plugin.identifier(), point.getSamplingRate(), point.getEngineeringUnit(),
                    point.metadataConfig(), plugin == null ? null : plugin.cadence().name());
        }
    }

    /** 基线状态名（与 BaselineService 状态机一致，避免反向依赖）。 */
    static final class BaselineStatusName {
        private BaselineStatusName() {}
        static String of(short status) {
            return switch (status) {
                case 1 -> "ACTIVE";
                case 2 -> "PENDING_RESET";
                case 3 -> "DIVERGED";
                default -> "LEARNING";
            };
        }
    }
}
