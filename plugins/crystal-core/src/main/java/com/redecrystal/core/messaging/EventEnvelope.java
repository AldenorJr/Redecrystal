package com.redecrystal.core.messaging;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Versioned event envelope — identical shape to the backend's, so events
 * round-trip as JSON between the backend and every plugin.
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        OffsetDateTime occurredAt,
        String sourceServerId,
        int schemaVersion,
        Map<String, Object> payload) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Convenience accessor for a payload field. */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return payload == null ? null : (T) payload.get(key);
    }
}
