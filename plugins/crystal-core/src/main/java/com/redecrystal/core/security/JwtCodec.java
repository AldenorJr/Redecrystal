package com.redecrystal.core.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.redecrystal.core.CrystalLogger;
import com.redecrystal.core.json.Json;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies player JWTs (HS256) with plain JDK crypto — the exact counterpart of
 * the backend {@code JwtService}. The proxy uses this to authorize cross-server
 * connections offline, with no shared library and no network round-trip.
 *
 * <p>Verification is signature + expiry only; session revocation (matching the
 * {@code sid} claim against Redis {@code jwt:{uuid}}) is the caller's job.
 */
public final class JwtCodec {

    private static final CrystalLogger log = CrystalLogger.of(JwtCodec.class);
    private static final String HMAC_ALG = "HmacSHA256";
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;

    public JwtCodec(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Verified contents of a player JWT. */
    public record Claims(UUID uuid, String username, String sessionId, boolean premium, long expiresAtEpochSec) {}

    /** Verify signature + expiry and return the claims, or empty if invalid. */
    public Optional<Claims> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        byte[] expected = hmac((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        if (!constantTimeEquals(expected, B64D.decode(parts[2]))) {
            return Optional.empty();
        }
        try {
            JsonNode claims = Json.MAPPER.readTree(B64D.decode(parts[1]));
            long exp = claims.path("exp").asLong();
            if (Instant.now().getEpochSecond() >= exp) {
                return Optional.empty();
            }
            return Optional.of(new Claims(
                    UUID.fromString(claims.path("sub").asText()),
                    claims.path("name").asText(null),
                    claims.path("sid").asText(null),
                    claims.path("prem").asBoolean(false),
                    exp));
        } catch (Exception e) {
            log.warn("Rejected malformed JWT: {}", e.toString());
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
