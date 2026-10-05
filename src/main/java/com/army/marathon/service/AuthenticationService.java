package com.army.marathon.service;

import com.army.marathon.model.User;
import com.army.marathon.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
public class AuthenticationService {

    private final UserRepository userRepository;
    private String currentUsername;

    public AuthenticationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Create default admin user if no users exist
     */
    @PostConstruct
    @Transactional
    public void initializeDefaultUser() {
        try {
            if (userRepository.count() == 0) {
                log.info("🔧 No users found - creating default admin user");
                
                User admin = User.builder()
                    .username("admin")
                    .password("admin123") // ⚠️ Use hashed passwords in production!
                    .fullName("System Administrator")
                    .role("ADMIN")
                    .active(true)
                    .build();
                
                userRepository.save(admin);
                log.info("✅ Default admin user created (username: admin, password: admin123)");
            }
        } catch (Exception e) {
            log.error("❌ Failed to create default user", e);
        }
    }

    /**
     * Authenticate user
     * 
     * @param username Username
     * @param password Password (plain text - hash in production!)
     * @return true if authenticated successfully
     */
    @Transactional
    public boolean authenticate(String username, String password) {
        try {
            Optional<User> userOpt = userRepository.findByUsernameAndActiveTrue(username);
            
            if (userOpt.isEmpty()) {
                log.warn("⚠️ User not found or inactive: {}", username);
                return false;
            }
            
            User user = userOpt.get();
            
            // ⚠️ Simple password check - use BCrypt in production!
            if (user.getPassword().equals(password)) {
                // Update last login
                user.setLastLogin(LocalDateTime.now());
                userRepository.save(user);
                
                currentUsername = username;
                log.info("✅ User authenticated: {}", username);
                return true;
            } else {
                log.warn("⚠️ Invalid password for user: {}", username);
                return false;
            }
            
        } catch (Exception e) {
            log.error("❌ Authentication error for user: {}", username, e);
            return false;
        }
    }

    /**
     * Get current logged-in username
     */
    public String getCurrentUsername() {
        return currentUsername;
    }

    /**
     * Logout current user
     */
    public void logout() {
        log.info("🚪 User logged out: {}", currentUsername);
        currentUsername = null;
    }
}
