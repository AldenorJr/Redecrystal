package com.redecrystal.parkour.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkourTimeRepository extends JpaRepository<ParkourTime, UUID> {

    /** Best times ascending (fastest first) for the leaderboard. */
    List<ParkourTime> findAllByOrderByBestTimeMsAsc(Pageable pageable);
}
