package com.redecrystal.core.http;

/** A player profile as served by the backend profile API. */
public record ProfileData(
        String uuid,
        String username,
        String rank,
        int level,
        long experience,
        long coins,
        long playSeconds,
        long kills,
        long deaths,
        long messagesSent,
        String createdAt) {
}
