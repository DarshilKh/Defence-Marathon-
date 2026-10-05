package com.army.marathon.config;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dev-run guard for the packaged-build path fix: when classes load from target/classes (mvnw
 * spring-boot:run, IDE, surefire) there is no jar to anchor on, so the app home must still be
 * the working directory (the project root), where lib/ with the RFID DLLs lives.
 */
class AppPathsTest {

    @Test
    void devRun_resolvesToWorkingDirectory_whereLibHolds_theRfidDlls() {
        assertTrue(AppPaths.isWorkingDirFallback(), "classes come from a directory here, not a jar");
        assertEquals(new File("").getAbsoluteFile(), AppPaths.home());

        File lib = AppPaths.libDir();
        assertTrue(new File(lib, "RFIDAPI3_JNI_HOST.dll").isFile(), lib + " should hold RFIDAPI3_JNI_HOST.dll");
        assertTrue(new File(lib, "RFIDAPI32PC.dll").isFile());
        assertTrue(new File(lib, "UHFReader18.dll").isFile());
    }
}
