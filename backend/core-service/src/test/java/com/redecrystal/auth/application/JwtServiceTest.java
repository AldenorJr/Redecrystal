package com.redecrystal.auth.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "test-secret-do-not-use-in-prod";

    private JwtService service(long ttlSeconds) {
        return new JwtService(new ObjectMapper(), SECRET, ttlSeconds);
    }

    @Test
    void issuedTokenRoundTrips() {
        JwtService jwt = service(3600);
        UUID uuid = UUID.randomUUID();

        JwtService.IssuedToken issued = jwt.issue(uuid, "Steve", true);
        TokenClaims claims = jwt.verify(issued.token()).orElseThrow();

        assertEquals(uuid, claims.uuid());
        assertEquals("Steve", claims.username());
        assertEquals(issued.sessionId(), claims.sessionId());
        assertTrue(claims.premium());
    }

    @Test
    void rejectsTamperedSignature() {
        JwtService jwt = service(3600);
        String token = jwt.issue(UUID.randomUUID(), "Alex", false).token();

        // Flip the last character of the signature segment.
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        assertTrue(jwt.verify(tampered).isEmpty());
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        String token = service(3600).issue(UUID.randomUUID(), "Notch", true).token();
        JwtService other = new JwtService(new ObjectMapper(), "different-secret", 3600);
        assertTrue(other.verify(token).isEmpty());
    }

    @Test
    void rejectsExpiredToken() {
        // Already expired (negative TTL → exp in the past).
        String token = service(-10).issue(UUID.randomUUID(), "Herobrine", false).token();
        assertTrue(service(3600).verify(token).isEmpty());
    }

    @Test
    void rejectsGarbage() {
        JwtService jwt = service(3600);
        assertTrue(jwt.verify(null).isEmpty());
        assertTrue(jwt.verify("").isEmpty());
        assertTrue(jwt.verify("not.a.jwt.token").isEmpty());
        assertFalse(jwt.verify(jwt.issue(UUID.randomUUID(), "Ok", true).token()).isEmpty());
    }
}
