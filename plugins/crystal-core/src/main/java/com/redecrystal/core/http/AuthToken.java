package com.redecrystal.core.http;

/** A player session token issued by the backend auth API. */
public record AuthToken(
        String token,
        long expiresAtEpochSec,
        String uuid,
        String username,
        boolean premium) {
}
