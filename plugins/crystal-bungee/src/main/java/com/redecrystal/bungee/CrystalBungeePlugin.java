package com.redecrystal.bungee;

import com.google.inject.Inject;
import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.NetworkServer;
import com.redecrystal.core.messaging.KafkaTopics;
import com.redecrystal.core.security.JwtCodec;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

/**
 * Velocity proxy plugin. Boots the Crystal SDK, registers the proxy, routes new
 * players to login, enforces maintenance, and — crucially for horizontal
 * scaling — discovers the lobby fleet from the Service Discovery registry and
 * registers each lobby with Velocity dynamically. New lobby instances appear and
 * receive players with zero proxy config or code changes; dead ones are dropped.
 */
@Plugin(id = "crystal-bungee", name = "Crystal Bungee", version = "0.1.0", authors = {"RedeCrystal"})
public final class CrystalBungeePlugin {

    private static final String LOGIN_SERVER = "login";
    private static final String LOBBY_TYPE = "lobby";
    private static final String GLOBAL_CONFIG = "global";
    private static final String JWT_KEY_PREFIX = "jwt:";
    private static final String SESSION_KEY_PREFIX = "session:";

    /** The login server hands us a verified player JWT over this channel. */
    private static final MinecraftChannelIdentifier AUTH_CHANNEL =
            MinecraftChannelIdentifier.create("crystal", "auth");

    private final ProxyServer proxy;
    private final Logger logger;
    private CrystalCore crystal;

    /** Recently-routed players per lobby, smoothing balance between heartbeats. */
    private final Map<String, AtomicInteger> pending = new ConcurrentHashMap<>();

    /** Players whose JWT the login server verified — the gate's allow-list. */
    private final Map<UUID, JwtCodec.Claims> authed = new ConcurrentHashMap<>();

    /** Authenticated players parked because no lobby was online; drained on lobby start. */
    private final Set<UUID> waitingForLobby = ConcurrentHashMap.newKeySet();

    @Inject
    public CrystalBungeePlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        crystal.configProvider().preload(GLOBAL_CONFIG);

        // Register this proxy in the network registry. A transient backend hiccup
        // at cold boot — the api-gateway → core-service route not yet propagated
        // through Eureka, yielding a 503 — must NOT abort the rest of init.
        // Otherwise lobby discovery below never starts and /server stays empty
        // until the proxy is restarted by hand. Retry in the background instead.
        registerWithRetry();
        crystal.startHeartbeat(proxy::getPlayerCount);

        // The login server hands us verified JWTs over this channel.
        proxy.getChannelRegistrar().register(AUTH_CHANNEL);

        // Discover the lobby fleet now and keep it in sync. Each sync also drains
        // any players parked waiting for a lobby to come online.
        syncLobbies();
        proxy.getScheduler().buildTask(this, this::syncLobbies)
                .repeat(Duration.ofSeconds(10)).schedule();
        crystal.events().on(KafkaTopics.SERVER_STARTED, e -> syncLobbies());
        crystal.events().on(KafkaTopics.SERVER_STOPPED, e -> syncLobbies());

        logger.info("CrystalBungee enabled.");
    }

    /**
     * Register this proxy in the network registry, retrying on transient failure.
     * The maxPlayers lookup and the register call both touch the backend, which
     * may be briefly unreachable at boot; a failure here is logged and retried in
     * the background so the proxy self-heals without operator intervention.
     */
    private void registerWithRetry() {
        try {
            int maxPlayers = crystal.configProvider().get("proxy").integer("maxPlayers", 1000);
            crystal.registerThisServer(maxPlayers);
        } catch (Exception e) {
            logger.warn("Proxy registration failed ({}); retrying in 10s", e.toString());
            proxy.getScheduler().buildTask(this, this::registerWithRetry)
                    .delay(Duration.ofSeconds(10)).schedule();
        }
    }

    /** Reconcile Velocity's registered lobby servers with the live registry. */
    private void syncLobbies() {
        try {
            List<NetworkServer> lobbies = crystal.backend().listServers(LOBBY_TYPE);
            Set<String> wanted = new HashSet<>();
            for (NetworkServer s : lobbies) {
                if (!s.isOnline()) {
                    continue;
                }
                wanted.add(s.serverId());
                if (proxy.getServer(s.serverId()).isEmpty()) {
                    proxy.registerServer(new ServerInfo(
                            s.serverId(), new InetSocketAddress(s.host(), s.port())));
                    logger.info("Discovered lobby {} ({}:{})", s.serverId(), s.host(), s.port());
                }
            }
            // Drop lobbies that left the registry (scaled down / crashed).
            for (RegisteredServer rs : proxy.getAllServers()) {
                String name = rs.getServerInfo().getName();
                if (name.startsWith("lobby") && !wanted.contains(name)) {
                    proxy.unregisterServer(rs.getServerInfo());
                    pending.remove(name);
                    logger.info("Removed lobby {} (no longer registered)", name);
                }
            }
            // Registry counts are authoritative again — reset local smoothing.
            pending.keySet().retainAll(wanted);
            pending.values().forEach(c -> c.set(0));
        } catch (Exception e) {
            logger.warn("Lobby sync failed: {}", e.toString());
        }
        // A lobby may have just come online — try to release anyone who was waiting.
        drainWaitingForLobby();
    }

    /**
     * Route an authenticated player to the least-loaded online lobby. If none is
     * online, park them on login and retry on the next lobby start / sync tick.
     */
    private void routeToLobby(Player player) {
        Optional<NetworkServer> best = pickLobby();
        if (best.isEmpty()) {
            waitingForLobby.add(player.getUniqueId());
            player.sendActionBar(Component.text("Procurando um lobby disponível...", PURPLE_SOFT));
            logger.info("No lobby online; {} is waiting for one to start", player.getUsername());
            return;
        }
        String target = best.get().serverId();
        proxy.getServer(target).ifPresent(server -> {
            pending.computeIfAbsent(target, k -> new AtomicInteger()).incrementAndGet();
            waitingForLobby.remove(player.getUniqueId());
            player.createConnectionRequest(server).fireAndForget();
            logger.info("Routed {} to {} (least-loaded)", player.getUsername(), target);
        });
    }

    /** The least-loaded online lobby, accounting for in-flight routes. */
    private Optional<NetworkServer> pickLobby() {
        return crystal.backend().listServers(LOBBY_TYPE).stream()
                .filter(NetworkServer::isOnline)
                .min(Comparator.comparingInt(s ->
                        s.onlinePlayers() + pending.getOrDefault(s.serverId(), new AtomicInteger()).get()));
    }

    /** Retry routing every parked player; drops those who disconnected. */
    private void drainWaitingForLobby() {
        if (waitingForLobby.isEmpty()) {
            return;
        }
        for (UUID uuid : Set.copyOf(waitingForLobby)) {
            proxy.getPlayer(uuid).ifPresentOrElse(this::routeToLobby, () -> waitingForLobby.remove(uuid));
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (crystal != null) {
            crystal.close();
        }
    }

    // RedeCrystal purple wordmark color, shared across MOTD / kick.
    private static final TextColor PURPLE = TextColor.color(0xB14AED);
    private static final TextColor PURPLE_SOFT = TextColor.color(0xC9A6FF);

    /**
     * Deny logins while the network is in maintenance, except for staff listed in
     * {@code global.maintenanceBypass} (player names), so they can get in to work.
     */
    @Subscribe
    public void onLogin(LoginEvent event) {
        if (!crystal.configProvider().get(GLOBAL_CONFIG).bool("maintenance", false)) {
            return;
        }
        String name = event.getPlayer().getUsername().toLowerCase(Locale.ROOT);
        if (bypassNames().contains(name)) {
            logger.info("Maintenance bypass for {}", event.getPlayer().getUsername());
            return;
        }
        event.setResult(com.velocitypowered.api.event.ResultedEvent.ComponentResult.denied(
                maintenanceKick()));
    }

    /** Swap the server-list MOTD for a maintenance banner while maintenance is on. */
    @Subscribe
    public void onPing(ProxyPingEvent event) {
        if (!crystal.configProvider().get(GLOBAL_CONFIG).bool("maintenance", false)) {
            return; // normal MOTD comes from velocity.toml
        }
        ServerPing maintenancePing = event.getPing().asBuilder()
                .description(maintenanceMotd())
                .build();
        event.setPing(maintenancePing);
    }

    /** Lower-cased set of player names allowed in during maintenance. */
    private Set<String> bypassNames() {
        Object raw = crystal.configProvider().get(GLOBAL_CONFIG).value("maintenanceBypass");
        if (raw instanceof List<?> list) {
            return list.stream().map(o -> String.valueOf(o).toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
        }
        return Set.of();
    }

    /** Two-line maintenance MOTD with the purple RedeCrystal wordmark. */
    private static Component maintenanceMotd() {
        Component line1 = Component.text("  REDECRYSTAL", PURPLE, TextDecoration.BOLD)
                .append(Component.text(" » ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false))
                .append(Component.text("Em manutenção", NamedTextColor.RED, TextDecoration.BOLD));
        Component line2 = Component.text("  Estamos melhorando a experiência. Voltamos já!", PURPLE_SOFT);
        return line1.append(Component.newline()).append(line2);
    }

    /** Kick screen shown to non-bypass players during maintenance. */
    private static Component maintenanceKick() {
        return Component.text("REDECRYSTAL", PURPLE, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("A rede está em manutenção.", NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("Estamos melhorando a experiência — volte em breve!", PURPLE_SOFT));
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
        proxy.getPlayer(claims.uuid()).ifPresent(this::routeToLobby);
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
        waitingForLobby.remove(id);
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
