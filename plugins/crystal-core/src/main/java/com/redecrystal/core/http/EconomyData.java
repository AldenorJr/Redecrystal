package com.redecrystal.core.http;

/** A player's RankUP balance as served by the economy API. */
public record EconomyData(String uuid, long money, long tokens, int version) {
}
