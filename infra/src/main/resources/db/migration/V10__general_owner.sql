-- F2 Wave 1 — account-side user↔general possession ("장수 점유") mapping.
--
-- This is ACCOUNT-side metadata, NOT game state. The game-engine daemon is the only writer of
-- game-state tables (general/city/nation/...) via ChangeRecorder→JdbcFlushExecutor (the
-- architecture-test-enforced "one-daemon-write" rule). game-api may INSERT/DELETE this table via
-- JPA because it is NOT a game-state table — it never mutates `general`.
--
-- Invariants:
--   * one owned general per user on this server  → UNIQUE(user_id)
--   * a general is claimed at most once          → UNIQUE(general_id) (PK)
--   * user_id references the gateway `users.id` (BIGSERIAL). general_id references the game `general.id`
--     (an integer PK). We DO NOT add an FK to `general` — that table is owned by the daemon write path
--     and game-api must not couple a schema constraint that would let an account-table write block on a
--     game-state row. The link is validated in application code (GeneralResolver / claim guard).
--
-- NOTE: the legacy `general.user_id TEXT` / `general.npc_state` columns are the GAME-STATE side of
-- possession (npc flip). Flipping npc_state on claim is a separate, deferred concern routed through the
-- intake path (see GeneralPossessionService doc) — this table does not and must not touch them.
CREATE TABLE IF NOT EXISTS general_owner (
    general_id  BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    claimed_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_general_owner PRIMARY KEY (general_id),
    CONSTRAINT uq_general_owner_user UNIQUE (user_id)
);

-- general_id is the PK (already indexed). Add a covering index on user_id for the resolver lookup
-- (the UNIQUE on user_id already creates a btree index, so this is the only extra index we need).
CREATE INDEX IF NOT EXISTS idx_general_owner_user ON general_owner (user_id);
