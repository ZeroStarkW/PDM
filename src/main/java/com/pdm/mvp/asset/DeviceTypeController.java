package com.pdm.mvp.asset;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/device-types")
public class DeviceTypeController {
    private final DeviceTypeService deviceTypes;

    public DeviceTypeController(DeviceTypeService deviceTypes) { this.deviceTypes = deviceTypes; }

    @GetMapping
    public List<DeviceTypeService.DeviceTypeView> list() { return deviceTypes.list(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceTypeService.DeviceTypeView create(@Valid @RequestBody CreateRequest request) {
        return deviceTypes.create(request.typeCode(), request.typeName());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceTypeService.DeviceTypeView rename(@PathVariable Long id, @Valid @RequestBody RenameRequest request) {
        return deviceTypes.rename(id, request.typeName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) { deviceTypes.delete(id); }

    public record CreateRequest(@NotBlank @Size(max = 32) String typeCode, @NotBlank @Size(max = 128) String typeName) {}
    public record RenameRequest(@NotBlank @Size(max = 128) String typeName) {}
}
