package com.redecrystal.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Network login identity (row of {@code player_accounts}). */
@Entity
@Table(name = "player_accounts")
public class PlayerAccount {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "username", length = 16, nullable = false)
    private String username;

    @Column(name = "username_lower", length = 16, nullable = false)
    private String usernameLower;

    @Column(name = "password_hash", length = 255)
    private String passwordHash; // NULL for premium accounts

    @Column(name = "premium", nullable = false)
    private boolean premium;

    @Column(name = "first_login_at", nullable = false)
    private OffsetDateTime firstLoginAt;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PlayerAccount() {
        // for JPA
    }

    public PlayerAccount(UUID id, String username, boolean premium, String passwordHash) {
        this.id = id;
        this.username = username;
        this.usernameLower = username.toLowerCase();
        this.premium = premium;
        this.passwordHash = passwordHash; // null for premium
        OffsetDateTime now = OffsetDateTime.now();
        this.firstLoginAt = now;
        this.lastLoginAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Replace the stored credential (cracked accounts only). */
    public void setPasswordHash(String hash) {
        this.passwordHash = hash;
        this.updatedAt = OffsetDateTime.now();
    }

    /** Record a successful authentication and refresh the cased username. */
    public void touchLogin(String name) {
        if (name != null && !name.equals(this.username)) {
            this.username = name;
            this.usernameLower = name.toLowerCase();
        }
        this.lastLoginAt = OffsetDateTime.now();
        this.updatedAt = this.lastLoginAt;
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getUsernameLower() { return usernameLower; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isPremium() { return premium; }
    public OffsetDateTime getFirstLoginAt() { return firstLoginAt; }
    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
