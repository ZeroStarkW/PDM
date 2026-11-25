package com.pdm.mvp.asset;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AssetController {
    private final AssetService assets;
    public AssetController(AssetService assets) { this.assets = assets; }

    @GetMapping("/factories")
    public List<AssetService.FactoryView> factories() { return assets.factories(); }

    @PostMapping("/factories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public AssetService.FactoryView createFactory(@Valid @RequestBody FactoryRequest request) {
        return assets.createFactory(request.factoryCode(), request.factoryName());
    }

    @PutMapping("/factories/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public AssetService.FactoryView updateFactory(@PathVariable Long id, @Valid @RequestBody FactoryUpdateRequest request) {
        return assets.updateFactory(id, request.factoryName());
    }

    @GetMapping("/devices")
    public List<AssetService.DeviceView> devices(@RequestParam String factoryCode) { return assets.devices(factoryCode); }

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public AssetService.DeviceView createDevice(@Valid @RequestBody DeviceRequest request) {
        return assets.createDevice(request.factoryCode(), request.deviceCode(), request.deviceName(), request.rpmNominal(),
                request.powerKw(), request.deviceTypeId(), request.isoClass());
    }

    @PutMapping("/devices/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public AssetService.DeviceView updateDevice(@PathVariable Long id, @Valid @RequestBody DeviceUpdateRequest request) {
        return assets.updateDevice(id, request.deviceName(), request.rpmNominal(), request.powerKw(),
                request.status().shortValue(), request.deviceTypeId(), request.isoClass());
    }

    /** 保留单参方法供既有 Java 调用方直接复用（无 HTTP 绑定）。 */
    public List<AssetService.PointView> points(Long deviceId) { return assets.points(deviceId); }

    /** 测点查询：deviceId 单设备 / factoryCode 整车间一次批量（消除管理页 N+1），二者至少提供一个。 */
    @GetMapping("/points")
    public List<AssetService.PointView> points(@RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String factoryCode) {
        if (deviceId != null) return assets.points(deviceId);
        if (factoryCode != null && !factoryCode.isBlank()) return assets.pointsByFactory(factoryCode);
        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                "deviceId 或 factoryCode 至少提供一个");
    }

    @PostMapping("/points")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public AssetService.PointView createPoint(@Valid @RequestBody PointRequest request) {
        return assets.createPoint(request.deviceId(), request.pointCode(), request.pointName(), request.sensorMac(),
                request.modalityType(), request.bearingModelId(), request.samplingRate(), request.engineeringUnit(),
                request.metadataConfig(), request.pluginIdentifier());
    }

    @PutMapping("/points/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public AssetService.PointView updatePoint(@PathVariable Long id, @Valid @RequestBody PointUpdateRequest request) {
        return assets.updatePoint(id, request.pointName(), request.sensorMac(), request.bearingModelId(),
                request.samplingRate(), request.engineeringUnit(), request.metadataConfig(), request.pluginIdentifier());
    }

    public record FactoryRequest(@NotBlank String factoryCode, @NotBlank String factoryName) {}
    public record FactoryUpdateRequest(@NotBlank String factoryName) {}
    public record DeviceRequest(@NotBlank String factoryCode, @NotBlank String deviceCode, @NotBlank String deviceName,
            @DecimalMin("0") BigDecimal rpmNominal, @DecimalMin("0") BigDecimal powerKw, @Positive Long deviceTypeId,
            String isoClass) {
        public DeviceRequest(String factoryCode, String deviceCode, String deviceName,
                BigDecimal rpmNominal, BigDecimal powerKw, Long deviceTypeId) {
            this(factoryCode, deviceCode, deviceName, rpmNominal, powerKw, deviceTypeId, null);
        }
    }
    public record PointRequest(@NotNull Long deviceId, @NotBlank String pointCode, @NotBlank String pointName,
            String sensorMac, String modalityType, Long bearingModelId,
            Integer samplingRate, String engineeringUnit, java.util.Map<String, Object> metadataConfig,
            String pluginIdentifier) {}
    public record DeviceUpdateRequest(@NotBlank String deviceName, @DecimalMin("0") BigDecimal rpmNominal,
            @DecimalMin("0") BigDecimal powerKw, @NotNull @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(3) Integer status,
            @Positive Long deviceTypeId, String isoClass) {
        public DeviceUpdateRequest(String deviceName, BigDecimal rpmNominal, BigDecimal powerKw, Integer status,
                Long deviceTypeId) {
            this(deviceName, rpmNominal, powerKw, status, deviceTypeId, null);
        }
    }
    /** bearingModelId：null=保持不变，0=解除绑定，>0=绑定型号；通道机理字段 null=保持不变。 */
    public record PointUpdateRequest(@NotBlank String pointName, String sensorMac, Long bearingModelId,
            Integer samplingRate, String engineeringUnit, java.util.Map<String, Object> metadataConfig,
            String pluginIdentifier) {}
}
