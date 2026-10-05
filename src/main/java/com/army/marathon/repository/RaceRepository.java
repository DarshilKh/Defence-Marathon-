// src/main/java/com/army/marathon/repository/RaceRepository.java
package com.army.marathon.repository;

import com.army.marathon.model.Race;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RaceRepository extends JpaRepository<Race, Long> {
    Optional<Race> findByStatus(Race.RaceStatus status);
}
