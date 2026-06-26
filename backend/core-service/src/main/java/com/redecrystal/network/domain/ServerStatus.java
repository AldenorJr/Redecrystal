package com.redecrystal.network.domain;

/** Lifecycle states of a registered server instance. */
public enum ServerStatus {
    STARTING,
    ONLINE,
    DRAINING,
    OFFLINE
}
