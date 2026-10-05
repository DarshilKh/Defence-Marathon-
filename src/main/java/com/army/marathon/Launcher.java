package com.army.marathon;

import com.army.marathon.model.Checkpoint;
import com.army.marathon.model.Race;
import com.army.marathon.repository.CheckpointRepository;
import com.army.marathon.repository.RaceRepository;
import com.army.marathon.service.DeviceModeService;
import com.army.marathon.service.MultiReaderService;
import com.army.marathon.service.UhfReaderService;
import com.army.marathon.ui.LoginController;
import com.army.marathon.ui.MainViewController;
import com.army.marathon.ui.SetupWizardController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import net.rgielen.fxweaver.core.FxControllerAndView;
import net.rgielen.fxweaver.core.FxWeaver;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Optional;

public class Launcher {

    public static void main(String[] args) {
       MarathonApplication.main(args);
    }
}
