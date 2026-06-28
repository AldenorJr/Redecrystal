package com.redecrystal.core.http;

/** Whether a player already has an account, and if it is premium. */
public record AccountStatus(boolean registered, boolean premium) {
}
