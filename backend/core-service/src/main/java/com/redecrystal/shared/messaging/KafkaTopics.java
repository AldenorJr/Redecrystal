package com.redecrystal.shared.messaging;

/** Canonical Kafka topic names. Mirror of infra/kafka/create-topics.sh. */
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

    private KafkaTopics() {}
}
