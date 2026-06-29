package com.redecrystal.core.cargo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.redecrystal.core.cargo.CargoResolver.Cargo;
import com.redecrystal.core.http.RemoteConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class CargoResolverTest {

    private static RemoteConfig chatConfig() {
        Map<String, Object> vip = new LinkedHashMap<>();
        vip.put("permission", "tag.vip");
        vip.put("weight", 10);
        vip.put("prefix", "<gold>[VIP]");
        vip.put("nameColor", "<gold>");
        Map<String, Object> ceo = new LinkedHashMap<>();
        ceo.put("permission", "tag.ceo");
        ceo.put("weight", 100);
        ceo.put("prefix", "<red>[CEO]");
        ceo.put("nameColor", "<red>");
        Map<String, Object> roles = new LinkedHashMap<>();
        roles.put("vip", vip);
        roles.put("ceo", ceo);
        return new RemoteConfig("chat", 1, Map.of("roles", roles));
    }

    private static Predicate<String> has(String... perms) {
        Set<String> set = Set.of(perms);
        return set::contains;
    }

    @Test
    void resolvesHighestWeightByPermission() {
        Cargo c = CargoResolver.resolve(chatConfig(), has("tag.vip", "tag.ceo"));
        assertEquals("ceo", c.id());
    }

    @Test
    void noPermissionResolvesToNull() {
        assertNull(CargoResolver.resolve(chatConfig(), has()));
    }

    @Test
    void overrideWinsEvenWithoutPermission() {
        Cargo c = CargoResolver.resolve(chatConfig(), "vip", has());
        assertEquals("vip", c.id());
        assertEquals("<gold>[VIP]", c.prefix());
    }

    @Test
    void unknownOverrideFallsBackToPermission() {
        Cargo c = CargoResolver.resolve(chatConfig(), "ghost", has("tag.vip"));
        assertEquals("vip", c.id());
    }

    @Test
    void blankOverrideFallsBackToPermission() {
        Cargo c = CargoResolver.resolve(chatConfig(), "", has("tag.vip"));
        assertEquals("vip", c.id());
    }
}
