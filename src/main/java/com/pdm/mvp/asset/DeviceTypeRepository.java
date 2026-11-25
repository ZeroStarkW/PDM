package com.pdm.mvp.asset;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTypeRepository extends JpaRepository<DeviceTypeEntity, Long> {
    List<DeviceTypeEntity> findAllByOrderByTypeCodeAsc();
    Optional<DeviceTypeEntity> findByTypeCode(String code);
    boolean existsByTypeCode(String code);
}
