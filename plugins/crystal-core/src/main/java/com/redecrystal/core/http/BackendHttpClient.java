package com.redecrystal.core.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.redecrystal.core.CrystalLogger;
import com.redecrystal.core.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed HTTP client for the backend. Always targets the API Gateway and presents
 * the service token as a bearer credential — plugins never call domain services
 * directly, so the gateway remains the single authentication point.
 */
public final class BackendHttpClient {

    private static final CrystalLogger log = CrystalLogger.of(BackendHttpClient.class);
    private static final int MAX_ATTEMPTS = 3;

    private final String baseUrl;
    private final String token;
    private final HttpClient http;

    public BackendHttpClient(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    // ── Config Service ──

    /** Fetch a config entry by key. Throws on failure (caller decides fallback). */
    public RemoteConfig getConfig(String key) {
        JsonNode node = send("GET", "/api/config/" + key, null);
        return toRemoteConfig(node);
    }

    /** Update a config entry; triggers a config-updated event server-side. */
    public RemoteConfig putConfig(String key, Map<String, Object> config) {
        JsonNode node = send("PUT", "/api/config/" + key, Map.of("config", config));
        return toRemoteConfig(node);
    }

    // ── Service Discovery (game servers) ──

    public void registerServer(String serverId, String type, String host, int port, int maxPlayers) {
        send("POST", "/api/network/register", Map.of(
                "serverId", serverId, "type", type, "host", host,
                "port", port, "maxPlayers", maxPlayers));
    }

    public void heartbeat(String serverId, int onlinePlayers, String status) {
        heartbeat(serverId, onlinePlayers, status, null, null, null);
    }

    /** Heartbeat with optional observability samples (tps/memory/cpu may be null). */
    public void heartbeat(String serverId, int onlinePlayers, String status,
                          Double tps, Double memoryMb, Double cpuLoad) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("onlinePlayers", onlinePlayers);
        body.put("status", status);
        if (tps != null) body.put("tps", tps);
        if (memoryMb != null) body.put("memoryMb", memoryMb);
        if (cpuLoad != null) body.put("cpuLoad", cpuLoad);
        send("POST", "/api/network/" + serverId + "/heartbeat", body);
    }

    public void deregister(String serverId) {
        send("DELETE", "/api/network/" + serverId, null);
    }

    // ── Profiles ──

    public ProfileData ensureProfile(String uuid, String username) {
        return toProfile(send("PUT", "/api/profile/" + uuid, Map.of("username", username)));
    }

    /** Fetch a profile, or {@code null} if it does not exist yet. */
    public ProfileData getProfile(String uuid) {
        JsonNode n = send("GET", "/api/profile/" + uuid, null, true);
        return n == null ? null : toProfile(n);
    }

    public ProfileData addStats(String uuid, long coins, long experience, long playSeconds) {
        return toProfile(send("POST", "/api/profile/" + uuid + "/add", Map.of(
                "coins", coins, "experience", experience, "playSeconds", playSeconds)));
    }

    // ── Inventories ──

    public InventoryData getInventory(String uuid, String serverType) {
        JsonNode n = send("GET", "/api/inventory/" + uuid + "/" + serverType, null);
        return new InventoryData(n.path("content").asText(""), n.path("version").asInt());
    }

    /**
     * Save an inventory with optimistic locking; returns the new version. Throws
     * {@link BackendException} (HTTP 409) if {@code version} is stale.
     */
    public int saveInventory(String uuid, String serverType, String content, int version) {
        JsonNode n = send("PUT", "/api/inventory/" + uuid + "/" + serverType, Map.of(
                "content", content, "version", version));
        return n.path("version").asInt();
    }

    // ── Parkour ──

    public ParkourResult submitParkourTime(String uuid, String username, long timeMs) {
        JsonNode n = send("POST", "/api/parkour/time", Map.of(
                "uuid", uuid, "username", username == null ? "" : username, "timeMs", timeMs));
        return new ParkourResult(n.path("bestTimeMs").asLong(), n.path("record").asBoolean(), n.path("rank").asLong());
    }

    public List<ParkourEntry> parkourTop(int limit) {
        JsonNode arr = send("GET", "/api/parkour/top?limit=" + limit, null);
        List<ParkourEntry> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                out.add(new ParkourEntry(n.path("rank").asLong(), n.path("username").asText(""), n.path("timeMs").asLong()));
            }
        }
        return out;
    }

    /** Personal best in ms, or -1 if the player has no recorded time. */
    public long parkourBest(String uuid) {
        JsonNode n = send("GET", "/api/parkour/best/" + uuid, null, true);
        return n == null ? -1 : n.path("bestTimeMs").asLong(-1);
    }

    private ProfileData toProfile(JsonNode n) {
        return new ProfileData(
                n.path("uuid").asText(), n.path("username").asText(null), n.path("rank").asText("DEFAULT"),
                n.path("level").asInt(1), n.path("experience").asLong(), n.path("coins").asLong(),
                n.path("playSeconds").asLong());
    }

    /** List registered servers of a given type (e.g. "lobby") for discovery/balancing. */
    public List<NetworkServer> listServers(String type) {
        JsonNode arr = send("GET", "/api/network?type=" + type, null);
        List<NetworkServer> servers = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                servers.add(new NetworkServer(
                        n.path("serverId").asText(),
                        n.path("type").asText(),
                        n.path("host").asText(),
                        n.path("port").asInt(),
                        n.path("status").asText(),
                        n.path("maxPlayers").asInt(),
                        n.path("onlinePlayers").asInt()));
            }
        }
        return servers;
    }

    // ── plumbing ──

    private RemoteConfig toRemoteConfig(JsonNode node) {
        return new RemoteConfig(
                node.path("key").asText(),
                node.path("version").asInt(),
                Json.MAPPER.convertValue(node.path("config"), Map.class));
    }

    private JsonNode send(String method, String path, Object body) {
        return send(method, path, body, false);
    }

    private JsonNode send(String method, String path, Object body, boolean allowNotFound) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(5))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/json");
                if (body != null) {
                    b.header("Content-Type", "application/json");
                    b.method(method, HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)));
                } else {
                    b.method(method, HttpRequest.BodyPublishers.noBody());
                }
                HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                int sc = resp.statusCode();
                if (sc >= 200 && sc < 300) {
                    return resp.body().isEmpty() ? Json.MAPPER.createObjectNode()
                            : Json.MAPPER.readTree(resp.body());
                }
                if (sc == 404 && allowNotFound) {
                    return null;
                }
                throw new BackendException(method + " " + path + " -> HTTP " + sc + ": " + resp.body());
            } catch (BackendException e) {
                throw e; // non-2xx is not retryable here
            } catch (Exception e) {
                last = new BackendException("transport error on " + method + " " + path, e);
                log.warn("Backend call {} {} failed (attempt {}/{}): {}", method, path, attempt, MAX_ATTEMPTS, e.toString());
                sleep(200L * attempt);
            }
        }
        throw last;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Raised when a backend call fails (transport error or non-2xx response). */
    public static final class BackendException extends RuntimeException {
        public BackendException(String message) {
            super(message);
        }

        public BackendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
