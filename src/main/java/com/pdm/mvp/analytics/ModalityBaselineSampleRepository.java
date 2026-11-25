package com.pdm.mvp.analytics;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModalityBaselineSampleRepository extends JpaRepository<ModalityBaselineSampleEntity, Long> {
    boolean existsByPoint_IdAndModalityTypeAndSampledAt(Long pointId, String modalityType, Instant sampledAt);
    boolean existsByPoint_IdAndModalityTypeAndBinIndexAndSampledAt(
            Long pointId, String modalityType, short binIndex, Instant sampledAt);
    List<ModalityBaselineSampleEntity> findByPoint_IdAndModalityTypeAndSampledAtBetweenOrderBySampledAtAsc(
            Long pointId, String modalityType, Instant from, Instant to);
    void deleteByPoint_IdAndModalityType(Long pointId, String modalityType);
    void deleteByPoint_IdAndModalityTypeAndSampledAtBefore(Long pointId, String modalityType, Instant cutoff);
    void deleteByPoint_IdAndModalityTypeAndBinIndexAndSampledAtBefore(
            Long pointId, String modalityType, short binIndex, Instant cutoff);
}
