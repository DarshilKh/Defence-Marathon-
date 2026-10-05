package com.army.marathon.ui;

import javafx.application.Platform;
import javafx.scene.Parent;
import net.rgielen.fxweaver.core.FxControllerAndView;
import net.rgielen.fxweaver.core.FxWeaver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless smoke test: loads every screen touched by the registration-layout /
 * antenna-testing / live-calculate changes through the real FxWeaver+FXML
 * pipeline used at runtime (no window shown). Catches fx:id / onAction
 * mismatches and constructor-wiring mistakes that only surface when a view is
 * actually loaded and its initialize() runs — compiling alone doesn't catch
 * these. Does not exercise real RFID hardware, login, or the setup wizard.
 */
@SpringBootTest
class UiFlowSmokeTest {

    @Autowired
    private FxWeaver fxWeaver;

    @Autowired
    private com.army.marathon.service.CalculationModeSelection modeSelection;

    @Autowired
    private com.army.marathon.service.MultiReaderService multiReaderService;

    private static volatile boolean toolkitStarted = false;

    /**
     * Idempotent across test CLASSES, not just within this one: when the whole suite runs in one
     * Surefire fork, another test class may already have started the JavaFX toolkit (each test
     * class's own toolkitStarted flag only guards itself) - Platform.startup() throws
     * IllegalStateException("Toolkit already initialized") in that case, which just means the
     * toolkit this class needs is already up and Platform.runLater() works immediately.
     */
    private static synchronized void initJavaFxToolkit() throws InterruptedException {
        if (toolkitStarted) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyStarted) {
            toolkitStarted = true;
            return;
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        toolkitStarted = true;
    }

    private <T> Parent assertLoads(Class<T> controllerClass) throws Exception {
        return loadControllerAndView(controllerClass).getView().get();
    }

    private <T> FxControllerAndView<T, Parent> loadControllerAndView(Class<T> controllerClass) throws Exception {
        initJavaFxToolkit();

        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<FxControllerAndView<T, Parent>> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                result.set(fxWeaver.load(controllerClass));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out loading " + controllerClass.getSimpleName());
        if (error.get() != null) {
            throw new AssertionError("Failed to load " + controllerClass.getSimpleName(), error.get());
        }
        assertNotNull(result.get());
        assertTrue(result.get().getView().isPresent(), controllerClass.getSimpleName() + " produced no view");
        return result.get();
    }

    /** Reads an @FXML-injected field (fx:id does not set the node id, so lookup("#...") can't find it). */
    @SuppressWarnings("unchecked")
    private static <F> F fxField(Object controller, String name) throws Exception {
        java.lang.reflect.Field f = controller.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (F) f.get(controller);
    }

    /** Every ImageView in the loaded screen must have decoded its image (a bad @path fails silently). */
    private static void assertImagesDecoded(Parent view, String screen) {
        var imageViews = view.lookupAll(".image-view");
        assertTrue(!imageViews.isEmpty(), screen + " should show the logo");
        for (var node : imageViews) {
            javafx.scene.image.Image image = ((javafx.scene.image.ImageView) node).getImage();
            assertNotNull(image, screen + " has an ImageView with no image");
            assertTrue(!image.isError() && image.getWidth() > 0, screen + " logo failed to load: " + image.getUrl());
        }
    }

    @Test
    void registrationViewLoads() throws Exception {
        assertLoads(RegistrationController.class);
    }

    @Test
    void configurationViewLoads() throws Exception {
        assertLoads(ConfigurationController.class);
    }

    @Test
    void configurationPorts_areReadOnlyAndShowTheFixedPort() throws Exception {
        Object controller = loadControllerAndView(ConfigurationController.class).getController();
        for (int location = 1; location <= 4; location++) {
            for (String reader : new String[]{"A", "B"}) {
                String name = "location" + location + "Reader" + reader + "PortField";
                javafx.scene.control.TextField port = fxField(controller, name);
                assertNotNull(port, name);
                assertTrue(!port.isEditable(), "port must not be editable: " + name);
                assertTrue(port.getText().equals("5084") || port.getText().equals("6000"),
                        "port shows a vendor-fixed value, was: " + port.getText());
            }
        }
    }

    private static void onFx(Runnable action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS), "FX action timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @Test
    void configurationStartFinish_sameMode_oneLocationIsBoth_otherwiseSeparateLocations() throws Exception {
        Object c = loadControllerAndView(ConfigurationController.class).getController();
        javafx.scene.control.CheckBox[] start = new javafx.scene.control.CheckBox[5];
        javafx.scene.control.CheckBox[] finish = new javafx.scene.control.CheckBox[5];
        javafx.scene.control.CheckBox[] enable = new javafx.scene.control.CheckBox[5];
        for (int i = 1; i <= 4; i++) {
            start[i] = fxField(c, "location" + i + "StartCheckBox");
            finish[i] = fxField(c, "location" + i + "FinishCheckBox");
            enable[i] = fxField(c, "location" + i + "EnabledCheckBox");
        }
        try {
            // ---- SAME_START_FINISH: ticking Start on location 2 makes location 2 BOTH start and finish
            modeSelection.set(com.army.marathon.model.Race.CalculationMode.SAME_START_FINISH);
            onFx(() -> {
                ((ConfigurationController) c).refresh();
                start[2].setSelected(false);
                finish[2].setSelected(false);
                for (int i = 1; i <= 4; i++) { start[i].setSelected(false); finish[i].setSelected(false); }
                start[2].setSelected(true);
            });
            onFx(() -> {
                // only ONE location is enabled in this mode, and it is the one carrying Start + Finish
                assertTrue(enable[2].isSelected(), "location 2 is enabled");
                for (int i : new int[]{1, 3, 4}) {
                    assertTrue(!enable[i].isSelected() && enable[i].isDisable(), "location " + i + " switched off and locked");
                }
                // freeing it, then enabling location 4 by hand, makes location 4 the single Start+Finish location
                start[2].setSelected(false);
                for (int i = 1; i <= 4; i++) assertTrue(!enable[i].isDisable(), "location " + i + " unlocked");
                enable[4].setSelected(true);
                assertTrue(start[4].isSelected() && finish[4].isSelected(), "enabling gives Start + Finish");
                assertTrue(!enable[2].isSelected(), "the previous location is off");
                start[4].setSelected(false);
                start[2].setSelected(true);
            });
            onFx(() -> {
                assertTrue(start[2].isSelected() && finish[2].isSelected(), "location 2 holds both");
                for (int i : new int[]{1, 3, 4}) {
                    assertTrue(!start[i].isSelected() && !finish[i].isSelected(), "location " + i + " cleared");
                    assertTrue(start[i].isDisabled() && finish[i].isDisabled(), "location " + i + " locked");
                }
                assertTrue(!start[2].isDisabled() && !finish[2].isDisabled(), "owner stays editable");

                // ticking Finish on the owner is the same single pair; unticking frees everything
                finish[2].setSelected(false);
                assertTrue(!start[2].isSelected() && !finish[2].isSelected(), "pair unticks together");
                for (int i = 1; i <= 4; i++) {
                    assertTrue(!start[i].isDisabled() && !finish[i].isDisabled(), "location " + i + " unlocked");
                }
                // choosing another location moves the pair
                finish[3].setSelected(true);
                assertTrue(start[3].isSelected() && finish[3].isSelected(), "location 3 holds both");
            });

            // ---- any other mode: leaving SAME drops Finish from the both-ticked location, boxes re-enabled
            modeSelection.set(com.army.marathon.model.Race.CalculationMode.TWO_CHECKPOINT);
            onFx(() -> {
                ((ConfigurationController) c).refresh();
                assertTrue(start[3].isSelected() && !finish[3].isSelected(), "Start kept, Finish dropped");
                for (int i = 1; i <= 4; i++) {
                    assertTrue(!start[i].isDisabled() && !finish[i].isDisabled(), "location " + i + " enabled");
                }
                // separate-location rules: Finish on 1 -> fine; Start on 1 then clears Finish on 1 and Start on 3
                finish[1].setSelected(true);
                assertTrue(finish[1].isSelected() && start[3].isSelected());
                start[1].setSelected(true);
                assertTrue(start[1].isSelected() && !finish[1].isSelected() && !start[3].isSelected());
                // Finish on 2 doesn't touch the start
                finish[2].setSelected(true);
                assertTrue(finish[2].isSelected() && start[1].isSelected());
                // a location cannot be both outside SAME_START_FINISH
                start[2].setSelected(true);
                assertTrue(start[2].isSelected() && !finish[2].isSelected());
            });
        } finally {
            modeSelection.set(null);
        }
    }

    @Test
    void raceEntries_uploadChecks_thenManualUncheckSurvivesARefresh() throws Exception {
        RaceEntriesController c = loadControllerAndView(RaceEntriesController.class).getController();
        javafx.collections.ObservableList<RaceEntriesController.RosterRow> rows = fxField(c, "rows");
        org.junit.jupiter.api.Assumptions.assumeTrue(rows.size() >= 2, "needs 2+ registered participants in the DB");

        Long checkedThenUnchecked = rows.get(0).getParticipantId();
        Long stayedChecked = rows.get(1).getParticipantId();

        onFx(() -> {
            // What handleUploadExcel does for each matched participant
            rows.get(0).setSelectedBy(true, com.army.marathon.model.RaceEntry.Source.EXCEL_UPLOAD);
            rows.get(1).setSelectedBy(true, com.army.marathon.model.RaceEntry.Source.EXCEL_UPLOAD);
            assertTrue(rows.get(0).isSelected() && rows.get(1).isSelected(), "upload checks the matches");

            // Operator unticks one box by hand (a CheckBoxTableCell click sets this same property)
            rows.get(0).selectedProperty().set(false);
            assertTrue(!rows.get(0).isSelected());
            org.junit.jupiter.api.Assertions.assertEquals(com.army.marathon.model.RaceEntry.Source.MANUAL, rows.get(0).getSource());

            // Tab switch / re-render: rows are rebuilt from the DB but unsaved edits must be kept
            c.refresh();
        });
        onFx(() -> {
            RaceEntriesController.RosterRow unchecked = rows.stream()
                    .filter(r -> r.getParticipantId().equals(checkedThenUnchecked)).findFirst().orElseThrow();
            RaceEntriesController.RosterRow kept = rows.stream()
                    .filter(r -> r.getParticipantId().equals(stayedChecked)).findFirst().orElseThrow();
            assertTrue(!unchecked.isSelected(), "manual uncheck must not be reset by a refresh");
            assertTrue(kept.isSelected(), "the other upload-checked row stays checked");
            org.junit.jupiter.api.Assertions.assertEquals(com.army.marathon.model.RaceEntry.Source.EXCEL_UPLOAD, kept.getSource());
            // Deliberately not saved: this test must not change the real roster
        });
    }

    @Test
    void liveTracking_doesNotArmReadersOnLoad_calculateStartsDisabled_declaredStartOnlyInManualMode() throws Exception {
        LiveTrackingController c = loadControllerAndView(LiveTrackingController.class).getController();

        // Section 8: opening the page must not connect any reader - only Start Race does
        assertTrue(!multiReaderService.areReadersArmed(), "readers must not be armed just by loading Live Tracking");
        org.junit.jupiter.api.Assertions.assertNull(
                java.util.Arrays.stream(LiveTrackingController.class.getDeclaredFields())
                        .filter(f -> f.getName().equals("armReadersButton")).findFirst().orElse(null),
                "the separate Arm Readers button is gone - Start Race is the only way to connect");

        // Section 8: Calculate Results is disabled until the race has been stopped
        javafx.scene.control.Button calculate = fxField(c, "calculateButton");
        var status = c.currentRace().getStatus();
        org.junit.jupiter.api.Assumptions.assumeTrue(status != com.army.marathon.model.Race.RaceStatus.FINISHED,
                "local race is FINISHED; the default-disabled state can't be observed");
        assertTrue(calculate.isDisable(), "Calculate Results must start disabled (race status " + status + ")");

        // Section 7: the Declared Start control (live clock + Set) exists only in the Manual Start mode
        javafx.scene.layout.HBox declaredBox = fxField(c, "declaredStartBox");
        javafx.scene.control.ComboBox<String> modes = fxField(c, "calculationModeComboBox");
        javafx.scene.control.TextField clock = fxField(c, "manualStartTimeField");
        assertTrue(!clock.isEditable(), "the field is a read-only live clock, not typed text");
        assertTrue(clock.getText().matches("\\d{2}:\\d{2}:\\d{2}"), "clock shows HH:mm:ss, was: " + clock.getText());

        String manualMode = modes.getItems().stream().filter(m -> m.startsWith("MANUAL START")).findFirst().orElse(null);
        org.junit.jupiter.api.Assertions.assertNotNull(manualMode, "Manual Start mode is selectable");
        try {
            for (String mode : modes.getItems()) {
                onFx(() -> modes.setValue(mode));
                onFx(() -> {
                    boolean manual = mode.equals(manualMode);
                    org.junit.jupiter.api.Assertions.assertEquals(manual, declaredBox.isVisible(), "visible only for: " + mode);
                    org.junit.jupiter.api.Assertions.assertEquals(manual, declaredBox.isManaged(), "managed only for: " + mode);
                });
            }
        } finally {
            modeSelection.set(null);
        }
    }

    @Test
    void manualStartMode_setButtonIsGone_startRaceIsNowTheOnlyWayToDeclareAStart() throws Exception {
        // Real hardware may be reachable from this checkpoint's configured reader IP (confirmed:
        // Location 1 is enabled with a real reader_a_ip in this installation's own database) -
        // actually firing Start Race here would risk connecting to and arming real equipment, so
        // this test stays purely structural: the FXML still loads (catches any fx:id mismatch
        // from removing the button) and the removed symbols are genuinely gone, not just unused.
        assertLoads(LiveTrackingController.class);

        org.junit.jupiter.api.Assertions.assertNull(java.util.Arrays.stream(LiveTrackingController.class.getDeclaredFields())
                        .filter(f -> f.getName().equals("setStartTimeButton")).findFirst().orElse(null),
                "the separate Set button field is gone - Start Race captures the Declared Start itself");
        org.junit.jupiter.api.Assertions.assertNull(java.util.Arrays.stream(LiveTrackingController.class.getDeclaredMethods())
                        .filter(m -> m.getName().equals("handleSetStartTime")).findFirst().orElse(null),
                "handleSetStartTime must be fully removed, not just unwired");

        Object c = loadControllerAndView(LiveTrackingController.class).getController();
        javafx.scene.control.TextField clock = fxField(c, "manualStartTimeField");
        assertTrue(!clock.isEditable(), "still a read-only preview/record field, never typed into");
    }

    @Test
    void freshCheckpoint_defaultsToOneReaderOnTheFixedPort() {
        var cp = ConfigurationController.buildDefaultCheckpoint(2, 5084);
        org.junit.jupiter.api.Assertions.assertEquals(1, cp.getActiveReaderCount());
        org.junit.jupiter.api.Assertions.assertNull(cp.getReaderBIp());
        org.junit.jupiter.api.Assertions.assertEquals(5084, cp.getReaderAPort());
        org.junit.jupiter.api.Assertions.assertEquals(5084, cp.getReaderBPort());
        // and the bare entity defaults to Zebra's port too
        var bare = new com.army.marathon.model.Checkpoint();
        org.junit.jupiter.api.Assertions.assertEquals(5084, bare.getReaderAPort());
        org.junit.jupiter.api.Assertions.assertEquals(5084, bare.getReaderBPort());
        org.junit.jupiter.api.Assertions.assertEquals(1, bare.getActiveReaderCount());
    }

    @Test
    void liveTrackingViewLoads() throws Exception {
        assertLoads(LiveTrackingController.class);
    }

    @Test
    void raceEntriesViewLoads() throws Exception {
        assertLoads(RaceEntriesController.class);
    }

    @Test
    void resultsViewLoads() throws Exception {
        assertLoads(ResultsController.class);
    }

    @Test
    void loginViewLoads() throws Exception {
        assertImagesDecoded(assertLoads(LoginController.class), "Login");
    }

    /** ImageView silently shows nothing on a bad path, so check the bundled logos really decode. */
    @Test
    void brandingImagesLoad() throws Exception {
        initJavaFxToolkit();
        for (String path : new String[]{"/logo/nts_logo.png", "/logo/nts_icon.png"}) {
            try (java.io.InputStream in = getClass().getResourceAsStream(path)) {
                assertNotNull(in, path + " missing from classpath");
                javafx.scene.image.Image image = new javafx.scene.image.Image(in);
                assertTrue(!image.isError() && image.getWidth() > 0, path + " failed to decode");
            }
        }
        assertTrue(getClass().getResource("/com/army/marathon/images/ets_logowt.png") == null,
                "old ETS logo should be gone");
    }

    @Autowired
    private com.army.marathon.service.DeviceModeService deviceModeService;

    @Test
    void reconfigureButton_isHiddenOnceConfigured_butSetupStaysReachableBeforeThat() throws Exception {
        Object main = loadControllerAndView(MainViewController.class).getController();
        javafx.scene.control.Button setup = fxField(main, "runSetupButton");
        boolean configured = deviceModeService.isConfigured();
        org.junit.jupiter.api.Assertions.assertEquals(!configured, setup.isVisible(),
                "configured=" + configured + ": the setup/reconfigure button is visible only while NOT configured");
        org.junit.jupiter.api.Assertions.assertEquals(!configured, setup.isManaged(),
                "a hidden button must not leave a gap in the header");
    }

    @Test
    void mainViewLoadsAllTabs() throws Exception {
        // MainViewController.initialize() loads Registration/Configuration/
        // LiveTracking/RaceEntries/Results into its tabs itself — this is the exact path
        // the real app takes right after a successful login.
        assertImagesDecoded(assertLoads(MainViewController.class), "Main window");
    }

    @AfterAll
    static void shutdownToolkit() {
        if (toolkitStarted) {
            Platform.exit();
        }
    }
}
