package com.redecrystal.core.messaging;

import com.redecrystal.core.CrystalLogger;
import com.redecrystal.core.json.Json;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Kafka producer + a single background consumer. Produces {@link EventEnvelope}s
 * as JSON; consumes them and forwards each to a handler (typically the EventBus).
 *
 * <p>Each instance uses a stable, unique group id so it receives a broadcast copy
 * of every event (every server hot-reloads config, sees player events, etc.).
 */
public final class KafkaClient implements AutoCloseable {

    private static final CrystalLogger log = CrystalLogger.of(KafkaClient.class);

    private final String brokers;
    private final String sourceServerId;
    /** Unique per SDK instance so every plugin gets a broadcast copy of all events. */
    private final String groupId;
    private final KafkaProducer<String, String> producer;

    private volatile boolean running;
    private Thread consumerThread;
    private KafkaConsumer<String, String> consumer;

    public KafkaClient(String brokers, String sourceServerId) {
        this.brokers = brokers;
        this.sourceServerId = sourceServerId;
        // Unique per instance → broadcast delivery, and avoids two plugins on the
        // same server (e.g. lobby + chat) sharing a group and splitting partitions.
        this.groupId = "crystal-" + sourceServerId + "-" + UUID.randomUUID();
        // Kafka loads classes via the thread context classloader, which in a
        // Minecraft plugin is NOT this (shaded) jar's loader. Swap it during
        // construction so the relocated client classes resolve.
        this.producer = withSdkClassLoader(() -> new KafkaProducer<>(producerProps()));
    }

    /** Publish an event envelope to {@code topic}, keyed by {@code key}. */
    public void publish(String topic, String key, Map<String, Object> payload) {
        var envelope = new EventEnvelope(
                UUID.randomUUID().toString(), topic, OffsetDateTime.now(),
                sourceServerId, EventEnvelope.CURRENT_SCHEMA_VERSION, payload);
        try {
            String json = Json.MAPPER.writeValueAsString(envelope);
            producer.send(new ProducerRecord<>(topic, key, json), (md, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish to " + topic, ex);
                }
            });
        } catch (Exception e) {
            log.error("Failed to serialize event for " + topic, e);
        }
    }

    /**
     * Start the background consumer subscribing to {@code topics}; each decoded
     * envelope is passed to {@code handler}. Idempotent (no-op if already running).
     */
    public synchronized void startConsumer(List<String> topics, Consumer<EventEnvelope> handler) {
        if (running) {
            return;
        }
        this.consumer = withSdkClassLoader(() -> new KafkaConsumer<>(consumerProps()));
        this.consumer.subscribe(topics);
        this.running = true;
        this.consumerThread = new Thread(() -> runLoop(handler), "crystal-kafka-consumer");
        this.consumerThread.setDaemon(true);
        this.consumerThread.start();
        log.info("Kafka consumer started for {} (topics={})", sourceServerId, topics.size());
    }

    private void runLoop(Consumer<EventEnvelope> handler) {
        // poll() may lazily load relocated classes — pin the SDK classloader.
        Thread.currentThread().setContextClassLoader(KafkaClient.class.getClassLoader());
        try {
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        handler.accept(Json.MAPPER.readValue(record.value(), EventEnvelope.class));
                    } catch (Exception e) {
                        log.error("Failed to decode event from " + record.topic(), e);
                    }
                }
            }
        } catch (WakeupException ignored) {
            // expected on shutdown
        } finally {
            consumer.close();
        }
    }

    @Override
    public void close() {
        running = false;
        if (consumer != null) {
            consumer.wakeup();
        }
        producer.close(Duration.ofSeconds(5));
    }

    private Properties producerProps() {
        var p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        // Pass Class objects (not names) so Kafka doesn't Class.forName them.
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        return p;
    }

    private Properties consumerProps() {
        var p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        return p;
    }

    /** Run an action with the SDK's classloader as the thread context loader. */
    private static <T> T withSdkClassLoader(Supplier<T> action) {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(KafkaClient.class.getClassLoader());
        try {
            return action.get();
        } finally {
            current.setContextClassLoader(previous);
        }
    }
}
