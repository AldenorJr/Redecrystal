package com.redecrystal.config.api;

import com.redecrystal.config.domain.NetworkConfig;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.Map;

/** Request/response DTOs for the Config API. */
public final class ConfigDtos {

    private ConfigDtos() {}

    public record ConfigResponse(
            String key,
            int version,
            Map<String, Object> config,
            OffsetDateTime updatedAt) {

        public static ConfigResponse from(NetworkConfig c) {
            return new ConfigResponse(c.getConfigKey(), c.getVersion(), c.getConfig(), c.getUpdatedAt());
        }
    }

    public record UpdateConfigRequest(
            @NotNull(message = "is required") Map<String, Object> config) {
    }
}
