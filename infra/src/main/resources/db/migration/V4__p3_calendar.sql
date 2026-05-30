-- P3 (Task FC1): calendar/clock columns on world_state + the P0/P1 city.trade / city.trust schema
-- fixes that the monthly tick (ServerClock / MonthlyPipeline / MonthScopedRng / RandomizeCityTradeRate)
-- requires. V1-V3 are FROZEN baselines — this migration only ALTERs.
--
-- ─── world_state calendar columns ───
-- PHP grand truth `turnDate` (func.php:1250-1275) reads the game-env values `startyear`/`starttime`/
-- `turnterm` (plus the live `year`/`month` it write-gates). `executeAllCommand`
-- (TurnExecutionHelper.php:409) freezes the whole tick when `isunited == 2 || 3` (천통). The month-
-- scoped RNG (F5) + ServerClock thread `hidden_seed`.
--
-- COMMITTED SHAPE (consolidated OQ #4/FEAS-04, option (a)): add explicit calendar columns rather than
-- read them from the existing `config` jsonb. The baseline already carries `current_year`/
-- `current_month`/`tick_seconds`/`config`/`meta`; `tick_seconds` is the canonical cadence in SECONDS
-- and `turn_term` is its MINUTES view (turn_term = tick_seconds / 60) — F1/F4/F5 do NOT duplicate the
-- cadence: they read `turn_term` (minutes, matching PHP `turnterm`) and treat `tick_seconds` as the
-- legacy seconds form. The new columns are NULLABLE (except `isunited`, which defaults 0) so the P1/P2
-- IT seeds that INSERT only the baseline columns still succeed; scenario import populates them
-- (scenario_1010 startYear=181).
--
-- `hidden_seed` provenance (consolidated OQ #6 — source-verified): in legacy it is a CONFIG-FILE static
-- field `d_setting/UniqueConst.php::$hiddenSeed` (live value `8ebfeb6fa932a181ec9ef43b7473f4c9`, a
-- 32-char lowercase hex = `bin2hex(random_bytes(16))`, ResetHelper.php:96); ZERO DB/KV writes of it
-- exist in legacy. For opensamguk this is an INTENTIONAL config-file→DB-column divergence: the seed
-- lives in `world_state.hidden_seed` (a single-row column read directly by ServerClock/MonthScopedRng),
-- captured ONCE per install. The G1b fixture commits the exact live hex as the shared golden input.
ALTER TABLE world_state ADD COLUMN start_year  integer;
ALTER TABLE world_state ADD COLUMN start_time  timestamptz;
ALTER TABLE world_state ADD COLUMN turn_term   integer;
ALTER TABLE world_state ADD COLUMN isunited    integer NOT NULL DEFAULT 0;
ALTER TABLE world_state ADD COLUMN hidden_seed text;

-- ─── city.trade: DROP NOT NULL + DROP DEFAULT (P0/P1 blocker) ───
-- Baseline V1__baseline.sql:50 is `trade integer NOT NULL DEFAULT 100`. RandomizeCityTradeRate writes
-- literal NULL for prob-0 / failed-roll cities (the GREEN body returns `Int? null`); a NOT NULL flush
-- would throw or silently coerce to 100 and break byte-match. Drop both so the NULL is byte-legal.
ALTER TABLE city ALTER COLUMN trade DROP NOT NULL;
ALTER TABLE city ALTER COLUMN trade DROP DEFAULT;

-- ─── city.trust: integer → double precision (P0/P1 blocker) ───
-- Baseline V1__baseline.sql:49 is `trust integer NOT NULL DEFAULT 0`, but the legacy schema is FLOAT.
-- Fractional trust accumulation + the `trust < 30` neutralize threshold (UpdateCitySupply) must survive
-- flush↔reload, else a silent multi-month-only divergence appears after month 1.
ALTER TABLE city ALTER COLUMN trust TYPE double precision;
