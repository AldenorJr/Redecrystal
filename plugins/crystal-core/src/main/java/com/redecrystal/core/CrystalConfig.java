package com.redecrystal.core;

/**
 * Bootstrap configuration — the ONLY values a plugin reads locally. Everything
 * else (motd, maxPlayers, maintenance, feature flags) comes from the Config
 * Service via the gateway. These are injected by Docker/Kubernetes as env vars.
 *
 * <p>Resolves the "no local config" tension explicitly: a plugin needs just
 * enough to reach the backend; all real configuration is fetched remotely.
 *
 * @param backendUrl    API Gateway base URL (plugins never call services directly)
 * @param serviceToken  bearer token presented to the gateway
 * @param redisHost     Redis host
 * @param redisPort     Redis port
 * @param kafkaBrokers  Kafka bootstrap servers
 * @param serverId      this instance's unique id (e.g. "lobby-01")
 * @param serverType    instance type ("proxy" | "login" | "lobby" | …)
 * @param serverHost    host other components reach this instance at
 * @param serverPort    port this instance listens on
 */
public record CrystalConfig(
        String backendUrl,
        String serviceToken,
        String redisHost,
        int redisPort,
        String kafkaBrokers,
        String serverId,
        String serverType,
        String serverHost,
        int serverPort) {

    /** Build from environment variables, with dev-friendly defaults. */
    public static CrystalConfig fromEnv() {
        return new CrystalConfig(
                env("BACKEND_URL", "http://localhost:8080"),
                env("BACKEND_SERVICE_TOKEN", "change-me-dev-service-token"),
                env("REDIS_HOST", "localhost"),
                Integer.parseInt(env("REDIS_PORT", "6379")),
                env("KAFKA_BROKERS", "localhost:29092"),
                env("SERVER_ID", "unknown"),
                env("SERVER_TYPE", "unknown"),
                env("SERVER_HOST", "localhost"),
                Integer.parseInt(env("SERVER_PORT", "25565")));
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
