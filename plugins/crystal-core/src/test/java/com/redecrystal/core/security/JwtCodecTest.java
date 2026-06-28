package com.redecrystal.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redecrystal.core.json.Json;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Verifies the proxy-side codec against tokens minted with the exact scheme the
 * backend {@code JwtService} uses (HS256, base64url, compact JSON) — i.e. proves
 * cross-module interop without depending on the backend module.
 */
class JwtCodecTest {

    private static final String SECRET = "shared-secret-between-backend-and-proxy";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    /** Mirror of JwtService.issue() — independently signs a token for the test. */
    private static String mint(String secret, UUID uuid, String name, boolean premium, long exp) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "redecrystal-auth");
        claims.put("sub", uuid.toString());
        claims.put("name", name);
        claims.put("sid", UUID.randomUUID().toString());
        claims.put("prem", premium);
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("exp", exp);
        try {
            String header = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String body = B64.encodeToString(Json.MAPPER.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
            String input = header + "." + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = B64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
            return input + "." + sig;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void verifiesBackendStyleToken() {
        UUID uuid = UUID.randomUUID();
        String token = mint(SECRET, uuid, "Steve", true, Instant.now().getEpochSecond() + 3600);

        JwtCodec.Claims claims = new JwtCodec(SECRET).verify(token).orElseThrow();
        assertEquals(uuid, claims.uuid());
        assertEquals("Steve", claims.username());
        assertTrue(claims.premium());
    }

    @Test
    void rejectsWrongSecret() {
        String token = mint(SECRET, UUID.randomUUID(), "Alex", false, Instant.now().getEpochSecond() + 3600);
        assertTrue(new JwtCodec("a-different-secret").verify(token).isEmpty());
    }

    @Test
    void rejectsExpired() {
        String token = mint(SECRET, UUID.randomUUID(), "Notch", true, Instant.now().getEpochSecond() - 1);
        assertTrue(new JwtCodec(SECRET).verify(token).isEmpty());
    }

    @Test
    void rejectsGarbage() {
        JwtCodec codec = new JwtCodec(SECRET);
        assertTrue(codec.verify(null).isEmpty());
        assertTrue(codec.verify("").isEmpty());
        assertTrue(codec.verify("nope").isEmpty());
    }
}
