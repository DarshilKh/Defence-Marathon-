package com.army.marathon.repository;

import com.army.marathon.model.Participant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParticipantRepository extends JpaRepository<Participant, Long> {
    
    // Find by RFID Tag
    Optional<Participant> findByRfidTag(String rfidTag);
    
    // Find by Service Number
    Optional<Participant> findByServiceNumber(String serviceNumber);
    
    // ✅ NEW: Find by Serial No
    Optional<Participant> findBySerialNo(String serialNo);
    
    // Check if Service Number exists
    boolean existsByServiceNumber(String serviceNumber);
    
    // Check if RFID tag exists
    boolean existsByRfidTag(String rfidTag);
    
    // ✅ NEW: Check if Serial No exists
    boolean existsBySerialNo(String serialNo);
    
    // Find all by unit
    List<Participant> findByUnit(String unit);
    
    // Find all by category
    List<Participant> findByCategory(String category);
    
    // Search by name (contains)
    List<Participant> findByNameContainingIgnoreCase(String name);
    
    // ✅ UPDATED: Search by RFID Tag, Serial No, Service Number, Name, or Unit
    @Query("SELECT p FROM Participant p WHERE " +
           "LOWER(p.rfidTag) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(p.serialNo) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(p.serviceNumber) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(p.unit) LIKE LOWER(CONCAT('%', :search, '%'))")
    List<Participant> searchParticipants(String search);
    
    // Count participants with RFID tags
    @Query("SELECT COUNT(p) FROM Participant p WHERE p.rfidTag IS NOT NULL")
    long countWithRfidTag();
    
    // Count participants without RFID tags
    @Query("SELECT COUNT(p) FROM Participant p WHERE p.rfidTag IS NULL")
    long countWithoutRfidTag();
}
