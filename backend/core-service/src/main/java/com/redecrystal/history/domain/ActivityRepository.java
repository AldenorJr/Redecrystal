package com.redecrystal.history.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<ActivityEntity, Long> {

    List<ActivityEntity> findByPlayerUuidOrderByCreatedAtDesc(UUID playerUuid, Pageable pageable);
}
