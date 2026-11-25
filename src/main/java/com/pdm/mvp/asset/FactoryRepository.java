package com.pdm.mvp.asset;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FactoryRepository extends JpaRepository<FactoryEntity, Long> {
    Optional<FactoryEntity> findByFactoryCode(String factoryCode);
}
