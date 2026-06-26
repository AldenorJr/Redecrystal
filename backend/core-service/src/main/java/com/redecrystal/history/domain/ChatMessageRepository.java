package com.redecrystal.history.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    List<ChatMessageEntity> findByPlayerUuidOrderByCreatedAtDesc(UUID playerUuid, Pageable pageable);
}
