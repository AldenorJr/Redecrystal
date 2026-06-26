package com.redecrystal.core.messaging;

import java.util.List;

/** Canonical Kafka topic names — mirrors the backend and create-topics.sh. */
public final class KafkaTopics {

    public static final String PLAYER_CONNECTED      = "player-connected";
    public static final String PLAYER_DISCONNECTED   = "player-disconnected";
    public static final String PLAYER_AUTHENTICATED  = "player-authenticated";
    public static final String PLAYER_CHAT           = "player-chat";
    public static final String SERVER_STARTED        = "server-started";
    public static final String SERVER_STOPPED        = "server-stopped";
    public static final String CONFIG_UPDATED        = "config-updated";
    public static final String MAINTENANCE_ENABLED   = "maintenance-enabled";
    public static final String MAINTENANCE_DISABLED  = "maintenance-disabled";

    /** All topics, for broadcast-style consumers. */
    public static final List<String> ALL = List.of(
            PLAYER_CONNECTED, PLAYER_DISCONNECTED, PLAYER_AUTHENTICATED, PLAYER_CHAT,
            SERVER_STARTED, SERVER_STOPPED,
            CONFIG_UPDATED, MAINTENANCE_ENABLED, MAINTENANCE_DISABLED);

    private KafkaTopics() {}
}
