package com.redecrystal.shared.messaging;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Versioned event envelope — the shared contract every RedeCrystal event rides
 * in, on every topic. {@code schemaVersion} is present from day one so payloads
 * can evolve without breaking consumers.
 *
 * @param eventId        unique id (UUID string)
 * @param eventType      logical type, usually the topic name
 * @param occurredAt     when the event happened (UTC offset)
 * @param sourceServerId emitter id ("backend" for backend-originated events)
 * @param schemaVersion  envelope/payload schema version
 * @param payload        event-specific data
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        OffsetDateTime occurredAt,
        String sourceServerId,
        int schemaVersion,
        Map<String, Object> payload) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
