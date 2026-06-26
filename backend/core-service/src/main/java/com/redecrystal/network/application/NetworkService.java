package com.redecrystal.network.application;

import com.redecrystal.network.domain.ServerInstance;
import com.redecrystal.network.domain.ServerRegistryRepository;
import com.redecrystal.network.domain.ServerStatus;
import com.redecrystal.shared.messaging.EventPublisher;
import com.redecrystal.shared.messaging.KafkaTopics;
import com.redecrystal.shared.web.NotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service Discovery. Instances register on startup, heartbeat periodically, and
 * deregister on shutdown. Registration/deregistration emit {@code server-started}
 * / {@code server-stopped} events so the rest of the network reacts in real time.
 */
@Service
public class NetworkService {

    private static final Logger log = LoggerFactory.getLogger(NetworkService.class);

    private final ServerRegistryRepository repository;
    private final EventPublisher events;
    private final com.redecrystal.network.metrics.NetworkMetrics metrics;

    public NetworkService(ServerRegistryRepository repository, EventPublisher events,
                          com.redecrystal.network.metrics.NetworkMetrics metrics) {
        this.repository = repository;
        this.events = events;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public List<ServerInstance> list(String type) {
        return type == null ? repository.findAll() : repository.findByType(type);
    }

    @Transactional(readOnly = true)
    public ServerInstance get(String serverId) {
        return repository.findById(serverId)
                .orElseThrow(() -> new NotFoundException("server not registered: " + serverId));
    }

    @Transactional
    public ServerInstance register(String serverId, String type, String host, int port, int maxPlayers) {
        ServerInstance instance = repository.findById(serverId)
                .map(existing -> {
                    existing.reregister(type, host, port, maxPlayers);
                    return existing;
                })
                .orElseGet(() -> new ServerInstance(serverId, type, host, port, maxPlayers));
        instance = repository.save(instance);

        events.publish(KafkaTopics.SERVER_STARTED, serverId, Map.of(
                "serverId", serverId, "type", type, "host", host, "port", port));
        log.info("Server registered: {} ({} at {}:{})", serverId, type, host, port);
        return instance;
    }

    @Transactional
    public ServerInstance heartbeat(String serverId, int onlinePlayers, ServerStatus status,
                                    Double tps, Double memoryMb, Double cpuLoad) {
        ServerInstance instance = get(serverId);
        instance.heartbeat(onlinePlayers, status);
        ServerInstance saved = repository.save(instance);
        metrics.update(serverId, onlinePlayers, tps, memoryMb, cpuLoad);
        return saved;
    }

    @Transactional
    public void deregister(String serverId) {
        ServerInstance instance = get(serverId);
        instance.markOffline();
        repository.save(instance);
        events.publish(KafkaTopics.SERVER_STOPPED, serverId, Map.of(
                "serverId", serverId, "type", instance.getType()));
        log.info("Server deregistered: {}", serverId);
    }

    /**
     * Crash detection. Instances heartbeat every ~10s; if one goes silent past
     * the timeout (e.g. it was killed, not gracefully stopped), mark it OFFLINE
     * and emit {@code server-stopped} so the proxy stops routing players to it.
     */
    @Scheduled(fixedDelayString = "${redecrystal.network.reaper-interval-ms:10000}",
               initialDelayString = "${redecrystal.network.reaper-interval-ms:10000}")
    @Transactional
    public void reapStaleServers() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(30);
        for (ServerInstance stale : repository.findByStatusAndLastHeartbeatBefore(ServerStatus.ONLINE, cutoff)) {
            stale.markOffline();
            repository.save(stale);
            events.publish(KafkaTopics.SERVER_STOPPED, stale.getServerId(), Map.of(
                    "serverId", stale.getServerId(), "type", stale.getType(), "reason", "heartbeat-timeout"));
            log.warn("Reaped {} (stale heartbeat → OFFLINE)", stale.getServerId());
        }
    }
}
