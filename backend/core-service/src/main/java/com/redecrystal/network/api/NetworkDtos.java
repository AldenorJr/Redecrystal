package com.redecrystal.network.api;

import com.redecrystal.network.domain.ServerInstance;
import com.redecrystal.network.domain.ServerStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;

/** Request/response DTOs for the Service Discovery API. */
public final class NetworkDtos {

    private NetworkDtos() {}

    public record RegisterServerRequest(
            @NotBlank(message = "is required") String serverId,
            @NotBlank(message = "is required") String type,
            @NotBlank(message = "is required") String host,
            @Min(value = 1, message = "must be a valid port") int port,
            @Min(value = 0, message = "must be >= 0") int maxPlayers) {
    }

    public record HeartbeatRequest(
            @Min(value = 0, message = "must be >= 0") int onlinePlayers,
            ServerStatus status,
            Double tps,
            Double memoryMb,
            Double cpuLoad) {
    }

    public record ServerResponse(
            String serverId,
            String type,
            String host,
            int port,
            ServerStatus status,
            int maxPlayers,
            int onlinePlayers,
            OffsetDateTime registeredAt,
            OffsetDateTime lastHeartbeat) {

        public static ServerResponse from(ServerInstance s) {
            return new ServerResponse(
                    s.getServerId(), s.getType(), s.getHost(), s.getPort(), s.getStatus(),
                    s.getMaxPlayers(), s.getOnlinePlayers(), s.getRegisteredAt(), s.getLastHeartbeat());
        }
    }
}
