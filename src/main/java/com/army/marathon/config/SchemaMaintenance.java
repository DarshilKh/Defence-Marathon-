package com.army.marathon.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Small, idempotent schema fixes that {@code spring.jpa.hibernate.ddl-auto=update} cannot do.
 *
 * <p>{@code update} adds missing tables/columns but never alters an existing column. On MySQL,
 * Hibernate 6 creates {@code @Enumerated(STRING)} columns as native {@code ENUM(...)}, so a value
 * added to {@code Race.CalculationMode} later (e.g. MANUAL_START_DUAL_TIMING) is rejected with
 * "Data truncated" on any database created before that value existed. Converting the column to
 * a plain VARCHAR once fixes this for this and every future enum value.
 *
 * <p>Runs after the JPA schema update, on every start; does nothing once the column is VARCHAR.
 * Never blocks startup: a failure is logged with the manual fix.
 */
@Slf4j
@Component
public class SchemaMaintenance implements ApplicationRunner {

    private static final String RACES_TABLE = "races";
    private static final String CALCULATION_MODE_COLUMN = "calculation_mode";
    private static final String NEWEST_MODE = "MANUAL_START_DUAL_TIMING";

    private final DataSource dataSource;

    public SchemaMaintenance(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null) {
                return;
            }
            String lower = product.toLowerCase();
            if (lower.contains("mysql")) {
                widenMysqlEnumColumn(connection);
            } else if (lower.contains("sqlite")) {
                warnIfSqliteCheckConstraintIsStale(connection);
            }
        } catch (Exception e) {
            log.warn("⚠️ Schema maintenance skipped: {}", e.getMessage());
        }
    }

    private void widenMysqlEnumColumn(Connection connection) throws Exception {
        String sql = "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        String dataType = null;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, RACES_TABLE);
            ps.setString(2, CALCULATION_MODE_COLUMN);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    dataType = rs.getString(1);
                }
            }
        }
        if (!"enum".equalsIgnoreCase(dataType)) {
            return; // already VARCHAR (or the table doesn't exist yet)
        }

        log.info("🔧 Converting {}.{} from ENUM to VARCHAR(30) so new calculation modes can be saved",
            RACES_TABLE, CALCULATION_MODE_COLUMN);
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE " + RACES_TABLE + " MODIFY COLUMN " + CALCULATION_MODE_COLUMN + " VARCHAR(30) NULL");
        } catch (Exception e) {
            log.error("❌ Could not convert {}.{} to VARCHAR - run manually: "
                + "ALTER TABLE {} MODIFY COLUMN {} VARCHAR(30) NULL ({})",
                RACES_TABLE, CALCULATION_MODE_COLUMN, RACES_TABLE, CALCULATION_MODE_COLUMN, e.getMessage());
        }
    }

    /** SQLite can't ALTER a CHECK constraint; say so plainly rather than failing later on save. */
    private void warnIfSqliteCheckConstraintIsStale(Connection connection) throws Exception {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '" + RACES_TABLE + "'")) {
            if (rs.next()) {
                String ddl = rs.getString(1);
                if (ddl != null && ddl.toLowerCase().contains("check (" + CALCULATION_MODE_COLUMN + " in")
                        && !ddl.contains(NEWEST_MODE)) {
                    log.warn("⚠️ This SQLite database's races.calculation_mode CHECK constraint predates {} "
                        + "(and possibly other modes). Saving a race with that mode will fail - recreate the "
                        + "database file (delete data/airforce_marathon.db) or use MySQL.", NEWEST_MODE);
                }
            }
        }
    }
}
