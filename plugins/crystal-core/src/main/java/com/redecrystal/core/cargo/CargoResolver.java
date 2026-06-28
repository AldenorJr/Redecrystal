package com.redecrystal.core.cargo;

import com.redecrystal.core.http.RemoteConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Resolves a player's cargo (tag) from the centralized {@code chat} config —
 * the single source shared by chat, tab and profile. Pure JVM (no Bukkit): the
 * caller supplies a permission predicate (e.g. {@code player::hasPermission}),
 * so the highest-{@code weight} role whose permission the player holds wins.
 */
public final class CargoResolver {

    private CargoResolver() {
    }

    /** A resolved cargo. {@code prefix}/{@code nameColor} are MiniMessage strings. */
    public record Cargo(String id, String prefix, String nameColor, int weight) {
    }

    /**
     * @param chatConfig    the {@code chat} RemoteConfig (carries {@code roles})
     * @param hasPermission predicate over permission nodes
     * @return the highest-weight matching cargo, or {@code null} if none match
     */
    public static Cargo resolve(RemoteConfig chatConfig, Predicate<String> hasPermission) {
        return resolve(chatConfig, null, hasPermission);
    }

    /**
     * Same as {@link #resolve(RemoteConfig, Predicate)} but an {@code overrideId}
     * (admin "test" tag) wins when it names a defined cargo — regardless of the
     * player's permissions. A {@code null}/blank/unknown override falls back to the
     * permission-based resolution.
     */
    @SuppressWarnings("unchecked")
    public static Cargo resolve(RemoteConfig chatConfig, String overrideId, Predicate<String> hasPermission) {
        if (chatConfig == null || !(chatConfig.value("roles") instanceof Map<?, ?> rolesMap)) {
            return null;
        }
        List<Cargo> roles = new ArrayList<>();
        Map<String, String> permissions = new HashMap<>();
        for (Map.Entry<?, ?> entry : rolesMap.entrySet()) {
            String id = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> data)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) data;
            String permission = m.get("permission") == null ? "tag." + id : String.valueOf(m.get("permission"));
            int weight = m.get("weight") instanceof Number n ? n.intValue() : 0;
            String prefix = m.get("prefix") == null ? "" : String.valueOf(m.get("prefix"));
            String nameColor = m.get("nameColor") == null ? "" : String.valueOf(m.get("nameColor"));
            roles.add(new Cargo(id, prefix, nameColor, weight));
            permissions.put(id, permission);
        }
        // An admin-set override wins, if it names a defined cargo.
        if (overrideId != null && !overrideId.isBlank()) {
            for (Cargo c : roles) {
                if (c.id().equals(overrideId)) {
                    return c;
                }
            }
        }
        roles.sort(Comparator.comparingInt(Cargo::weight).reversed());
        for (Cargo c : roles) {
            if (hasPermission.test("tag." + c.id()) || hasPermission.test(permissions.get(c.id()))) {
                return c;
            }
        }
        return null;
    }
}
