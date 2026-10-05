package com.army.marathon.repository;

import com.army.marathon.model.Checkpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface CheckpointRepository extends JpaRepository<Checkpoint, Long> {
    
    // Find all enabled checkpoints ordered by display order
    List<Checkpoint> findByEnabledTrueOrderByDisplayOrderAsc();
    
    // Find checkpoint by location name
    Optional<Checkpoint> findByLocationName(String locationName);
    
    List<Checkpoint> findByRaceIdAndEnabledTrueOrderByDisplayOrderAsc(Long raceId);
    
    // ✅✅✅ CRITICAL FIX: Add @Modifying and @Query for delete
    @Modifying
    @Transactional
    @Query("DELETE FROM Checkpoint c WHERE c.raceId = :raceId")
    void deleteByRaceId(Long raceId);
    
    // ✅ NEW: Find checkpoint by name and race ID (for ResultsController)
    @Query("SELECT c FROM Checkpoint c WHERE c.locationName = :name AND c.raceId = :raceId")
    Optional<Checkpoint> findByNameAndRaceId(String name, Long raceId);
    
    // ✅ NEW: Find checkpoints by race ID ordered by display order
    List<Checkpoint> findByRaceIdOrderByDisplayOrderAsc(Long raceId);
    
    // Find start line checkpoint
    Optional<Checkpoint> findByStartLineTrue();
    
    // Find finish line checkpoint
    Optional<Checkpoint> findByFinishLineTrue();
    
    // Find all checkpoints ordered by display order
    List<Checkpoint> findAllByOrderByDisplayOrderAsc();
    
    // Find checkpoint by location number
    Optional<Checkpoint> findByLocationNumber(Integer locationNumber);
    
    // Count enabled checkpoints
    @Query("SELECT COUNT(c) FROM Checkpoint c WHERE c.enabled = true")
    long countEnabledCheckpoints();
    
    // ✅ NEW: Count checkpoints by race ID
    long countByRaceId(Long raceId);
    
    // Find checkpoints with IP/Port configured
    @Query("SELECT c FROM Checkpoint c WHERE c.readerAIp IS NOT NULL OR c.readerBIp IS NOT NULL")
    List<Checkpoint> findCheckpointsWithIpConfig();
    
    // ✅ NEW: Check if reader IP/Port combination exists
    boolean existsByReaderIpAndReaderPort(String readerIp, Integer readerPort);
}
