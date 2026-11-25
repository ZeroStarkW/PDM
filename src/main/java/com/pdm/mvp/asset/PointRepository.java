package com.pdm.mvp.asset;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointRepository extends JpaRepository<PointEntity, Long> {
    @Query("select p from PointEntity p join fetch p.device d join fetch d.factory join fetch d.deviceType")
    List<PointEntity> findAllWithDeviceContext();

    /** 按车间一次取回全部测点（含设备与轴承型号）：消除资产管理页按设备循环查询的 N+1 请求风暴。 */
    @Query("select p from PointEntity p join fetch p.device d left join fetch p.bearingModel where d.factory.factoryCode = :factoryCode order by d.id asc, p.pointCode asc")
    List<PointEntity> findByFactoryCodeWithContext(@Param("factoryCode") String factoryCode);

    List<PointEntity> findByDevice_IdOrderByPointCode(Long deviceId);
    boolean existsByDevice_IdAndPointCode(Long deviceId, String pointCode);
    /** 大小写不敏感查重：编码入库统一大写，防大小写变体绕过唯一键后 409。 */
    boolean existsByDevice_IdAndPointCodeIgnoreCase(Long deviceId, String pointCode);
    Optional<PointEntity> findByDevice_Factory_FactoryCodeAndDevice_DeviceCodeAndPointCode(
            String factoryCode, String deviceCode, String pointCode);
    /** 协议 2.0.0 定位：设备编码 + 测点编码全局查找（跨工厂重复编码返回多条，由调用方拒绝）。 */
    List<PointEntity> findByDevice_DeviceCodeAndPointCodeOrderByIdAsc(String deviceCode, String pointCode);
    /** v2 通道定位：测点编码全局查找（跨工厂重复编码返回多条，由调用方拒绝）。 */
    List<PointEntity> findByPointCodeOrderByIdAsc(String pointCode);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PointEntity p join fetch p.device d join fetch d.factory f "
            + "where f.factoryCode = :factoryCode and d.deviceCode = :deviceCode and p.pointCode = :pointCode")
    Optional<PointEntity> findForTelemetryUpdate(@Param("factoryCode") String factoryCode,
            @Param("deviceCode") String deviceCode, @Param("pointCode") String pointCode);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PointEntity p where p.id = :id")
    Optional<PointEntity> findByIdForUpdate(@Param("id") Long id);
    /** 连同设备与轴承型号一并取回：供无事务上下文的调用方（召唤指令、SSE 推流线程）安全读取 LAZY 关联。 */
    @Query("select p from PointEntity p join fetch p.device left join fetch p.bearingModel where p.id = :id")
    Optional<PointEntity> findByIdWithDeviceAndBearing(@Param("id") Long id);
}
