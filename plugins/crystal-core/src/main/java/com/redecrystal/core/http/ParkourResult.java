package com.redecrystal.core.http;

/** Result of submitting a parkour time. */
public record ParkourResult(long bestTimeMs, boolean record, long rank) {}
