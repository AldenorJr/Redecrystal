package com.redecrystal.profile.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redecrystal.profile.domain.ProfileEntity;
import com.redecrystal.profile.domain.ProfileRepository;
import com.redecrystal.shared.web.NotFoundException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profiles: PostgreSQL is the source of truth, Redis ({@code profile:{uuid}})
 * the read cache. Profiles are created on demand the first time a player is seen.
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);
    private static final String CACHE_PREFIX = "profile:";

    private final ProfileRepository repository;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ProfileService(ProfileRepository repository, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.repository = repository;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ProfileEntity get(UUID uuid) {
        return repository.findById(uuid)
                .orElseThrow(() -> new NotFoundException("profile not found: " + uuid));
    }

    /** Create the profile if absent, refresh the username, and return it. */
    @Transactional
    public ProfileEntity ensure(UUID uuid, String username) {
        ProfileEntity profile = repository.findById(uuid)
                .orElseGet(() -> new ProfileEntity(uuid, username));
        profile.touchUsername(username);
        profile = repository.save(profile);
        cache(profile);
        return profile;
    }

    /** Apply additive stat deltas and persist. */
    @Transactional
    public ProfileEntity addStats(UUID uuid, long coins, long experience, long playSeconds) {
        ProfileEntity profile = get(uuid);
        profile.addStats(coins, experience, playSeconds);
        profile = repository.save(profile);
        cache(profile);
        return profile;
    }

    private void cache(ProfileEntity p) {
        try {
            redis.opsForValue().set(CACHE_PREFIX + p.getPlayerUuid(),
                    objectMapper.writeValueAsString(java.util.Map.of(
                            "uuid", p.getPlayerUuid().toString(),
                            "username", p.getUsername() == null ? "" : p.getUsername(),
                            "rank", p.getRank(),
                            "level", p.getLevel(),
                            "experience", p.getExperience(),
                            "coins", p.getCoins(),
                            "playSeconds", p.getPlaySeconds())));
        } catch (Exception e) {
            log.warn("Failed to cache profile {}", p.getPlayerUuid(), e);
        }
    }
}
