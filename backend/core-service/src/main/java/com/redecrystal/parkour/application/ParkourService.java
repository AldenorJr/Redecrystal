package com.redecrystal.parkour.application;

import com.redecrystal.parkour.domain.ParkourTime;
import com.redecrystal.parkour.domain.ParkourTimeRepository;
import com.redecrystal.shared.web.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parkour leaderboard. PostgreSQL holds each player's best time; Redis
 * ({@code leaderboard:parkour}, score = best time ms) gives O(log n) ranking.
 * A submission only counts if it beats the stored best (lower is better).
 */
@Service
public class ParkourService {

    private static final String LEADERBOARD = "leaderboard:parkour";

    private final ParkourTimeRepository repository;
    private final StringRedisTemplate redis;

    public ParkourService(ParkourTimeRepository repository, StringRedisTemplate redis) {
        this.repository = repository;
        this.redis = redis;
    }

    public record Result(long bestTimeMs, boolean record, long rank) {}
    public record Entry(long rank, String username, long timeMs) {}

    @Transactional
    public Result submit(UUID uuid, String username, long timeMs) {
        ParkourTime existing = repository.findById(uuid).orElse(null);
        boolean isRecord = existing == null || timeMs < existing.getBestTimeMs();
        if (isRecord) {
            if (existing == null) {
                existing = new ParkourTime(uuid, username, timeMs);
            } else {
                existing.improve(username, timeMs);
            }
            repository.save(existing);
            redis.opsForZSet().add(LEADERBOARD, uuid.toString(), timeMs);
        }
        long best = existing.getBestTimeMs();
        Long zrank = redis.opsForZSet().rank(LEADERBOARD, uuid.toString());
        long rank = zrank == null ? -1 : zrank + 1;
        return new Result(best, isRecord, rank);
    }

    @Transactional(readOnly = true)
    public List<Entry> top(int limit) {
        List<ParkourTime> rows = repository.findAllByOrderByBestTimeMsAsc(PageRequest.of(0, limit));
        return java.util.stream.IntStream.range(0, rows.size())
                .mapToObj(i -> new Entry(i + 1L, rows.get(i).getUsername(), rows.get(i).getBestTimeMs()))
                .toList();
    }

    @Transactional(readOnly = true)
    public long best(UUID uuid) {
        return repository.findById(uuid)
                .map(ParkourTime::getBestTimeMs)
                .orElseThrow(() -> new NotFoundException("no parkour time for " + uuid));
    }
}
