package com.pdm.mvp.analytics;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BaselineSampleRepository extends JpaRepository<BaselineSampleEntity, Long> {
    boolean existsByPoint_IdAndSampledAt(Long pointId, java.time.Instant sampledAt);
    boolean existsByPoint_IdAndBinIndexAndSampledAt(Long pointId, short binIndex, java.time.Instant sampledAt);
    List<BaselineSampleEntity> findByPoint_IdAndSampledAtBetweenOrderBySampledAtAsc(
            Long pointId, java.time.Instant from, java.time.Instant to);
    long deleteByPoint_Id(Long pointId);
    long deleteByPoint_IdAndSampledAtBefore(Long pointId, java.time.Instant cutoff);
    long deleteByPoint_IdAndBinIndexAndSampledAtBefore(Long pointId, short binIndex, java.time.Instant cutoff);
}
