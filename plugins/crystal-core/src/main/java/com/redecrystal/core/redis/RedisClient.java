package com.redecrystal.core.redis;

import com.redecrystal.core.CrystalLogger;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Lettuce-backed Redis wrapper covering the network's hot-state needs: sessions
 * ({@code session:{uuid}}), online players ({@code online_players}), config cache
 * ({@code config:*}), leaderboards ({@code leaderboard:*}) and pub/sub.
 */
public final class RedisClient implements AutoCloseable {

    private static final CrystalLogger log = CrystalLogger.of(RedisClient.class);
    public static final String ONLINE_PLAYERS_KEY = "online_players";

    private final io.lettuce.core.RedisClient lettuce;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> sync;
    private StatefulRedisPubSubConnection<String, String> pubSub;

    public RedisClient(String host, int port) {
        this.lettuce = io.lettuce.core.RedisClient.create(
                RedisURI.builder().withHost(host).withPort(port)
                        .withTimeout(Duration.ofSeconds(2)).build());
        this.connection = lettuce.connect();
        this.sync = connection.sync();
        log.info("Connected to Redis at {}:{}", host, port);
    }

    // ── key/value ──
    public String get(String key)                 { return sync.get(key); }
    public void set(String key, String value)      { sync.set(key, value); }
    public void setex(String key, String value, Duration ttl) {
        sync.setex(key, ttl.toSeconds(), value);
    }
    public void del(String key)                    { sync.del(key); }
    public long incr(String key)                   { return sync.incr(key); }
    public void expire(String key, Duration ttl)   { sync.expire(key, ttl.toSeconds()); }

    // ── online players (set) ──
    public void addOnlinePlayer(String uuid)       { sync.sadd(ONLINE_PLAYERS_KEY, uuid); }
    public void removeOnlinePlayer(String uuid)    { sync.srem(ONLINE_PLAYERS_KEY, uuid); }
    public Set<String> onlinePlayers()             { return Set.copyOf(sync.smembers(ONLINE_PLAYERS_KEY)); }
    public long onlineCount()                      { return sync.scard(ONLINE_PLAYERS_KEY); }
    public boolean isOnline(String uuid)           { return Boolean.TRUE.equals(sync.sismember(ONLINE_PLAYERS_KEY, uuid)); }

    // ── generic set ops (e.g. tells_disabled) ──
    public void sadd(String key, String member)        { sync.sadd(key, member); }
    public void srem(String key, String member)        { sync.srem(key, member); }
    public boolean sismember(String key, String member){ return Boolean.TRUE.equals(sync.sismember(key, member)); }

    // ── leaderboards (sorted set) ──
    public void leaderboardAdd(String board, String member, double score) {
        sync.zadd("leaderboard:" + board, score, member);
    }
    public List<String> leaderboardTop(String board, int n) {
        return sync.zrevrange("leaderboard:" + board, 0, n - 1);
    }

    // ── pub/sub ──
    public void publish(String channel, String message) { sync.publish(channel, message); }

    /** Subscribe to a channel; {@code handler} receives (channel, message). */
    public void subscribe(String channel, BiConsumer<String, String> handler) {
        if (pubSub == null) {
            pubSub = lettuce.connectPubSub();
        }
        pubSub.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String ch, String msg) {
                handler.accept(ch, msg);
            }
        });
        pubSub.sync().subscribe(channel);
    }

    @Override
    public void close() {
        if (pubSub != null) {
            pubSub.close();
        }
        connection.close();
        lettuce.shutdown();
    }
}
