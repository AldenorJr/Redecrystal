-- Per-player permission grants. `context` allows per-server / per-world scoping
-- (NULL = global). `value = FALSE` is an explicit negation.
CREATE TABLE player_permissions (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY,
    player_id   UUID         NOT NULL REFERENCES players (id) ON DELETE CASCADE,
    permission  VARCHAR(128) NOT NULL,
    value       BOOLEAN      NOT NULL DEFAULT TRUE,
    context     VARCHAR(64),
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_player_permissions PRIMARY KEY (id)
);

CREATE UNIQUE INDEX ux_player_permissions
    ON player_permissions (player_id, permission, COALESCE(context, ''));
CREATE INDEX ix_player_permissions_player ON player_permissions (player_id);
