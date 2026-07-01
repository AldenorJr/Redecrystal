package com.redecrystal.bungee;

import com.google.inject.Inject;
import com.redecrystal.bungee.command.ChangePasswordCommand;
import com.redecrystal.bungee.command.PingCommand;
import com.redecrystal.bungee.listener.ConnectionRoutingListener;
import com.redecrystal.bungee.listener.MaintenanceListener;
import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.messaging.KafkaTopics;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Duration;
import org.slf4j.Logger;

/**
 * Velocity proxy plugin. Boots the Crystal SDK, registers the proxy, registers
 * the listeners that route players and enforce maintenance, and — crucially for
 * horizontal scaling — drives the {@link LobbyRouter} that discovers the lobby
 * fleet from the Service Discovery registry and registers each lobby with
 * Velocity dynamically. New lobby instances appear and receive players with zero
 * proxy config or code changes; dead ones are dropped. This {@code @Plugin}
 * class stays the injected lifecycle owner: it bootstraps, self-registers and
 * wires everything, but holds no per-player event logic itself.
 */
@Plugin(id = "crystal-bungee", name = "Crystal Bungee", version = "0.1.0", authors = {"RedeCrystal"})
public final class CrystalBungeePlugin {

    private static final String GLOBAL_CONFIG = "global";

    private final ProxyServer proxy;
    private final Logger logger;
    private CrystalCore crystal;
    private LobbyRouter router;

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
        proxy.getChannelRegistrar().register(ConnectionRoutingListener.AUTH_CHANNEL);

        this.router = new LobbyRouter(proxy, crystal, logger);
        var events = proxy.getEventManager();
        events.register(this, new MaintenanceListener(crystal, logger));
        events.register(this, new ConnectionRoutingListener(proxy, crystal, logger, router));

        // Network-wide self-service password change (see ChangePasswordCommand).
        var commandManager = proxy.getCommandManager();
        var passwordMeta = commandManager.metaBuilder("trocarsenha")
                .aliases("mudarsenha")
                .plugin(this)
                .build();
        commandManager.register(passwordMeta, new ChangePasswordCommand(this, proxy, crystal, logger));

        // Network-wide latency check (see PingCommand).
        var pingMeta = commandManager.metaBuilder("ping").plugin(this).build();
        commandManager.register(pingMeta, new PingCommand());

        // Discover the lobby fleet now and keep it in sync. Each sync also drains
        // any players parked waiting for a lobby to come online.
        router.syncLobbies();
        proxy.getScheduler().buildTask(this, router::syncLobbies)
                .repeat(Duration.ofSeconds(10)).schedule();
        crystal.events().on(KafkaTopics.SERVER_STARTED, e -> router.syncLobbies());
        crystal.events().on(KafkaTopics.SERVER_STOPPED, e -> router.syncLobbies());

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

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (crystal != null) {
            crystal.close();
        }
    }
}
