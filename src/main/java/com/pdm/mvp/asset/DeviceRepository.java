package com.pdm.mvp.asset;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;

public interface DeviceRepository extends JpaRepository<DeviceEntity, Long> {
    @Query("select d from DeviceEntity d join fetch d.factory join fetch d.deviceType order by d.factory.factoryCode, d.deviceCode")
    List<DeviceEntity> findAllWithHealthContext();

    @EntityGraph(attributePaths = "deviceType")
    List<DeviceEntity> findByFactory_FactoryCodeOrderByDeviceCode(String factoryCode);
    Optional<DeviceEntity> findByFactory_FactoryCodeAndDeviceCode(String factoryCode, String deviceCode);
    long countByDeviceType_Id(Long id);
    boolean existsByDeviceType_Id(Long id);

    @Query("select d.deviceType.id as typeId, count(d) as deviceCount from DeviceEntity d group by d.deviceType.id")
    List<DeviceTypeCount> countByDeviceType();

    interface DeviceTypeCount {
        Long getTypeId();
        long getDeviceCount();
    }
}
