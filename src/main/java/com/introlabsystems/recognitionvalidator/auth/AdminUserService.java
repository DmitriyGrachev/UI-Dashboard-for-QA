package com.introlabsystems.recognitionvalidator.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class AdminUserService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final UserSessionService sessions;

    public AdminUserService(
            AppUserRepository users,
            PasswordEncoder passwordEncoder,
            Clock clock,
            JdbcTemplate jdbc,
            UserSessionService sessions
    ) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Transactional
    public void createOperator(String username, String password) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (normalizedUsername.isEmpty()) {
            throw new AdminUserException("username", "Username is required");
        }
        validatePassword(password);
        if (users.existsByUsername(normalizedUsername)) {
            throw new AdminUserException("username", "Username is already in use");
        }

        users.save(AppUser.operator(
                UUID.randomUUID(),
                normalizedUsername,
                passwordEncoder.encode(password),
                clock.instant()
        ));
    }

    @Transactional
    public void deactivateOperator(UUID operatorId) {
        AppUser operator = operator(operatorId);
        operator.deactivate();
        jdbc.update("""
                UPDATE review_task
                SET status = 'PENDING',
                    assigned_to = NULL,
                    assigned_at = NULL,
                    lease_expires_at = NULL
                WHERE assigned_to = ?
                  AND status = 'ASSIGNED'
                """, operatorId);
        sessions.expireFor(operatorId);
    }

    @Transactional
    public void restoreOperator(UUID operatorId) {
        operator(operatorId).restore();
    }

    @Transactional
    public void changePassword(UUID operatorId, String password) {
        validatePassword(password);
        operator(operatorId).changePasswordHash(passwordEncoder.encode(password));
        sessions.expireFor(operatorId);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new AdminUserException(
                    "password",
                    "Password must contain at least 8 characters"
            );
        }
    }

    private AppUser operator(UUID operatorId) {
        AppUser user = users.findById(operatorId)
                .orElseThrow(() -> new AdminUserException(
                        "operator",
                        "Operator was not found"
                ));
        if (user.getRole() != UserRole.OPERATOR) {
            throw new AdminUserException("operator", "Only operators can be managed");
        }
        return user;
    }
}
