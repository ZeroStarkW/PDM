package com.pdm.mvp.asset;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class DeviceTypeService {
    private static final Pattern TYPE_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,31}");
    private final DeviceTypeRepository types;
    private final DeviceRepository devices;

    public DeviceTypeService(DeviceTypeRepository types, DeviceRepository devices) {
        this.types = types;
        this.devices = devices;
    }

    @Transactional(readOnly = true)
    public List<DeviceTypeView> list() {
        Map<Long, Long> counts = devices.countByDeviceType().stream().collect(Collectors.toMap(
                DeviceRepository.DeviceTypeCount::getTypeId, DeviceRepository.DeviceTypeCount::getDeviceCount));
        return types.findAllByOrderByTypeCodeAsc().stream()
                .map(type -> DeviceTypeView.from(type, counts.getOrDefault(type.getId(), 0L)))
                .toList();
    }

    public DeviceTypeView create(String code, String name) {
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        if (!TYPE_CODE.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "类型编码需由字母、数字、下划线或短横线组成，且以字母或数字开头");
        }
        if (types.existsByTypeCode(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备类型编码已存在");
        }
        return DeviceTypeView.from(types.save(new DeviceTypeEntity(normalized, name.strip())), 0);
    }

    /** Idempotent system/demo seeding for equipment families, separate from telemetry modalities. */
    public DeviceTypeEntity getOrCreate(String code, String name) {
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        if (!TYPE_CODE.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备类型编码格式无效");
        }
        return types.findByTypeCode(normalized)
                .orElseGet(() -> types.save(new DeviceTypeEntity(normalized, name.strip())));
    }

    public DeviceTypeView rename(Long id, String name) {
        DeviceTypeEntity type = require(id);
        type.rename(name.strip());
        return DeviceTypeView.from(type, devices.countByDeviceType_Id(id));
    }

    public void delete(Long id) {
        DeviceTypeEntity type = require(id);
        if (type.isSystem()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "系统设备类型不能删除");
        }
        if (devices.existsByDeviceType_Id(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有设备使用此类型，请先为设备改选类型");
        }
        types.delete(type);
    }

    @Transactional(readOnly = true)
    public DeviceTypeEntity require(Long id) {
        return types.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备类型不存在"));
    }

    @Transactional(readOnly = true)
    public DeviceTypeEntity general() {
        return types.findByTypeCode(DeviceTypeEntity.GENERAL_CODE)
                .orElseThrow(() -> new IllegalStateException("GENERAL device type is missing"));
    }

    public record DeviceTypeView(Long id, String typeCode, String typeName, long deviceCount, boolean system) {
        static DeviceTypeView from(DeviceTypeEntity type, long deviceCount) {
            return new DeviceTypeView(type.getId(), type.getTypeCode(), type.getTypeName(), deviceCount, type.isSystem());
        }
    }
}
