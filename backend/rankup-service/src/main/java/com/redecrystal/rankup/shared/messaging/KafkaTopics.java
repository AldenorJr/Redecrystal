package com.redecrystal.rankup.shared.messaging;

/** Canonical Kafka topic names for the RankUP service. Mirror of create-topics.sh. */
public final class KafkaTopics {

    public static final String MONEY_UPDATED = "money-updated";
    public static final String TOKEN_UPDATED = "token-updated";

    private KafkaTopics() {}
}
