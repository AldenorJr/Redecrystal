package com.redecrystal.core.cargo;

import com.redecrystal.core.http.RemoteConfig;
import java.util.ArrayList;
import java.util.Comparator;
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
    @SuppressWarnings("unchecked")
    public static Cargo resolve(RemoteConfig chatConfig, Predicate<String> hasPermission) {
        if (chatConfig == null || !(chatConfig.value("roles") instanceof Map<?, ?> rolesMap)) {
            return null;
        }
        List<Cargo> roles = new ArrayList<>();
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
        }
        roles.sort(Comparator.comparingInt(Cargo::weight).reversed());
        for (Cargo c : roles) {
            if (hasPermission.test("tag." + c.id()) || hasPermission.test(permissionOf(rolesMap, c.id()))) {
                return c;
            }
        }
        return null;
    }

    private static String permissionOf(Map<?, ?> rolesMap, String id) {
        Object v = rolesMap.get(id);
        if (v instanceof Map<?, ?> m && m.get("permission") != null) {
            return String.valueOf(m.get("permission"));
        }
        return "tag." + id;
    }
}
