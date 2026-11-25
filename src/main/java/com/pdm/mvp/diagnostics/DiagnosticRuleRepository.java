package com.pdm.mvp.diagnostics;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface DiagnosticRuleRepository extends JpaRepository<DiagnosticRuleEntity, Long> {
    List<DiagnosticRuleEntity> findByDeviceType_IdOrderByPriorityDescIdAsc(Long deviceTypeId);
    List<DiagnosticRuleEntity> findByDeviceType_IdAndEnabledTrueOrderByPriorityDescIdAsc(Long deviceTypeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DiagnosticRuleEntity> findByIdAndDeviceType_Id(Long id, Long deviceTypeId);
}
