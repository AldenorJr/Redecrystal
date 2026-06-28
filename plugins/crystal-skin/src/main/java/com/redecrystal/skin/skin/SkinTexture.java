package com.redecrystal.skin.skin;

/**
 * A signed Mojang skin texture, captured for later re-use. {@code name}/{@code uuid}
 * are the original owner whose skin was fetched; {@code value}/{@code signature} are
 * the Base64 "textures" property + its Mojang signature (signature may be null in
 * unsigned/offline lookups). {@code appliedAt} is the epoch-millis of last use, used
 * to order and de-duplicate the history.
 */
public record SkinTexture(String name, String uuid, String value, String signature, long appliedAt) {

    /** A copy stamped with a new "last used" time (kept immutable). */
    public SkinTexture usedAt(long when) {
        return new SkinTexture(name, uuid, value, signature, when);
    }
}
