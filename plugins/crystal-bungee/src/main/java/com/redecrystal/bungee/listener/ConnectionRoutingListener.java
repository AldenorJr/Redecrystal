package com.redecrystal.bungee.listener;

import com.redecrystal.bungee.LobbyRouter;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.messaging.KafkaTopics;
import com.redecrystal.core.security.JwtCodec;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * The proxy connection gate and login flow. Forces fresh players to the login
 * server first, accepts the verified JWT the login server hands back over the
 * {@link #AUTH_CHANNEL} channel, and only then lets a player onto a lobby —
 * delegating the balancing to {@link LobbyRouter}. Holds the authenticated
 * allow-list and tears the session down on disconnect. Cohesive shared state
 * ({@code authed}) keeps these handlers together.
 */
public final class ConnectionRoutingListener {

    private static final String LOGIN_SERVER = "login";
    private static final String JWT_KEY_PREFIX = "jwt:";
    private static final String SESSION_KEY_PREFIX = "session:";

    /** The login server hands us a verified player JWT over this channel. */
    public static final MinecraftChannelIdentifier AUTH_CHANNEL =
            MinecraftChannelIdentifier.create("crystal", "auth");

    private final ProxyServer proxy;
    private final CrystalCore crystal;
    private final Logger logger;
    private final LobbyRouter router;

    /** Players whose JWT the login server verified — the gate's allow-list. */
    private final Map<UUID, JwtCodec.Claims> authed = new ConcurrentHashMap<>();

    public ConnectionRoutingListener(ProxyServer proxy, CrystalCore crystal, Logger logger, LobbyRouter router) {
        this.proxy = proxy;
        this.crystal = crystal;
        this.logger = logger;
        this.router = router;
    }

    /** Send freshly-connected players to a login server first. */
    @Subscribe
    public void onChooseServer(PlayerChooseInitialServerEvent event) {
        proxy.getServer(LOGIN_SERVER).ifPresent(event::setInitialServer);
    }

    /**
     * The login server's handshake: a verified player JWT. We re-verify the
     * signature here (offline), confirm the session wasn't revoked (Redis
     * {@code jwt:{uuid}} still holds this token's {@code sid}), record the player
     * as authenticated, and route them to a lobby. Only after this does the
     * connect gate ({@link #onServerPreConnect}) let them onto a backend server.
     */
    @Subscribe
    public void onAuthHandshake(PluginMessageEvent event) {
        if (!AUTH_CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        // Consume it: never forward the token on to the client.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        // Only the backend (login server) may assert authentication, never a client.
        if (!(event.getSource() instanceof ServerConnection)) {
            return;
        }

        String token = new String(event.getData(), StandardCharsets.UTF_8);
        Optional<JwtCodec.Claims> verified = crystal.jwtCodec().verify(token);
        if (verified.isEmpty()) {
            logger.warn("Rejected an invalid auth handshake token");
            return;
        }
        JwtCodec.Claims claims = verified.get();
        if (isRevoked(claims)) {
            logger.warn("Rejected a revoked/superseded session for {}", claims.username());
            return;
        }

        authed.put(claims.uuid(), claims);
        proxy.getPlayer(claims.uuid()).ifPresent(router::routeToLobby);
    }

    /**
     * The gate that makes login non-bypassable: a player may only reach the login
     * server or — once authenticated with a still-valid JWT — a lobby. Any other
     * connection attempt (e.g. a direct {@code /server lobby}) is denied.
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        String target = event.getOriginalServer().getServerInfo().getName();
        if (LOGIN_SERVER.equals(target)) {
            return; // login is always reachable
        }
        JwtCodec.Claims claims = authed.get(event.getPlayer().getUniqueId());
        if (claims == null || claims.expiresAtEpochSec() <= Instant.now().getEpochSecond()) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            logger.warn("Denied {} -> {}: not authenticated", event.getPlayer().getUsername(), target);
        }
    }

    /** A session is revoked if Redis holds a different sid for this player. */
    private boolean isRevoked(JwtCodec.Claims claims) {
        try {
            String activeSid = crystal.redis().get(JWT_KEY_PREFIX + claims.uuid());
            // null => Redis down or key expired: trust the (valid) signature, fail open.
            return activeSid != null && !activeSid.equals(claims.sessionId());
        } catch (Exception e) {
            logger.warn("Revocation check failed for {} (allowing): {}", claims.username(), e.toString());
            return false;
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        String uuid = id.toString();
        authed.remove(id);
        router.removeWaiting(id);
        crystal.redis().removeOnlinePlayer(uuid);
        // Revoke the session so a stale token can't be replayed after logout.
        try {
            crystal.redis().del(JWT_KEY_PREFIX + uuid);
            crystal.redis().del(SESSION_KEY_PREFIX + uuid);
        } catch (Exception e) {
            logger.warn("Session cleanup failed for {}: {}", event.getPlayer().getUsername(), e.toString());
        }
        crystal.kafka().publish(KafkaTopics.PLAYER_DISCONNECTED, uuid, Map.of(
                "player", event.getPlayer().getUsername(),
                "uuid", uuid,
                "proxy", crystal.config().serverId()));
    }
}
