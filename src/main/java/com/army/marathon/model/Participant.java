package com.army.marathon.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.Period;

@Data
@Entity
@Table(name = "participants")
public class Participant {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // ✅ REMOVED nullable = false - Allow NULL for flexible imports
    @Column(unique = true)
    private String rfidTag;
    
    @Column  // ✅ REMOVED nullable = false
    private String name;
    
    @Column(name = "service_number", unique = true)  // ✅ REMOVED nullable = false
    private String serviceNumber;
    
    @Column(name = "`rank`")
    private String rank;
    
    private String trade;
    
    private String branch;
    
    private String unit;
    
    private String section;
    
    // "Tag No" in the UI: the printed race number. Mandatory and unique (the race-day
    // roster keys on it). NOT the chip ID - that is rfidTag.
    @Column(name = "serial_no", nullable = false, unique = true)
    private String serialNo;
    
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;
    
    @Transient
    private Integer age;
    
    @Transient
    private String ageGroup;
    
    private String remarks;
    
    private String category;
    
    @Column(name = "created_at")
    private LocalDate createdAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDate.now();
        }
    }
    
    public Integer getAge() {
        if (dateOfBirth != null) {
            return Period.between(dateOfBirth, LocalDate.now()).getYears();
        }
        return null;
    }
    
    public String getAgeGroup() {
        Integer currentAge = getAge();
        if (currentAge == null) return "Unknown";
        
        if (currentAge < 20) return "Under 20";
        else if (currentAge < 30) return "20-29";
        else if (currentAge < 40) return "30-39";
        else if (currentAge < 50) return "40-49";
        else if (currentAge < 60) return "50-59";
        else return "60+";
    }
    
    public String getArmyNo() {
        return serviceNumber;
    }
    
    public void setArmyNo(String armyNo) {
        this.serviceNumber = armyNo;
    }
}
