package com.redecrystal.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A centralized configuration entry (one row of {@code network_configs}). The
 * {@code config} payload is free-form JSON (jsonb) so new keys can be added
 * without schema changes; {@code version} bumps on every update to support
 * client-side cache invalidation and hot reload.
 */
@Entity
@Table(name = "network_configs")
public class NetworkConfig {

    @Id
    @Column(name = "config_key", length = 64)
    private String configKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false)
    private Map<String, Object> config;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected NetworkConfig() {
        // for JPA
    }

    public NetworkConfig(String configKey, Map<String, Object> config) {
        this.configKey = configKey;
        this.config = config;
        this.version = 1;
        this.updatedAt = OffsetDateTime.now();
    }

    /** Replace the payload, bump the version, and stamp the update time. */
    public void update(Map<String, Object> newConfig) {
        this.config = newConfig;
        this.version += 1;
        this.updatedAt = OffsetDateTime.now();
    }

    public boolean isMaintenance() {
        return Boolean.TRUE.equals(config.get("maintenance"));
    }

    public String getConfigKey() {
        return configKey;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public int getVersion() {
        return version;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
