package com.pdm.mvp.security;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsername(String username);
    boolean existsByUsername(String username);
    @Query(value = "SELECT EXISTS (SELECT 1 FROM pdm_user_role WHERE role = 'ADMIN')", nativeQuery = true)
    boolean existsAdmin();
}
