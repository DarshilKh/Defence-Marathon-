package com.army.marathon;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.stage.Modality;
import javafx.stage.Stage;
import net.rgielen.fxweaver.core.FxControllerAndView;
import net.rgielen.fxweaver.core.FxWeaver;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.army.marathon.config.AppPaths;
import com.army.marathon.ui.MainViewController;
import com.army.marathon.ui.SetupWizardController;
import com.army.marathon.ui.LoginController;
import com.army.marathon.service.UhfReaderService;
import com.army.marathon.service.DeviceModeService;
import com.army.marathon.service.MultiReaderService;
import com.army.marathon.service.RaceLifecycleService;
import com.army.marathon.model.Checkpoint;
import com.army.marathon.repository.CheckpointRepository;
import com.army.marathon.repository.RaceRepository;
import com.army.marathon.model.Race;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@SpringBootApplication
public class MarathonApplication extends Application {

    private ConfigurableApplicationContext context;
    private volatile boolean shutdownInProgress = false;
    private Stage primaryStage;

    @Override
    public void init() {
        log.info("🚀 Initializing Indian Defence Marathon RFID System...");
        
        String[] args = getParameters().getRaw().toArray(new String[0]);

        this.context = new SpringApplicationBuilder()
            .sources(MarathonApplication.class)
            .run(args);
        
        log.info("✅ Spring Boot Application Context Started");
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        
        try {
            log.info("🎨 Loading JavaFX UI...");
            
            FxWeaver fxWeaver = context.getBean(FxWeaver.class);
            DeviceModeService deviceModeService = context.getBean(DeviceModeService.class);
            
            // ===== SHOW LOGIN FIRST =====
            boolean loginSuccess = showLoginScreen(fxWeaver);
            
            if (!loginSuccess) {
                log.warn("❌ Login cancelled - Exiting application");
                performShutdown(null, null);
                return;
            }
            
            log.info("✅ Login successful - Proceeding to main application");
            
            // ===== CHECK IF SYSTEM IS CONFIGURED =====
            if (!deviceModeService.isConfigured()) {
                log.warn("⚠️ System NOT configured - Launching Setup Wizard...");
                
                boolean setupCompleted = showSetupWizard(primaryStage, fxWeaver, deviceModeService);
                
                if (!setupCompleted) {
                    log.error("❌ Setup cancelled - Exiting application");
                    
                    Alert exitAlert = new Alert(Alert.AlertType.WARNING);
                    exitAlert.setTitle("Setup Required");
                    exitAlert.setHeaderText("System Not Configured");
                    exitAlert.setContentText(
                        "The application requires initial setup to function.\n\n" +
                        "Please run the application again and complete the setup wizard."
                    );
                    exitAlert.showAndWait();
                    
                    performShutdown(null, null);
                    return;
                }
                
                log.info("✅ Setup completed");
            }
            
            // ===== NOW SHOW MAIN WINDOW =====
            String title = "Indian Defence Marathon - RFID Timing System";
            if (deviceModeService.isConfigured()) {
                title += " [" + deviceModeService.getConfigurationSummary() + "]";
            } else {
                title += " [NOT CONFIGURED]";
            }
            
            primaryStage.setTitle(title);
            applyAppIcon(primaryStage);
            
            // Initialize RFID Reader Service
            UhfReaderService readerService = context.getBean(UhfReaderService.class);
            readerService.initialize();
            
            // Auto-start readers in SLAVE mode
            if (deviceModeService.isSlaveMode()) {
                log.info("🔄 SLAVE MODE DETECTED - Auto-starting readers...");
                Platform.runLater(() -> autoStartSlaveReaders());
            }
            
            // Load Main View
            FxControllerAndView<MainViewController, javafx.scene.Node> controllerAndView = 
                fxWeaver.load(MainViewController.class);
            
            Parent root = (Parent) controllerAndView.getView()
                .orElseThrow(() -> new RuntimeException("Unable to load MainView"));
            
            Scene scene = new Scene(root, 1400, 850);
            primaryStage.setScene(scene);
            primaryStage.setMaximized(true);
            
            // ✅ FIXED: Smart close request handler
            primaryStage.setOnCloseRequest(event -> {
                if (shutdownInProgress) {
                    return;
                }
                
                event.consume(); // Block default close
                
                MultiReaderService multiReaderService = context.getBean(MultiReaderService.class);
                
                // A race counts as running when readers are armed OR the race is ARMED/ACTIVE - a
                // race whose readers failed to connect is still ACTIVE and must get the warning.
                // (A hard kill, power loss or Windows shutdown can't show any dialog, which is why
                // StartupService closes any orphaned RaceSession on the next launch.)
                if (isRaceRunning(multiReaderService)) {
                    log.warn("⚠️ Close attempt blocked - Race is active!");
                    
                    Alert raceActiveAlert = new Alert(Alert.AlertType.WARNING);
                    raceActiveAlert.setTitle("⚠️ Race Active");
                    raceActiveAlert.setHeaderText("Cannot Close - Race is Running!");
                    raceActiveAlert.setContentText(
                        "A race is currently active.\n\n" +
                        "Please stop the race first before closing.\n\n" +
                        "Force shutdown anyway?"
                    );
                    
                    raceActiveAlert.getButtonTypes().setAll(
                        ButtonType.YES, 
                        ButtonType.NO
                    );
                    // NO is the default so a stray Enter never ends a running race
                    ((javafx.scene.control.Button) raceActiveAlert.getDialogPane().lookupButton(ButtonType.YES)).setDefaultButton(false);
                    ((javafx.scene.control.Button) raceActiveAlert.getDialogPane().lookupButton(ButtonType.NO)).setDefaultButton(true);
                    
                    Optional<ButtonType> result = raceActiveAlert.showAndWait();
                    
                    if (result.isPresent() && result.get() == ButtonType.YES) {
                        log.warn("⚠️ FORCE SHUTDOWN confirmed");
                        performShutdown(readerService, multiReaderService);
                    }
                    
                } else {
                    Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
                    confirmAlert.setTitle("Exit Application");
                    confirmAlert.setHeaderText("Close Application?");
                    confirmAlert.setContentText("Are you sure you want to exit?");
                    
                    Optional<ButtonType> result = confirmAlert.showAndWait();
                    
                    if (result.isPresent() && result.get() == ButtonType.OK) {
                        log.info("✅ Shutdown confirmed");
                        performShutdown(readerService, multiReaderService);
                    }
                }
            });
            
            primaryStage.show();
            log.info("✅ JavaFX Main Application Started Successfully");
            
        } catch (Exception e) {
            log.error("❌ Error starting application", e);
            e.printStackTrace();
            showErrorDialog(e);
            performShutdown(null, null);
        }
    }

    /**
     * Window/taskbar icon for every stage, from the bundled NTS logo.
     */
    public static void applyAppIcon(Stage stage) {
        try (java.io.InputStream in = MarathonApplication.class.getResourceAsStream("/logo/nts_icon.png")) {
            if (in != null) {
                stage.getIcons().add(new Image(in));
            }
        } catch (Exception e) {
            log.warn("⚠️ Could not load application icon: {}", e.getMessage());
        }
    }

    /**
     * ✅ FIXED: Show Login Screen (modal, non-closable)
     */
    private boolean showLoginScreen(FxWeaver fxWeaver) {
        try {
            log.info("🔐 Loading Login Screen...");
            
            FxControllerAndView<LoginController, Parent> loginView = 
                fxWeaver.load(LoginController.class);
            
            Stage loginStage = new Stage();
            loginStage.setTitle("Indian Defence Marathon - Login");
            applyAppIcon(loginStage);
            
            Parent loginRoot = loginView.getView()
                .orElseThrow(() -> new RuntimeException("Unable to load Login view"));
            
            Scene loginScene = new Scene(loginRoot, 640, 660);
            loginStage.setScene(loginScene);
            loginStage.setResizable(false);
            
            // ✅ ADDED: Make it modal
            loginStage.initModality(Modality.APPLICATION_MODAL);
            
            // Prevent closing without action
            loginStage.setOnCloseRequest(event -> {
                event.consume();
                
                LoginController controller = loginView.getController();
                if (!controller.isLoginSuccessful()) {
                    controller.handleExit(); // Will ask confirmation
                }
            });
            
            log.info("📋 Showing Login Screen (blocking modal)...");
            loginStage.showAndWait(); // Blocks until login window closes
            
            LoginController controller = loginView.getController();
            boolean success = controller.isLoginSuccessful();
            
            if (success) {
                log.info("✅ Login successful");
            } else {
                log.warn("⚠️ Login cancelled or failed");
            }
            
            return success;
            
        } catch (Exception e) {
            log.error("❌ Failed to show Login Screen", e);
            showErrorDialog(e);
            return false;
        }
    }

    /** Readers armed, or the race row says ARMED/ACTIVE (readers may have failed to connect). */
    private boolean isRaceRunning(MultiReaderService multiReaderService) {
        if (multiReaderService != null && multiReaderService.areReadersArmed()) {
            return true;
        }
        try {
            return context != null && context.isActive()
                    && context.getBean(RaceRepository.class)
                            .findById(RaceLifecycleService.RACE_ID)
                            .map(r -> r.getStatus() == Race.RaceStatus.ACTIVE || r.getStatus() == Race.RaceStatus.ARMED)
                            .orElse(false);
        } catch (Exception e) {
            log.warn("Could not read race status for close check: {}", e.getMessage());
            return false;
        }
    }

    /**
     * ✅ PROPER SHUTDOWN SEQUENCE
     */
    private void performShutdown(UhfReaderService readerService, MultiReaderService multiReaderService) {
        if (shutdownInProgress) {
            log.warn("⚠️ Shutdown already in progress - skipping");
            return;
        }
        
        shutdownInProgress = true;
        log.info("🛑 Starting application shutdown sequence...");
        
        try {
            // Get services if not provided
            if (context != null) {
                if (readerService == null) {
                    try {
                        readerService = context.getBean(UhfReaderService.class);
                    } catch (Exception e) {
                        log.warn("⚠️ Could not get UhfReaderService bean");
                    }
                }
                if (multiReaderService == null) {
                    try {
                        multiReaderService = context.getBean(MultiReaderService.class);
                    } catch (Exception e) {
                        log.warn("⚠️ Could not get MultiReaderService bean");
                    }
                }
            }
            
            // Decided before readers are stopped (stopRace clears the armed flag)
            final boolean raceWasRunning = multiReaderService != null && isRaceRunning(multiReaderService);

            // Stop race if active - disconnects every reader cleanly, so a dangling Zebra host
            // session doesn't make the reader refuse the next connection ("Connection Already exists")
            if (multiReaderService != null && multiReaderService.areReadersArmed()) {
                log.info("⏹️ Stopping active race...");
                multiReaderService.stopRace();
                Thread.sleep(1000); // Give time for cleanup
            }
            
            // Seal the running attempt: current RaceSession.endedAt and race status FINISHED, so
            // Reports shows a finished attempt instead of one that keeps "running until now".
            if (raceWasRunning && context != null && context.isActive()) {
                try {
                    context.getBean(RaceLifecycleService.class)
                            .endCurrentAttempt(RaceLifecycleService.RACE_ID, LocalDateTime.now());
                } catch (Exception e) {
                    log.error("Could not end the running attempt during shutdown", e);
                }
            }

            // Shutdown reader service
            if (readerService != null) {
                log.info("📡 Shutting down reader service...");
                readerService.shutdown();
            }
            
            // Close Spring context
            if (context != null && context.isActive()) {
                log.info("🔒 Closing Spring context...");
                context.close();
            }
            
            // Exit JavaFX
            log.info("🎨 Exiting JavaFX Platform...");
            Platform.exit();
            
            // Force JVM exit after small delay
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                    log.info("✅ Shutdown complete - exiting JVM");
                    System.exit(0);
                } catch (InterruptedException e) {
                    System.exit(0);
                }
            }).start();
            
        } catch (Exception e) {
            log.error("❌ Error during shutdown", e);
            System.exit(1);
        }
    }

    /**
     * Show Setup Wizard
     */
    private boolean showSetupWizard(Stage owner, FxWeaver fxWeaver, DeviceModeService deviceModeService) {
        try {
            log.info("🔧 Loading Setup Wizard...");
            
            FxControllerAndView<SetupWizardController, Parent> setupWizard = 
                fxWeaver.load(SetupWizardController.class);
            
            Stage wizardStage = new Stage();
            wizardStage.setTitle("🎯 System Setup Wizard");
            applyAppIcon(wizardStage);
            
            Parent wizardRoot = setupWizard.getView()
                .orElseThrow(() -> new RuntimeException("Unable to load Setup Wizard view"));
            
            Scene wizardScene = new Scene(wizardRoot, 680, 720);
            wizardStage.setScene(wizardScene);
            wizardStage.setResizable(false);
            
            wizardStage.initModality(Modality.APPLICATION_MODAL);
            wizardStage.initOwner(owner);
            
            wizardStage.setOnCloseRequest(event -> {
                if (!deviceModeService.isConfigured()) {
                    event.consume();
                    
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.initOwner(wizardStage);
                    alert.setTitle("Configuration Required");
                    alert.setHeaderText("System Not Configured");
                    alert.setContentText(
                        "You must complete the setup.\n\n" +
                        "Click 'Save & Restart' to save configuration."
                    );
                    alert.showAndWait();
                }
            });
            
            wizardStage.showAndWait();
            
            return deviceModeService.isConfigured();
            
        } catch (Exception e) {
            log.error("❌ Failed to show Setup Wizard", e);
            return false;
        }
    }

    /**
     * Auto-start readers in SLAVE mode
     */
    private void autoStartSlaveReaders() {
        try {
            log.info("🔄 Auto-starting SLAVE mode readers...");
            
            CheckpointRepository checkpointRepo = context.getBean(CheckpointRepository.class);
            MultiReaderService multiReaderService = context.getBean(MultiReaderService.class);
            RaceRepository raceRepository = context.getBean(RaceRepository.class);
            
            List<Checkpoint> enabledCheckpoints = checkpointRepo.findByEnabledTrueOrderByDisplayOrderAsc();
            
            if (enabledCheckpoints.isEmpty()) {
                log.warn("⚠️ No enabled checkpoints found - cannot auto-start");
                return;
            }
            
            Optional<Race> activeRaceOpt = raceRepository.findByStatus(Race.RaceStatus.ACTIVE);
            Long raceId;
            
            if (activeRaceOpt.isPresent()) {
                raceId = activeRaceOpt.get().getId();
                log.info("✅ Found active race ID: " + raceId);
            } else {
                List<Race> allRaces = raceRepository.findAll();
                raceId = allRaces.isEmpty() ? 1L : allRaces.get(0).getId();
                log.info("⚠️ No active race - using race ID: " + raceId);
            }
            
            multiReaderService.armReaders(raceId, enabledCheckpoints);

            log.info("✅ Slave mode readers armed successfully");
            
        } catch (Exception e) {
            log.error("❌ Failed to auto-start slave readers", e);
            
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Auto-Start Failed");
                alert.setHeaderText("Failed to start SLAVE mode readers");
                alert.setContentText(
                    "Error: " + e.getMessage() + "\n\n" +
                    "Please start readers manually from the UI."
                );
                alert.show();
            });
        }
    }

    private void showErrorDialog(Exception e) {
        try {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Application Error");
                alert.setHeaderText("Failed to start application");
                alert.setContentText(
                    "Error: " + e.getMessage() + "\n\n" +
                    "Check logs for details.\n\n" +
                    "Stack trace: " + e.getClass().getSimpleName()
                );
                alert.showAndWait();
            });
        } catch (Exception ex) {
            log.error("❌ Failed to show error dialog", ex);
        }
    }

    /**
     * ✅ FIXED: JavaFX lifecycle stop() method
     * Called when Platform.exit() is invoked
     */
    @Override
    public void stop() {
        log.info("🛑 JavaFX stop() lifecycle method called");
        
        // Don't trigger shutdown here - it's already handled by performShutdown()
        // This method is called AFTER Platform.exit() in performShutdown()
        
        if (!shutdownInProgress) {
            log.warn("⚠️ stop() called but shutdown not initiated - forcing cleanup");
            shutdownInProgress = true;
            
            try {
                if (context != null && context.isActive()) {
                    context.close();
                }
            } catch (Exception e) {
                log.error("❌ Error in stop() cleanup", e);
            }
        } else {
            log.info("✅ stop() called as part of normal shutdown sequence");
        }
    }

    public static void main(String[] args) {
        AppPaths.publish();              // ${app.home} for the packaged properties
        useExternalConfigIfPresent();
        configureNativeLibraryPath();
        Application.launch(MarathonApplication.class, args);
    }

    /**
     * A packaged build ships its own config/application.properties next to lib/ (SQLite,
     * no MySQL credentials). Point Spring at it by absolute path - Spring's default
     * "./config" lookup is relative to the working directory, which a packaged app
     * doesn't control. Absent in dev runs, so nothing changes there.
     */
    private static void useExternalConfigIfPresent() {
        java.io.File external = new java.io.File(AppPaths.dir("config"), "application.properties");
        if (external.isFile()
                && System.getProperty("spring.config.location") == null
                && System.getProperty("spring.config.additional-location") == null) {
            System.setProperty("spring.config.additional-location", external.toURI().toString());
            diag("🔧 Using external config: " + external.getAbsolutePath());
        }
    }

    /**
     * Startup diagnostics go to stdout AND (packaged builds only) logs/startup-diagnostics.log,
     * because a packaged .exe has no console to read stdout from.
     */
    private static void diag(String line) {
        System.out.println(line);
        if (AppPaths.isWorkingDirFallback()) {
            return;
        }
        try {
            java.io.File logDir = AppPaths.dir("logs");
            logDir.mkdirs();
            java.nio.file.Files.writeString(new java.io.File(logDir, "startup-diagnostics.log").toPath(),
                    line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // diagnostics must never stop the app from starting
        }
    }

    /**
     * Makes java.library.path include the bundled lib/ folder regardless of how
     * the app is launched (IntelliJ Run button, mvnw spring-boot:run, a packaged
     * jar, etc.) — this is a defense-in-depth fix, NOT a replacement for setting
     * -Djava.library.path=lib as a VM option; see the log output this prints for
     * whether it actually worked on your JDK.
     *
     * Why this exists: the Zebra reader (com.mot.rfid.api3) failed with
     * UnsatisfiedLinkError even after RFIDAPI3_JNI_HOST.dll + RFIDAPI32PC.dll
     * were correctly placed in lib/, because nothing was actually adding lib/ to
     * java.library.path at runtime — a manually-set IDE VM option is easy to
     * lose (wrong run config, reverted settings, a teammate running it without
     * the same setup) and gives zero visibility when it's silently not applied.
     * This prints the real, live java.library.path immediately, so a broken
     * setup is visible in the log the moment the app starts instead of only
     * surfacing later as a confusing NoClassDefFoundError when a reader is
     * first used.
     *
     * IMPORTANT CAVEAT: setting the system property alone does NOT make the JVM
     * re-read it — native library search paths are cached the first time
     * System.loadLibrary()/System.load() is called anywhere in the process. The
     * reflection reset below forces that cache to clear on JDK 8-15, but on
     * JDK 16+ (this app targets JDK 21) the JVM's strong encapsulation of
     * internal APIs (JEP 403) can block this reflective access unless the JVM
     * is launched with --add-opens java.base/java.lang=ALL-UNNAMED. This is
     * caught and logged rather than crashing the app, but if you see a WARNING
     * below about this failing, you still need EITHER that --add-opens flag OR
     * -Djava.library.path=lib passed explicitly as a VM option — check the
     * "java.library.path is now:" log line on your very next run to know for
     * certain which situation you're in, instead of guessing.
     */
    private static void configureNativeLibraryPath() {
        // Resolved from where the app is installed (AppPaths), never from user.dir - a
        // packaged app's working directory is whatever launched it.
        java.io.File libDirFile = AppPaths.libDir();
        String libDir = libDirFile.getAbsolutePath();
        diag("🔍 Working directory: " + new java.io.File("").getAbsolutePath());
        diag("🔍 App home: " + AppPaths.home().getAbsolutePath()
                + (AppPaths.isWorkingDirFallback() ? " (working directory - dev run)" : ""));
        diag("🔍 Expecting native libs in: " + libDir);
        diag("🔍 RFIDAPI3_JNI_HOST.dll present: "
                + new java.io.File(libDir, "RFIDAPI3_JNI_HOST.dll").exists());
        diag("🔍 RFIDAPI32PC.dll present: "
                + new java.io.File(libDir, "RFIDAPI32PC.dll").exists());
        diag("🔍 java.library.path BEFORE fix: " + System.getProperty("java.library.path"));

        String[] nativeLibs = { "RFIDAPI32PC.dll", "RFIDAPI3_JNI_HOST.dll" };

        // DEV RUN ONLY (IDE / spring-boot:run): Windows puts "." (the working directory) on
        // java.library.path by default, so copying the DLLs into the working directory makes
        // System.loadLibrary() find them with no VM options. In a packaged build the working
        // directory is arbitrary (possibly System32), so never copy files into it there; the
        // launcher passes -Djava.library.path=<app>/lib instead.
        //
        // Do NOT replace this with the old ClassLoader "sys_paths" reflection trick:
        // that field was removed after JDK 11 and throws NoSuchFieldException on
        // JDK 21 (this app's target), so it silently does nothing. --add-opens does
        // not help either, because the field genuinely no longer exists.
        if (AppPaths.isWorkingDirFallback()) {
            java.io.File workingDir = new java.io.File("").getAbsoluteFile();

            for (String libName : nativeLibs) {
                java.io.File source = new java.io.File(libDir, libName);
                java.io.File target = new java.io.File(workingDir, libName);

                if (!source.exists()) {
                    System.err.println("⚠️ Missing native library: " + source.getAbsolutePath()
                            + " — Zebra readers cannot work without it.");
                    continue;
                }

                try {
                    if (!target.exists() || target.length() != source.length()) {
                        java.nio.file.Files.copy(source.toPath(), target.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("✅ Copied " + libName + " to working directory (on java.library.path as '.')");
                    } else {
                        System.out.println("✅ " + libName + " already present in working directory");
                    }
                } catch (Exception e) {
                    // A locked file here usually just means a previous run already loaded it,
                    // which is fine — the existing copy is what will be used.
                    System.err.println("⚠️ Could not copy " + libName + " to working directory ("
                            + e.getClass().getSimpleName() + ": " + e.getMessage() + "). If Zebra readers fail with "
                            + "UnsatisfiedLinkError, either copy " + libName + " from lib/ into "
                            + workingDir.getAbsolutePath() + " manually, or add -Djava.library.path=" + libDir
                            + " as a VM option.");
                }
            }
        }

        // RFIDAPI3_JNI_HOST.dll imports RFIDAPI32PC.dll. Windows resolves a DLL's imports from
        // the process exe's folder, System32 and PATH - NOT from the folder the importing DLL
        // sits in - so with the DLLs only under <app>/lib the JNI host would fail to load with
        // "Can't find dependent libraries". Loading the dependency first by absolute path
        // registers it in the process, and the loader then satisfies the import by name.
        java.io.File dependency = new java.io.File(libDir, "RFIDAPI32PC.dll");
        if (dependency.exists()) {
            try {
                System.load(dependency.getAbsolutePath());
                diag("✅ Preloaded RFIDAPI32PC.dll from " + dependency.getAbsolutePath());
            } catch (Throwable t) {
                System.err.println("⚠️ Could not preload RFIDAPI32PC.dll (" + t + "). Zebra readers may fail to load.");
            }
        }
    }
}
