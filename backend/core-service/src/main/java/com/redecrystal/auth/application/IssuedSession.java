package com.redecrystal.auth.application;

import java.util.UUID;

/** Result of a successful register/login/refresh: the account plus its fresh token. */
public record IssuedSession(UUID uuid, String username, boolean premium,
                            String token, long expiresAtEpochSec) {}
