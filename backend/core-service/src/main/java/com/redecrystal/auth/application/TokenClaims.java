package com.redecrystal.auth.application;

import java.util.UUID;

/** Verified contents of a player JWT. */
public record TokenClaims(UUID uuid, String username, String sessionId, boolean premium, long expiresAtEpochSec) {}
