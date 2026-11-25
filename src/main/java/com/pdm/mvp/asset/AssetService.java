package com.pdm.mvp.asset;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class AssetService {
    private final FactoryRepository factories;
    private final DeviceRepository devices;
    private final PointRepository points;
    private final DeviceTypeService deviceTypes;
    private final com.pdm.mvp.modality.ModalityPluginRegistry plugins;
    private final com.pdm.mvp.bearing.BearingLibraryService bearings;

    public AssetService(FactoryRepository factories, DeviceRepository devices, PointRepository points,
            DeviceTypeService deviceTypes, com.pdm.mvp.modality.ModalityPluginRegistry plugins,
            com.pdm.mvp.bearing.BearingLibraryService bearings) {
        this.factories = factories; this.devices = devices; this.points = points; this.deviceTypes = deviceTypes;
        this.plugins = plugins; this.bearings = bearings;
    }

    @Transactional(readOnly = true)
    public List<FactoryView> factories() { return factories.findAll().stream().map(FactoryView::from).toList(); }

    public FactoryView createFactory(String code, String name) {
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (factories.findByFactoryCode(normalized).isPresent()) throw conflict("工厂编码已存在");
        return FactoryView.from(factories.save(new FactoryEntity(normalized, name.trim())));
    }

    public FactoryView updateFactory(Long id, String name) {
        FactoryEntity factory = factories.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工厂不存在"));
        factory.setFactoryName(name.trim());
        return FactoryView.from(factory);
    }

    @Transactional(readOnly = true)
    public List<DeviceView> devices(String factoryCode) {
        return devices.findByFactory_FactoryCodeOrderByDeviceCode(factoryCode).stream().map(DeviceView::from).toList();
    }

    private static final java.util.Set<String> NON_ROTATING_TYPE_CODES = java.util.Set.of(
            "CIRCUIT_BREAKER", "CONTROL_VALVE");

    public DeviceView createDevice(String factoryCode, String code, String name, BigDecimal rpm, BigDecimal power,
            Long deviceTypeId) {
        return createDevice(factoryCode, code, name, rpm, power, deviceTypeId, null);
    }

    public DeviceView createDevice(String factoryCode, String code, String name, BigDecimal rpm, BigDecimal power,
            Long deviceTypeId, String isoClass) {
        FactoryEntity factory = factories.findByFactoryCode(factoryCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工厂不存在"));
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (devices.findByFactory_FactoryCodeAndDeviceCode(factoryCode, normalized).isPresent()) throw conflict("设备编码已存在");
        DeviceTypeEntity type = deviceTypeId == null ? deviceTypes.general() : deviceTypes.require(deviceTypeId);
        if (rpm != null && rpm.signum() < 0) throw bad("rpm_nominal 不能为负数");
        BigDecimal effectiveRpm = rpm != null ? rpm
                : (type != null && NON_ROTATING_TYPE_CODES.contains(type.getTypeCode()) ? BigDecimal.ZERO : null);
        String resolvedIso = validateIsoClass(isoClass, "CLASS_II");
        return DeviceView.from(devices.save(new DeviceEntity(factory, normalized, name.trim(), effectiveRpm, power, type, resolvedIso)));
    }

    public DeviceView updateDevice(Long id, String name, BigDecimal rpm, BigDecimal power, short status, Long deviceTypeId) {
        return updateDevice(id, name, rpm, power, status, deviceTypeId, null);
    }

    public DeviceView updateDevice(Long id, String name, BigDecimal rpm, BigDecimal power, short status,
            Long deviceTypeId, String isoClass) {
        if (status < DeviceEntity.STATUS_ACTIVE || status > DeviceEntity.STATUS_RETIRED)
            throw bad("status 只能是 1(在运)/2(检修)/3(报废)");
        if (rpm != null && rpm.signum() < 0) throw bad("rpm_nominal 不能为负数");
        DeviceEntity device = devices.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"));
        DeviceTypeEntity type = deviceTypeId == null ? device.getDeviceType() : deviceTypes.require(deviceTypeId);
        String resolvedIso = validateIsoClass(isoClass, device.getIsoClass());
        BigDecimal effectiveRpm = rpm == null ? BigDecimal.ZERO : rpm;
        device.update(name.trim(), effectiveRpm, power, status, type, resolvedIso);
        return DeviceView.from(device);
    }

    private String validateIsoClass(String isoClass, String fallback) {
        if (isoClass == null || isoClass.isBlank()) return fallback == null || fallback.isBlank() ? "CLASS_II" : fallback;
        try {
            return com.pdm.mvp.analytics.HealthIndexCalculator.IsoClass.normalizeStrict(isoClass);
        } catch (IllegalArgumentException ex) {
            throw bad("iso_class 只能是 CLASS_I/CLASS_II/CLASS_III/CLASS_IV");
        }
    }

    @Transactional(readOnly = true)
    public List<PointView> points(Long deviceId) {
        return points.findByDevice_IdOrderByPointCode(deviceId).stream()
                .map(point -> PointView.from(point, plugins)).toList();
    }

    /** 按车间一次取回全部测点：供资产管理页替代按设备逐台查询（N+1）的批量路径。 */
    @Transactional(readOnly = true)
    public List<PointView> pointsByFactory(String factoryCode) {
        return points.findByFactoryCodeWithContext(factoryCode.trim().toUpperCase(Locale.ROOT)).stream()
                .map(point -> PointView.from(point, plugins)).toList();
    }

    public PointView createPoint(Long deviceId, String code, String name, String sensorMac, String modalityType,
            Long bearingModelId, Integer samplingRate, String engineeringUnit,
            java.util.Map<String, Object> metadataConfig, String pluginIdentifier) {
        DeviceEntity device = devices.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"));
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        // 大小写不敏感查重：库内历史行可能为小写变体（唯一键区分大小写），忽略大小写防止同义重码后定位 409
        if (points.existsByDevice_IdAndPointCodeIgnoreCase(deviceId, normalized))
            throw conflict("测点编码已存在");
        // 模态标识与遥测入口同一套归一化（去空白、统一大写、ELECTRICAL_MCSA → MCSA），再解析插件
        String modality = com.pdm.mvp.modality.ModalityTypes.normalize(modalityType);
        if (modality == null) modality = com.pdm.mvp.modality.ModalityTypes.ROTATING_VIB;
        var plugin = plugins.require(modality);
        validateChannelFields(plugin, samplingRate, engineeringUnit, metadataConfig, pluginIdentifier);
        PointEntity point = new PointEntity(device, normalized, name.trim(), sensorMac);
        point.setModalityType(modality);
        point.setSamplingRate(samplingRate);
        point.setEngineeringUnit(engineeringUnit == null || engineeringUnit.isBlank() ? null : engineeringUnit.trim());
        point.setMetadataConfig(metadataConfig);
        point.setBearingModel(bearings.require(bearingModelId));
        return PointView.from(points.save(point), plugins);
    }

    /** bearingModelId 语义：null=保持不变，0=解除绑定，>0=绑定指定型号；通道机理字段 null=保持不变。 */
    public PointView updatePoint(Long id, String name, String sensorMac, Long bearingModelId,
            Integer samplingRate, String engineeringUnit, java.util.Map<String, Object> metadataConfig,
            String pluginIdentifier) {
        PointEntity point = points.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "测点不存在"));
        point.update(name.trim(), sensorMac == null || sensorMac.isBlank() ? null : sensorMac.trim());
        if (bearingModelId != null) point.setBearingModel(bearingModelId <= 0 ? null : bearings.require(bearingModelId));
        validateChannelFields(plugins.require(point.getModalityType()), samplingRate, engineeringUnit, metadataConfig,
                pluginIdentifier);
        if (samplingRate != null) point.setSamplingRate(samplingRate);
        if (engineeringUnit != null) point.setEngineeringUnit(engineeringUnit.isBlank() ? null : engineeringUnit.trim());
        if (metadataConfig != null) point.setMetadataConfig(metadataConfig);
        return PointView.from(point, plugins);
    }

    /** 通道模型字段校验：采样率、工程单位、机理参数与插件标识必须与测点模态一致（机理参数约束委托插件 validateMetadata）。 */
    private void validateChannelFields(com.pdm.mvp.modality.ModalityPlugin plugin, Integer samplingRate,
            String engineeringUnit, java.util.Map<String, Object> metadataConfig, String pluginIdentifier) {
        if (samplingRate != null && samplingRate <= 0)
            throw bad("sampling_rate 必须为正整数");
        if (engineeringUnit != null && engineeringUnit.length() > 32)
            throw bad("engineering_unit 长度不能超过 32");
        if (metadataConfig != null) {
            for (var entry : metadataConfig.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) throw bad("metadata_config 键不能为空");
                if (!(entry.getValue() instanceof Number || entry.getValue() instanceof String))
                    throw bad("metadata_config 值只能是数值或字符串: " + entry.getKey());
            }
            for (var field : plugin.metadataFields()) {
                if (metadataConfig.containsKey(field.key())
                        && !(metadataConfig.get(field.key()) instanceof Number))
                    throw bad("metadata_config 参数必须为数值: " + field.key());
            }
            // 通道级覆盖不得绕过全局配置的阈值约束（否则如 duration_danger_ratio=0.5 会让每次动作都硬限告警）
            plugin.validateMetadata(metadataConfig);
        }
        if (pluginIdentifier != null && !pluginIdentifier.isBlank() && !pluginIdentifier.equals(plugin.identifier()))
            throw bad("plugin_identifier 与测点模态不匹配");
    }

    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    public record FactoryView(Long id, String factoryCode, String factoryName) {
        static FactoryView from(FactoryEntity entity) { return new FactoryView(entity.getId(), entity.getFactoryCode(), entity.getFactoryName()); }
    }
    public record DeviceView(Long id, Long factoryId, String factoryCode, String deviceCode, String deviceName,
            BigDecimal rpmNominal, BigDecimal powerKw, String isoClass, short status, Long deviceTypeId,
            String deviceTypeCode, String deviceTypeName) {
        static DeviceView from(DeviceEntity entity) {
            DeviceTypeEntity type = entity.getDeviceType();
            return new DeviceView(entity.getId(), entity.getFactory().getId(), entity.getFactory().getFactoryCode(),
                    entity.getDeviceCode(), entity.getDeviceName(), entity.getRpmNominal(), entity.getPowerKw(),
                    entity.getIsoClass(), entity.getStatus(), type.getId(), type.getTypeCode(), type.getTypeName());
        }
    }
    public record PointView(Long id, Long deviceId, String pointCode, String pointName, String sensorMac,
            String modalityType, Double freq1xBase, Double vBaseRms, short baselineStatus, int learnedSamples,
            Double healthIndex, String lastStatus, java.time.Instant lastTelemetryAt,
            Long bearingModelId, String bearingModelName, Double bpfoFactor, Double bpfiFactor,
            Double bsfFactor, Double ftfFactor,
            String pluginIdentifier, Integer samplingRate, String engineeringUnit,
            java.util.Map<String, Object> metadataConfig, String cadence) {
        /** 个别测点模态未注册插件时不得让整个测点列表 400：pluginIdentifier / cadence 置 null。 */
        static PointView from(PointEntity entity, com.pdm.mvp.modality.ModalityPluginRegistry plugins) {
            var bearing = entity.getBearingModel();
            var plugin = plugins.find(entity.getModalityType()).orElse(null);
            return new PointView(entity.getId(), entity.getDevice().getId(),
                    entity.getPointCode(), entity.getPointName(), entity.getSensorMac(), entity.getModalityType(),
                    entity.getFreq1xBase(), entity.getVBaseRms(), entity.getBaselineStatus(), entity.getLearnedSamples(),
                    entity.getLastHealthIndex(), entity.getLastStatus(), entity.getLastTelemetryAt(),
                    bearing == null ? null : bearing.getId(),
                    bearing == null ? null : bearing.getModelName(),
                    bearing == null ? null : bearing.getBpfoFactor().doubleValue(),
                    bearing == null ? null : bearing.getBpfiFactor().doubleValue(),
                    bearing == null ? null : bearing.getBsfFactor().doubleValue(),
                    bearing == null ? null : bearing.getFtfFactor().doubleValue(),
                    plugin == null ? null : plugin.identifier(), entity.getSamplingRate(), entity.getEngineeringUnit(),
                    entity.metadataConfig(), plugin == null ? null : plugin.cadence().name());
        }
    }
}
