package com.redecrystal.auth.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerAccountRepository extends JpaRepository<PlayerAccount, UUID> {

    Optional<PlayerAccount> findByUsernameLower(String usernameLower);
}
