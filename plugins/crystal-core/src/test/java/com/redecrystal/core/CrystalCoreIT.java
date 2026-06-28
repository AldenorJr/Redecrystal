package com.redecrystal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redecrystal.core.http.RemoteConfig;
import com.redecrystal.core.messaging.KafkaTopics;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end SDK test against the running docker-compose stack (host endpoints).
 * Skipped unless CRYSTAL_IT=1 so normal `mvn package` builds stay hermetic.
 *
 *   CRYSTAL_IT=1 mvn -pl plugins/crystal-core test
 */
@EnabledIfEnvironmentVariable(named = "CRYSTAL_IT", matches = "1")
class CrystalCoreIT {

    private static CrystalConfig hostConfig() {
        return new CrystalConfig(
                "http://localhost:8080", "change-me-dev-service-token",
                "localhost", 6379, "localhost:29092",
                "sdk-it-01", "lobby", "localhost", 25565, "change-me-dev-jwt-secret");
    }

    @Test
    void fullSdkFlowAgainstLiveStack() throws Exception {
        try (CrystalCore crystal = CrystalCore.bootstrap(hostConfig())) {

            // 1) Config fetch via gateway
            RemoteConfig lobby = crystal.configProvider().get("lobby");
            System.out.println("[IT] lobby config = " + lobby.config());
            assertTrue(lobby.integer("maxPlayers", 0) > 0, "maxPlayers should be configured");

            // 2) Service Discovery: register + heartbeat (no exception = success)
            crystal.registerThisServer(100);
            crystal.backend().heartbeat("sdk-it-01", 7, "ONLINE");

            // Give the Kafka consumer time to be assigned before we publish.
            TimeUnit.SECONDS.sleep(5);

            // 3) Kafka event round-trip through the EventBus
            CountDownLatch eventLatch = new CountDownLatch(1);
            AtomicReference<String> got = new AtomicReference<>();
            crystal.events().on(KafkaTopics.PLAYER_CONNECTED, e -> {
                got.set(e.get("player"));
                eventLatch.countDown();
            });
            crystal.kafka().publish(KafkaTopics.PLAYER_CONNECTED, "p1",
                    Map.of("player", "Steve", "server", "sdk-it-01"));
            assertTrue(eventLatch.await(15, TimeUnit.SECONDS), "should receive published event");
            assertEquals("Steve", got.get());
            System.out.println("[IT] event round-trip OK");

            // 4) Hot reload: backend PUT triggers config-updated → cache refresh + listener
            String marker = "motd-" + System.nanoTime();
            CountDownLatch cfgLatch = new CountDownLatch(1);
            crystal.configProvider().onChange("lobby", c -> {
                if (marker.equals(c.string("motd", ""))) {
                    cfgLatch.countDown();
                }
            });
            crystal.backend().putConfig("lobby", Map.of(
                    "server", "lobby", "maxPlayers", 500, "maintenance", false, "motd", marker));
            assertTrue(cfgLatch.await(15, TimeUnit.SECONDS), "should hot-reload config");
            assertEquals(marker, crystal.configProvider().get("lobby").string("motd", ""));
            System.out.println("[IT] hot reload OK");

            // restore lobby config
            crystal.backend().putConfig("lobby", Map.of(
                    "server", "lobby", "maxPlayers", 500, "maintenance", false, "motd", "RedeCrystal"));

            // 5) Redis: session + online players
            crystal.redis().set("session:it-test", "value-1");
            assertEquals("value-1", crystal.redis().get("session:it-test"));
            crystal.redis().addOnlinePlayer("uuid-it-1");
            assertTrue(crystal.redis().onlinePlayers().contains("uuid-it-1"));
            crystal.redis().del("session:it-test");
            crystal.redis().removeOnlinePlayer("uuid-it-1");
            System.out.println("[IT] redis OK");
        }
    }
}
