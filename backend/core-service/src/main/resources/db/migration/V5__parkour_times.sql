-- Best parkour time per player (lobby parkour). Redis leaderboard:parkour (ZSET,
-- score = best_time_ms) is the hot ranking; this table is the durable record.
CREATE TABLE parkour_times (
    player_uuid   UUID         PRIMARY KEY,
    username      VARCHAR(16),
    best_time_ms  BIGINT       NOT NULL,
    achieved_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_parkour_best ON parkour_times (best_time_ms ASC);
