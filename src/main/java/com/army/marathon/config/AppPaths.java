package com.army.marathon.config;

import java.io.File;
import java.security.CodeSource;

/**
 * Single answer to "where does this app live on disk?" so lib/, data/, logs/, exports/,
 * backups/ and config/ never depend on the process working directory (a packaged app's
 * working directory is whatever launched it: a shortcut, Explorer, a scheduled task...).
 *
 * Resolution order:
 *  1. -Dapp.home=... if given explicitly
 *  2. the folder holding the jar this class was loaded from (or its parent), if that folder
 *     has a lib/ directory - i.e. a packaged build, or `java -jar target/x.jar` with target/lib
 *  3. the working directory - i.e. `mvnw spring-boot:run` / an IDE, where classes load from
 *     target/classes and the project root (which has lib/) is the working directory
 */
public final class AppPaths {

    private static File home;
    private static boolean workingDirFallback;

    private AppPaths() {}

    public static synchronized File home() {
        if (home == null) {
            home = resolveHome();
        }
        return home;
    }

    public static File libDir() {
        return new File(home(), "lib");
    }

    /** A folder under the app home (data, logs, exports, backups). */
    public static File dir(String name) {
        return new File(home(), name);
    }

    /**
     * True when home fell back to the working directory (dev run). False for a packaged
     * build or an explicit -Dapp.home.
     */
    public static synchronized boolean isWorkingDirFallback() {
        home();
        return workingDirFallback;
    }

    /**
     * Exposes the resolved home as the "app.home" system property so properties files can
     * use ${app.home} (e.g. the SQLite path and log file in the packaged config).
     */
    public static void publish() {
        System.setProperty("app.home", home().getAbsolutePath());
    }

    private static File resolveHome() {
        String explicit = System.getProperty("app.home");
        if (explicit != null && !explicit.isBlank()) {
            return new File(explicit).getAbsoluteFile();
        }

        try {
            CodeSource source = AppPaths.class.getProtectionDomain().getCodeSource();
            if (source != null) {
                File location = new File(source.getLocation().toURI());
                if (location.isFile()) {
                    File dir = location.getParentFile();
                    for (int depth = 0; depth < 2 && dir != null; depth++, dir = dir.getParentFile()) {
                        if (new File(dir, "lib").isDirectory()) {
                            return dir.getAbsoluteFile();
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️ Could not resolve app location from code source: " + e);
        }

        workingDirFallback = true;
        return new File("").getAbsoluteFile();
    }
}
