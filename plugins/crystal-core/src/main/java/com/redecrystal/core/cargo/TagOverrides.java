package com.redecrystal.core.cargo;

import com.redecrystal.core.redis.RedisClient;
import java.util.UUID;

/**
 * Per-player tag override (the admin "test" tag), stored in the Redis hash
 * {@value #KEY}: field = player UUID, value = cargo id. When present, the override
 * wins over the permission-based cargo everywhere the tag is shown (nametag, tab,
 * chat, sidebar, profile). Reads fail open: a Redis blip means "no override".
 */
public final class TagOverrides {

    public static final String KEY = "tag:overrides";

    private TagOverrides() {
    }

    /** The override cargo id for a player, or {@code null} if none / on failure. */
    public static String read(RedisClient redis, UUID uuid) {
        try {
            return redis.hget(KEY, uuid.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public static void set(RedisClient redis, UUID uuid, String cargoId) {
        redis.hset(KEY, uuid.toString(), cargoId);
    }

    public static void clear(RedisClient redis, UUID uuid) {
        redis.hdel(KEY, uuid.toString());
    }
}
