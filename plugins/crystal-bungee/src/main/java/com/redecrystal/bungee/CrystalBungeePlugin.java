package com.redecrystal.bungee;

import com.google.inject.Inject;
import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.NetworkServer;
import com.redecrystal.core.messaging.KafkaTopics;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
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

    private final ProxyServer proxy;
    private final Logger logger;
    private CrystalCore crystal;

    /** Recently-routed players per lobby, smoothing balance between heartbeats. */
    private final Map<String, AtomicInteger> pending = new ConcurrentHashMap<>();

    @Inject
    public CrystalBungeePlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        crystal.configProvider().preload(GLOBAL_CONFIG);
        crystal.registerThisServer(crystal.configProvider().get("proxy").integer("maxPlayers", 1000));
        crystal.startHeartbeat(proxy::getPlayerCount);

        // Discover the lobby fleet now and keep it in sync.
        syncLobbies();
        proxy.getScheduler().buildTask(this, this::syncLobbies)
                .repeat(Duration.ofSeconds(10)).schedule();
        crystal.events().on(KafkaTopics.SERVER_STARTED, e -> syncLobbies());
        crystal.events().on(KafkaTopics.SERVER_STOPPED, e -> syncLobbies());

        // Event-driven handoff: when login authenticates a player, route them to
        // the least-loaded lobby. Routing stays on the proxy.
        crystal.events().on(KafkaTopics.PLAYER_AUTHENTICATED, authEvent -> {
            String uuid = authEvent.get("uuid");
            if (uuid == null) {
                return;
            }
            proxy.getPlayer(UUID.fromString(uuid)).ifPresent(this::routeToLobby);
        });

        logger.info("CrystalBungee enabled.");
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
    }

    /** Pick the least-loaded online lobby and connect the player to it. */
    private void routeToLobby(com.velocitypowered.api.proxy.Player player) {
        List<NetworkServer> lobbies = crystal.backend().listServers(LOBBY_TYPE);
        Optional<NetworkServer> best = lobbies.stream()
                .filter(NetworkServer::isOnline)
                .min(Comparator.comparingInt(s ->
                        s.onlinePlayers() + pending.getOrDefault(s.serverId(), new AtomicInteger()).get()));
        if (best.isEmpty()) {
            logger.warn("No lobby available for {}", player.getUsername());
            return;
        }
        String target = best.get().serverId();
        proxy.getServer(target).ifPresent(server -> {
            pending.computeIfAbsent(target, k -> new AtomicInteger()).incrementAndGet();
            player.createConnectionRequest(server).fireAndForget();
            logger.info("Routed {} to {} (least-loaded)", player.getUsername(), target);
        });
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (crystal != null) {
            crystal.close();
        }
    }

    /** Deny logins while the network is in maintenance (no bypass yet). */
    @Subscribe
    public void onLogin(LoginEvent event) {
        if (crystal.configProvider().get(GLOBAL_CONFIG).bool("maintenance", false)) {
            event.setResult(com.velocitypowered.api.event.ResultedEvent.ComponentResult.denied(
                    Component.text("RedeCrystal está em manutenção. Volte em breve!")));
        }
    }

    /** Send freshly-connected players to a login server first. */
    @Subscribe
    public void onChooseServer(PlayerChooseInitialServerEvent event) {
        proxy.getServer(LOGIN_SERVER).ifPresent(event::setInitialServer);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        crystal.redis().removeOnlinePlayer(uuid);
        crystal.kafka().publish(KafkaTopics.PLAYER_DISCONNECTED, uuid, Map.of(
                "player", event.getPlayer().getUsername(),
                "uuid", uuid,
                "proxy", crystal.config().serverId()));
    }
}
