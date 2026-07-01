package com.redecrystal.core.http;

/**
 * Typed facade over {@link BackendHttpClient} for the RankUP economy. First
 * per-feature client in the SDK (see rankup master decision #6). Every call is a
 * single HTTP round-trip — the caller runs it off the main thread.
 */
public final class EconomyClient {

    /** Redis hash holding a player's hot balance ({money,tokens,version}). */
    public static final String KEY_PREFIX = "economy:";
    /** Sorted-set leaderboard name (used with RedisClient.leaderboardAdd/Top). */
    public static final String MONEY_LEADERBOARD = "money";

    private final BackendHttpClient backend;

    public EconomyClient(BackendHttpClient backend) {
        this.backend = backend;
    }

    /** Balance, or {@code null} if the player has no row yet (treat as zeroed). */
    public EconomyData get(String uuid)                        { return backend.getEconomy(uuid); }
    public EconomyData ensure(String uuid)                     { return backend.ensureEconomy(uuid); }
    public EconomyData addMoney(String uuid, long d, String s) { return backend.addMoney(uuid, d, s); }
    public EconomyData addTokens(String uuid, long d, String s){ return backend.addTokens(uuid, d, s); }
    public EconomyData debit(String uuid, long cost, String r) { return backend.debitMoney(uuid, cost, r); }
    public EconomyData transfer(String from, String to, long a){ return backend.transfer(from, to, a); }
    public EconomyData set(String uuid, long m, long t, int v) { return backend.setEconomy(uuid, m, t, v); }

    public static String cacheKey(String uuid) { return KEY_PREFIX + uuid; }
}
