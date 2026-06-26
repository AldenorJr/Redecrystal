package com.redecrystal.core.http;

/** A leaderboard row: rank (1-based), username, time in ms. */
public record ParkourEntry(long rank, String username, long timeMs) {}
