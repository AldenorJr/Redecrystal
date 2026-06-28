package com.redecrystal.auth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies player JWTs (HS256). Implemented with plain JDK crypto so
 * the exact same compact-JSON + base64url + HMAC-SHA256 scheme can be re-verified
 * by the proxy plugin ({@code crystal-core} JwtCodec) with zero shared library.
 *
 * <p>The token is the player's proof of authentication carried across servers;
 * the {@code sid} claim is mirrored in Redis ({@code jwt:{uuid}}) so a session can
 * be revoked without waiting for the token to expire.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String ISSUER = "redecrystal-auth";
    private static final String HMAC_ALG = "HmacSHA256";
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long ttlSeconds;

    public JwtService(ObjectMapper objectMapper,
                      @Value("${redecrystal.security.jwt-secret}") String jwtSecret,
                      @Value("${redecrystal.security.jwt-ttl-seconds:21600}") long ttlSeconds) {
        this.objectMapper = objectMapper;
        this.secret = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    /** A freshly minted token plus the data needed to mirror it into Redis. */
    public record IssuedToken(String token, String sessionId, long expiresAtEpochSec) {}

    /** Sign a new token for the authenticated player. */
    public IssuedToken issue(UUID uuid, String username, boolean premium) {
        long now = Instant.now().getEpochSecond();
        long exp = now + ttlSeconds;
        String sid = UUID.randomUUID().toString();

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("sub", uuid.toString());
        claims.put("name", username);
        claims.put("sid", sid);
        claims.put("prem", premium);
        claims.put("iat", now);
        claims.put("exp", exp);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(claims);
        } catch (Exception e) {
            // Should never happen for a plain map; surface rather than issue a bad token.
            throw new IllegalStateException("failed to serialize JWT claims", e);
        }

        String header = B64.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
        String body = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + body;
        String signature = B64.encodeToString(hmac(signingInput.getBytes(StandardCharsets.UTF_8)));
        return new IssuedToken(signingInput + "." + signature, sid, exp);
    }

    /** Verify signature + expiry and return the claims, or empty if invalid. */
    public Optional<TokenClaims> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = hmac(signingInput.getBytes(StandardCharsets.UTF_8));
        if (!constantTimeEquals(expected, B64D.decode(parts[2]))) {
            return Optional.empty();
        }
        try {
            byte[] payload = B64D.decode(parts[1]);
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
            long exp = ((Number) claims.getOrDefault("exp", 0L)).longValue();
            if (Instant.now().getEpochSecond() >= exp) {
                return Optional.empty();
            }
            return Optional.of(new TokenClaims(
                    UUID.fromString((String) claims.get("sub")),
                    (String) claims.get("name"),
                    (String) claims.get("sid"),
                    Boolean.TRUE.equals(claims.get("prem")),
                    exp));
        } catch (Exception e) {
            log.debug("Rejected malformed JWT", e);
            return Optional.empty();
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret, HMAC_ALG));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
