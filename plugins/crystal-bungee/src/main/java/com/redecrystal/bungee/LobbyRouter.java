package com.redecrystal.bungee;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.NetworkServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
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
 * Discovers the lobby fleet from the Service Discovery registry and keeps
 * Velocity's registered servers in sync with it, then balances authenticated
 * players onto the least-loaded online lobby. Players who arrive while no lobby
 * is online are parked and released as soon as one starts. Cohesive proxy
 * routing state — per-lobby in-flight counts and the waiting set — lives
 * together here; the {@code @Subscribe} handlers that feed it delegate in.
 */
public final class LobbyRouter {

    private static final String LOBBY_TYPE = "lobby";

    private final ProxyServer proxy;
    private final CrystalCore crystal;
    private final Logger logger;

    /** Recently-routed players per lobby, smoothing balance between heartbeats. */
    private final Map<String, AtomicInteger> pending = new ConcurrentHashMap<>();

    /** Authenticated players parked because no lobby was online; drained on lobby start. */
    private final Set<UUID> waitingForLobby = ConcurrentHashMap.newKeySet();

    public LobbyRouter(ProxyServer proxy, CrystalCore crystal, Logger logger) {
        this.proxy = proxy;
        this.crystal = crystal;
        this.logger = logger;
    }

    // ── fleet discovery ──

    /** Reconcile Velocity's registered lobby servers with the live registry. */
    public void syncLobbies() {
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

    // ── routing ──

    /**
     * Route an authenticated player to the least-loaded online lobby. If none is
     * online, park them on login and retry on the next lobby start / sync tick.
     */
    public void routeToLobby(Player player) {
        Optional<NetworkServer> best = pickLobby();
        if (best.isEmpty()) {
            waitingForLobby.add(player.getUniqueId());
            player.sendActionBar(Component.text("Procurando um lobby disponível...", BrandColors.PURPLE_SOFT));
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

    /** Forget a player who left while parked, so the waiting set never leaks. */
    public void removeWaiting(UUID uuid) {
        waitingForLobby.remove(uuid);
    }
}
