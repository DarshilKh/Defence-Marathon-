package com.army.marathon.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "race_configuration")
public class RaceConfiguration {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String raceName;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "is_active")
    private boolean active = true;

    @Column(length = 500)
    private String description;

    // ✅ Single source of truth for the duplicate-read time window (used to
    // replace the two separate hardcoded filters in ClouReaderClient/MultiReaderService)
    // columnDefinition gives existing rows a value when this column is added to a live table
    @Column(name = "duplicate_read_window_minutes", nullable = false, columnDefinition = "INT DEFAULT 2")
    private int duplicateReadWindowMinutes = 2;

    // ✅ One global reader-vendor setting for the whole race (not per-checkpoint).
    // Default MUST stay CLOU so existing deployments are unaffected until this is
    // explicitly switched on the Configuration page.
    // columnDefinition gives existing rows a value when this column is added to a live table
    @Enumerated(EnumType.STRING)
    @Column(name = "reader_type", length = 20, nullable = false, columnDefinition = "VARCHAR(20) DEFAULT 'CLOU'")
    private ReaderType readerType = ReaderType.CLOU;

    public enum ReaderType {
        // The one TCP port each vendor's readers listen on. Not operator-configurable.
        CLOU(6000),
        ZEBRA(5084);

        private final int fixedPort;

        ReaderType(int fixedPort) {
            this.fixedPort = fixedPort;
        }

        public int getFixedPort() {
            return fixedPort;
        }
    }
}
