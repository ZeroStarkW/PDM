package com.pdm.mvp.analytics;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BaselineFeatureStateRepository extends JpaRepository<BaselineFeatureStateEntity, Long> {
    List<BaselineFeatureStateEntity> findByPoint_IdAndModalityType(Long pointId, String modalityType);
    Optional<BaselineFeatureStateEntity> findByPoint_IdAndModalityTypeAndFeatureCode(
            Long pointId, String modalityType, String featureCode);
    void deleteByPoint_Id(Long pointId);
    void deleteByPoint_IdAndModalityType(Long pointId, String modalityType);
}
