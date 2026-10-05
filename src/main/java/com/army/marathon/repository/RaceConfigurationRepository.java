package com.army.marathon.repository;

import com.army.marathon.model.RaceConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RaceConfigurationRepository extends JpaRepository<RaceConfiguration, Long> {
    
    RaceConfiguration findTopByOrderByCreatedAtDesc();
    
    RaceConfiguration findByRaceName(String raceName);
}
