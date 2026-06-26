package com.redecrystal.core.event;

import com.redecrystal.core.CrystalLogger;
import com.redecrystal.core.messaging.EventEnvelope;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process event system. Kafka-delivered {@link EventEnvelope}s are dispatched
 * to handlers registered per event type, turning distributed events into typed
 * local callbacks. This is the Event-Driven Architecture entry point for plugins.
 */
public final class EventBus {

    private static final CrystalLogger log = CrystalLogger.of(EventBus.class);

    private final Map<String, List<Consumer<EventEnvelope>>> handlers = new ConcurrentHashMap<>();

    /** Register a handler for events of {@code eventType} (usually a topic name). */
    public void on(String eventType, Consumer<EventEnvelope> handler) {
        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /** Dispatch an event to all matching handlers; a failing handler is isolated. */
    public void dispatch(EventEnvelope event) {
        List<Consumer<EventEnvelope>> list = handlers.get(event.eventType());
        if (list == null) {
            return;
        }
        for (Consumer<EventEnvelope> handler : list) {
            try {
                handler.accept(event);
            } catch (Exception e) {
                log.error("Handler for " + event.eventType() + " failed", e);
            }
        }
    }
}
