package com.redecrystal.network.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Prometheus metrics for the network, exposed via the backend's
 * {@code /actuator/prometheus}:
 * <ul>
 *   <li>{@code redecrystal_online_players} — network-wide online count (Redis)</li>
 *   <li>{@code redecrystal_redis_ping_seconds} — Redis round-trip latency</li>
 *   <li>{@code redecrystal_server_tps|players|memory_mb|cpu_load{server}} — per
 *       game-server samples reported through heartbeats</li>
 * </ul>
 */
@Component
public class NetworkMetrics {

    private static final ServerSample EMPTY = new ServerSample(-1, -1, -1, -1);

    private final MeterRegistry registry;
    private final StringRedisTemplate redis;
    private final Map<String, ServerSample> samples = new ConcurrentHashMap<>();
    private final Set<String> registered = ConcurrentHashMap.newKeySet();

    public record ServerSample(double tps, double players, double memoryMb, double cpuLoad) {}

    public NetworkMetrics(MeterRegistry registry, StringRedisTemplate redis) {
        this.registry = registry;
        this.redis = redis;

        Gauge.builder("redecrystal_online_players", redis, NetworkMetrics::onlineCount)
                .description("Players online across the whole network")
                .register(registry);

        Gauge.builder("redecrystal_redis_ping_seconds", this, NetworkMetrics::pingLatencySeconds)
                .description("Redis PING round-trip latency in seconds")
                .register(registry);
    }

    /** Record a heartbeat sample and lazily register that server's gauges. */
    public void update(String serverId, int players, Double tps, Double memoryMb, Double cpuLoad) {
        samples.put(serverId, new ServerSample(
                tps == null ? -1 : tps, players,
                memoryMb == null ? -1 : memoryMb,
                cpuLoad == null ? -1 : cpuLoad));
        if (registered.add(serverId)) {
            Tags tags = Tags.of("server", serverId);
            gauge("redecrystal_server_tps", tags, serverId, ServerSample::tps);
            gauge("redecrystal_server_players", tags, serverId, ServerSample::players);
            gauge("redecrystal_server_memory_mb", tags, serverId, ServerSample::memoryMb);
            gauge("redecrystal_server_cpu_load", tags, serverId, ServerSample::cpuLoad);
        }
    }

    private void gauge(String name, Tags tags, String serverId, java.util.function.ToDoubleFunction<ServerSample> field) {
        Gauge.builder(name, samples, m -> field.applyAsDouble(m.getOrDefault(serverId, EMPTY)))
                .tags(tags).register(registry);
    }

    private static double onlineCount(StringRedisTemplate redis) {
        Long n = redis.opsForSet().size("online_players");
        return n == null ? 0 : n;
    }

    private double pingLatencySeconds() {
        long start = System.nanoTime();
        try (RedisConnection con = redis.getRequiredConnectionFactory().getConnection()) {
            con.ping();
        } catch (Exception e) {
            return -1;
        }
        return (System.nanoTime() - start) / 1_000_000_000.0;
    }
}
