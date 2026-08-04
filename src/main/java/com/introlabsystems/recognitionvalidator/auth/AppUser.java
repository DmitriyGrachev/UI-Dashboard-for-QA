package com.introlabsystems.recognitionvalidator.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AppUser {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 16,
            columnDefinition = "varchar(16) default 'OPERATOR'"
    )
    private UserRole role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static AppUser operator(
            UUID id,
            String username,
            String passwordHash,
            Instant createdAt
    ) {
        return new AppUser(
                id,
                username,
                passwordHash,
                true,
                UserRole.OPERATOR,
                createdAt
        );
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void deactivate() {
        enabled = false;
    }

    public void restore() {
        enabled = true;
    }
}
