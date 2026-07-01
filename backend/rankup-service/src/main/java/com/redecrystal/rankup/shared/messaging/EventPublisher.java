package com.redecrystal.rankup.shared.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link EventEnvelope}s as JSON to Kafka. Backend-originated events
 * use {@code sourceServerId = "backend"}. The message key is the supplied
 * partition key (e.g. player uuid) so related events stay ordered.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private static final String SOURCE = "backend";

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    /** Publish an event to {@code topic} keyed by {@code key}. */
    public void publish(String topic, String key, Map<String, Object> payload) {
        var envelope = new EventEnvelope(
                UUID.randomUUID().toString(),
                topic,
                OffsetDateTime.now(),
                SOURCE,
                EventEnvelope.CURRENT_SCHEMA_VERSION,
                payload);
        try {
            String json = objectMapper.writeValueAsString(envelope);
            kafka.send(topic, key, json);
            log.debug("Published event {} to {} (key={})", envelope.eventId(), topic, key);
        } catch (Exception e) {
            // Never let a telemetry/event failure break the business operation.
            log.error("Failed to publish event to topic {} (key={})", topic, key, e);
        }
    }
}
