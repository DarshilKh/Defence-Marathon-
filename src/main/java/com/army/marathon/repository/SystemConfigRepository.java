package com.army.marathon.repository;

import com.army.marathon.model.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SystemConfigRepository extends JpaRepository<SystemConfig, Long> {
    
    Optional<SystemConfig> findByDeviceId(String deviceId);
    
    // Get the first (and should be only) system config
    Optional<SystemConfig> findFirstByOrderByIdAsc();
    
    boolean existsByDeviceId(String deviceId);
}
