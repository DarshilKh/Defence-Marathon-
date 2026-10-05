package com.army.marathon.ui;

import com.army.marathon.service.AuthenticationService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import net.rgielen.fxweaver.core.FxmlView;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@FxmlView("LoginView.fxml")
public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;
    @FXML private Button loginButton;
    
    private final AuthenticationService authService;
    private boolean loginSuccessful = false;

    public LoginController(AuthenticationService authService) {
        this.authService = authService;
    }

    @FXML
    public void initialize() {
        log.info("🔐 Login Controller initialized");
        
        // ✅ Enter key support - Username field se Password field
        usernameField.setOnKeyPressed(this::handleUsernameKeyPress);
        
        // ✅ Enter key support - Password field se Login trigger
        passwordField.setOnKeyPressed(this::handlePasswordKeyPress);
        
        // Focus on username field
        Platform.runLater(() -> usernameField.requestFocus());
    }

    /**
     * ✅ Username field में Enter दबाने पर password field पर जाएं
     */
    private void handleUsernameKeyPress(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            passwordField.requestFocus();
        }
    }
    
    /**
     * ✅ Password field में Enter दबाने पर login trigger करें
     */
    private void handlePasswordKeyPress(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            handleLogin();
        }
    }

    @FXML
    private void handleLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        
        // Clear previous error
        hideError();
        
        // Validate input
        if (username.isEmpty()) {
            showError("❌ Username cannot be empty");
            usernameField.requestFocus();
            return;
        }
        
        if (password.isEmpty()) {
            showError("❌ Password cannot be empty");
            passwordField.requestFocus();
            return;
        }
        
        // Authenticate
        log.info("🔑 Attempting login for user: {}", username);
        
        if (authService.authenticate(username, password)) {
            log.info("✅ Login successful for user: {}", username);
            loginSuccessful = true;
            
            // ✅ IMPORTANT: Close login window WITHOUT shutting down app
            Stage stage = (Stage) usernameField.getScene().getWindow();
            stage.close();
            
        } else {
            log.warn("❌ Login failed for user: {}", username);
            showError("❌ Invalid username or password!");
            passwordField.clear();
            passwordField.requestFocus();
        }
    }

    @FXML
    public void handleExit() {
        log.info("🚪 Exit button clicked");
        
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Exit Application");
        confirm.setHeaderText("Exit Application?");
        confirm.setContentText("Are you sure you want to exit?");
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                log.info("✅ User confirmed exit from login screen");
                Platform.exit();
                System.exit(0);
            }
        });
    }
    
    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }
    
    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
    
    /**
     * ✅ Check if login was successful
     */
    public boolean isLoginSuccessful() {
        return loginSuccessful;
    }
}
