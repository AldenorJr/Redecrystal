package com.redecrystal.core;

import com.redecrystal.core.config.ConfigProvider;
import com.redecrystal.core.event.EventBus;
import com.redecrystal.core.http.BackendHttpClient;
import com.redecrystal.core.messaging.KafkaClient;
import com.redecrystal.core.messaging.KafkaTopics;
import com.redecrystal.core.redis.RedisClient;
import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/**
 * The Crystal SDK entry point. Wires the HTTP/Redis/Kafka clients, the event bus,
 * and the config provider, and manages this instance's lifecycle against the
 * network: Kafka consumer, Eureka-less self-registration, and heartbeats.
 *
 * <p>Every downstream plugin builds one of these in its bootstrap:
 * <pre>{@code
 * CrystalCore crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
 * crystal.config().onChange("lobby", cfg -> applyMotd(cfg.string("motd", "")));
 * crystal.startHeartbeat(() -> Bukkit.getOnlinePlayers().size());
 * }</pre>
 */
public final class CrystalCore implements AutoCloseable {

    private static final CrystalLogger log = CrystalLogger.of(CrystalCore.class);

    private final CrystalConfig config;
    private final BackendHttpClient backend;
    private final RedisClient redis;
    private final KafkaClient kafka;
    private final EventBus events;
    private final ConfigProvider configProvider;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "crystal-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private CrystalCore(CrystalConfig config) {
        this.config = config;
        this.backend = new BackendHttpClient(config.backendUrl(), config.serviceToken());
        this.redis = new RedisClient(config.redisHost(), config.redisPort());
        this.kafka = new KafkaClient(config.kafkaBrokers(), config.serverId());
        this.events = new EventBus();
        this.configProvider = new ConfigProvider(backend, events);
    }

    /** Build and start the SDK: Kafka consumer feeding the event bus. */
    public static CrystalCore bootstrap(CrystalConfig config) {
        CrystalCore core = new CrystalCore(config);
        core.kafka.startConsumer(KafkaTopics.ALL, core.events::dispatch);
        log.info("CrystalCore booted for {} ({})", config.serverId(), config.serverType());
        return core;
    }

    /** Register this instance in the backend Service Discovery registry. */
    public void registerThisServer(int maxPlayers) {
        backend.registerServer(config.serverId(), config.serverType(),
                config.serverHost(), config.serverPort(), maxPlayers);
        log.info("Registered {} with the network", config.serverId());
    }

    /** Begin periodic heartbeats reporting online players + JVM memory/CPU. */
    public void startHeartbeat(IntSupplier onlinePlayers) {
        startHeartbeat(onlinePlayers, null);
    }

    /** As {@link #startHeartbeat(IntSupplier)} plus a TPS sample (Paper servers). */
    public void startHeartbeat(IntSupplier onlinePlayers, DoubleSupplier tps) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                Double t = tps == null ? null : tps.getAsDouble();
                backend.heartbeat(config.serverId(), onlinePlayers.getAsInt(), "ONLINE",
                        t, usedMemoryMb(), cpuLoadPercent());
            } catch (Exception e) {
                log.warn("Heartbeat failed: {}", e.toString());
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    private static double usedMemoryMb() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / (1024.0 * 1024.0);
    }

    private static double cpuLoadPercent() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double load = os.getProcessCpuLoad();
            return load < 0 ? -1 : load * 100.0;
        } catch (Throwable t) {
            return -1;
        }
    }

    public CrystalConfig config()         { return config; }
    public BackendHttpClient backend()    { return backend; }
    public RedisClient redis()            { return redis; }
    public KafkaClient kafka()            { return kafka; }
    public EventBus events()              { return events; }
    public ConfigProvider configProvider(){ return configProvider; }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            backend.deregister(config.serverId());
        } catch (Exception e) {
            log.warn("Deregister failed: {}", e.toString());
        }
        kafka.close();
        redis.close();
        log.info("CrystalCore shut down for {}", config.serverId());
    }
}
