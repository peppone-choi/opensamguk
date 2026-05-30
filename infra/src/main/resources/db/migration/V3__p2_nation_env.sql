-- P2 (Task FF2): the per-nation key-value store the flush step-10 KV write-set lands in.
--
-- Faithful port of the PHP grand-truth `nation_env` table (`legacy/devsam-core/hwe/sql/schema.sql:576`:
-- `namespace INT [= nation_id], key VARCHAR(40), value LONGTEXT-json, UNIQUE(namespace, key)`),
-- Postgres-ized. The KV value families this table carries (research 2026-05-29-p2-research.md:802):
--   * `next_execute_{actionName}` — a bare int (joined yearMonth), written when postReqTurn truthy,
--   * `turn_last_{officer_level}` — a `LastTurn::toRaw()` object (the nation-command setResultTurn
--      target; general commands instead ride the `general.last_turn` jsonb),
--   * `last천도Trial` and friends — per-nation cooldown side-effects.
-- `setValue(key, null)` DELETEs the row (delete-on-null — KVStorage.php:335-356).
--
-- Design §188 lists `nation`(+nation_turn/nation_env); V1 baseline did not port nation_env, so this
-- migration adds it (the OPEN QUESTION at p2-research.md:814 is resolved here in favor of the
-- design-named dedicated `nation_env` table, NOT a generic `storage` table). V1/V2 are frozen — do
-- NOT touch them.
CREATE TABLE nation_env (
    id        serial PRIMARY KEY,
    namespace integer NOT NULL,
    key       text NOT NULL,
    value     jsonb NOT NULL,
    UNIQUE (namespace, key)
);
CREATE INDEX nation_env_by_namespace ON nation_env (namespace);
