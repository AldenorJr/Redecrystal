package com.redecrystal.core.http;

/**
 * A registered server instance as returned by the Service Discovery API
 * (mirror of the backend's ServerResponse). Used by the proxy to discover and
 * balance across the lobby fleet without static configuration.
 */
public record NetworkServer(
        String serverId,
        String type,
        String host,
        int port,
        String status,
        int maxPlayers,
        int onlinePlayers) {

    public boolean isOnline() {
        return "ONLINE".equals(status);
    }
}
