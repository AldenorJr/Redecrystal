-- Player economy for RankUP: Money (progression) + Tokens (cosmetics), keyed by
-- UUID. `version` drives the optimistic lock on absolute/admin sets; additive
-- deltas and conditional debits are atomic SQL (no lock). Redis economy:{uuid} is
-- the write-through hot cache; leaderboard:money is the sorted-set ranking.
CREATE TABLE player_economy (
    player_uuid   UUID     PRIMARY KEY,
    money         BIGINT   NOT NULL DEFAULT 0,
    tokens        BIGINT   NOT NULL DEFAULT 0,
    version       INTEGER  NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
