package com.army.marathon.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;

/**
 * ✅ UNIVERSAL DATABASE CONFIGURATION
 * - Works in Eclipse IDE
 * - Works as JAR on any system
 * - Auto-creates folders
 * - Auto-fallback to SQLite if MySQL fails
 *
 * <p>This bean used to hand back a raw {@code DriverManagerDataSource}, which opens a brand-new
 * TCP socket to MySQL on every single JDBC call with no pooling at all. Under any sustained load
 * (a live race scanning tags) that exhausts the OS's ephemeral ports and every DB call across the
 * whole app starts failing at once with {@code BindException: Address already in use: connect} -
 * confirmed from a real stack trace, not a hypothetical. There was no comment or history
 * explaining why a raw DriverManagerDataSource was used instead of a pooled one; it predates
 * every other change in this file (present verbatim since the first commit) and looks like an
 * unexamined default rather than a deliberate choice - none of this class's actual goals (mode
 * toggle, MySQL-to-SQLite fallback, folder creation) need it. HikariCP is already on the classpath
 * via spring-boot-starter-data-jpa, so this now builds a proper pooled HikariDataSource instead -
 * same behavior, same fallback, just pooled.
 */
@Slf4j
@Configuration
public class DatabaseConfig {

    @Value("${database.mode:offline}")
    private String databaseMode;

    @Value("${database.online.url:jdbc:mysql://localhost:3306/airforce_marathon}")
    private String onlineUrl;

    @Value("${database.online.username:root}")
    private String onlineUsername;

    @Value("${database.online.password:root}")
    private String onlinePassword;

    @Value("${database.offline.url:jdbc:sqlite:${app.home}/data/airforce_marathon.db}")
    private String offlineUrl;

    // These already existed in application.properties before this fix, but had no effect at all:
    // a manually-built DriverManagerDataSource ignores Spring Boot's Hikari auto-configuration
    // entirely, so they were dead configuration. Read explicitly here since this DataSource bean
    // is still hand-built (needed for the online/offline mode toggle and MySQL-to-SQLite
    // fallback), not something spring.datasource.hikari.* auto-binds onto by itself.
    @Value("${spring.datasource.hikari.maximum-pool-size:10}")
    private int hikariMaxPoolSize;

    @Value("${spring.datasource.hikari.minimum-idle:5}")
    private int hikariMinIdle;

    @Value("${spring.datasource.hikari.connection-timeout:30000}")
    private long hikariConnectionTimeoutMs;

    @Value("${spring.datasource.hikari.idle-timeout:600000}")
    private long hikariIdleTimeoutMs;

    @Value("${spring.datasource.hikari.max-lifetime:1800000}")
    private long hikariMaxLifetimeMs;

    @Primary
    @Bean
    public DataSource dataSource() {
        // ✅ ENSURE DATA FOLDER EXISTS (Eclipse + JAR)
        ensureDataFolderExists();

        if ("online".equalsIgnoreCase(databaseMode)) {
            log.info("========================================");
            log.info("🌐 MySQL Database Mode");
            log.info("========================================");
            log.info("URL      : {}", onlineUrl);
            log.info("Username : {}", onlineUsername);
            log.info("========================================");

            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setPoolName("marathon-mysql-pool");
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setJdbcUrl(onlineUrl);
            dataSource.setUsername(onlineUsername);
            dataSource.setPassword(onlinePassword);
            dataSource.setMaximumPoolSize(hikariMaxPoolSize);
            dataSource.setMinimumIdle(hikariMinIdle);
            dataSource.setConnectionTimeout(hikariConnectionTimeoutMs);
            dataSource.setIdleTimeout(hikariIdleTimeoutMs);
            dataSource.setMaxLifetime(hikariMaxLifetimeMs);

            // ✅ TEST CONNECTION WITH AUTO-FALLBACK
            try (Connection conn = dataSource.getConnection()) {
                log.info("✅ MySQL connection successful!");
                log.info("📊 Database: airforce_marathon");
                log.info("🏊 Pool     : max={}, min-idle={}, connection-timeout={}ms",
                        hikariMaxPoolSize, hikariMinIdle, hikariConnectionTimeoutMs);
                log.info("========================================");
                return dataSource;

            } catch (Exception e) {
                log.error("========================================");
                log.error("❌ MySQL Connection FAILED!");
                log.error("========================================");
                log.error("Error: {}", e.getMessage());
                log.error("");
                log.error("Please check:");
                log.error("  1. MySQL server is running");
                log.error("  2. Database 'airforce_marathon' exists");
                log.error("  3. Username/password is correct");
                log.error("========================================");
                log.warn("⚠️  AUTO-FALLBACK TO SQLITE");
                log.info("========================================");

                // The pool we just tried to open still holds resources even though it failed its
                // first connection - close it before falling back, or it leaks.
                dataSource.close();

                // ✅ AUTO-FALLBACK TO SQLITE
                return configureSQLiteDataSource();
            }
        } else {
            log.info("========================================");
            log.info("💾 SQLite Database Mode (Offline)");
            log.info("========================================");
            return configureSQLiteDataSource();
        }
    }

    /**
     * ✅ CONFIGURE SQLITE DATASOURCE
     */
    private DataSource configureSQLiteDataSource() {
        ensureDataFolderExists(); // Double-check folder exists

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("marathon-sqlite-pool");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setJdbcUrl(offlineUrl);
        // SQLite is a single-file embedded database - the JDBC driver serializes writes itself,
        // so a multi-connection pool against it just produces "database is locked" errors under
        // any concurrent write instead of real parallelism. One pooled connection is still a real
        // improvement over DriverManagerDataSource's per-call raw file-handle churn, without
        // inviting that failure mode.
        dataSource.setMaximumPoolSize(1);
        dataSource.setMinimumIdle(1);

        log.info("Database : SQLite");
        log.info("File     : {}", offlineUrl);
        log.info("========================================");

        return dataSource;
    }

    /**
     * ✅ ENSURE DATA FOLDER EXISTS (Eclipse + JAR)
     */
    private void ensureDataFolderExists() {
        // Under the app's own folder, not the working directory (see AppPaths)
        File dataDir = AppPaths.dir("data");
        if (!dataDir.exists()) {
            if (dataDir.mkdirs()) {
                log.info("✅ Created 'data' folder");
            } else {
                log.warn("⚠️  Could not create 'data' folder");
            }
        }

        // Also create other folders
        AppPaths.dir("logs").mkdirs();
        AppPaths.dir("exports").mkdirs();
        AppPaths.dir("backups").mkdirs();
    }

    /**
     * ✅ CHECK IF ONLINE MODE
     */
    public boolean isOnlineMode() {
        return "online".equalsIgnoreCase(databaseMode);
    }
}
