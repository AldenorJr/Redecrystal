package com.redecrystal.network.domain;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServerRegistryRepository extends JpaRepository<ServerInstance, String> {

    List<ServerInstance> findByType(String type);

    List<ServerInstance> findByTypeAndStatus(String type, ServerStatus status);

    /** Servers in {@code status} whose last heartbeat predates {@code cutoff} (stale). */
    List<ServerInstance> findByStatusAndLastHeartbeatBefore(ServerStatus status, OffsetDateTime cutoff);
}
