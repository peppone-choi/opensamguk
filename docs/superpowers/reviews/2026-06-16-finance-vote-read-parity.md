# Cross-agent critique — finance/vote read-parity (PR #94)

- **Branch:** `fix-finance-vote-read-parity` (commit a3c1aa08)
- **Date:** 2026-06-16
- **Scope:** Backend-read-blocked page-parity gaps from `docs/superpowers/gap/FE_OUTPUT_READ_GAP.md` §10 — nation-finance income/outcome/nationsList, vote voteReward, nation.meta seed parity.
- **Reviewers (independent, separate lanes):**
  - `general-purpose (opus)` — adversarial PHP byte-parity vs `legacy/devsam-core` grand truth.
  - `oh-my-claudecode:code-reviewer` — correctness / null-safety / React+TS (`web/game`).

## Files under review

- `app/game-api/.../controller/NationFinanceController.kt` — income/outcome computed LIVE at rate=100 reusing golden-verified `logic/.../IncomeTick.kt` functions; `nationsList` from `getAllNationStaticInfo` + cityCnt + diplomacy.
- `app/game-api/.../controller/FrontInfoController.kt`, `dto/IdentityDto.kt` — `voteReward = develcost*5`.
- `infra/.../seed/ScenarioImporter.kt` (+ `ScenarioImporterIT.kt`) — nation.meta seeds `rate/bill/scout/war/strategic_cmd_limit/surlimit/gennum`.
- `web/game/app/game/nation-finance/page.tsx` — 외교관계 table + `diplomacyStateInfo`/`joinYearMonth`/`parseYearMonth` helpers.
- `web/game/app/game/vote/page.tsx`, `web/game/lib/types.ts`, `web/game/types/game.ts`.

## PHP-parity findings (opus, citing oracle)

All MATCH against grand truth:

- **rate=100 income preview** — `NationFinanceController` passes `taxRate=100.0` to `getGoldIncome/getRiceIncome/getWallIncome`, byte-identical to `v_nationStratFinan.php` (live what-if at rate 100, not a stored snapshot). Each per-city term is already `Util::round`→Int, so the outer `phpRound` is a verified no-op — no double-rounding.
- **officer-count filter** — `officer_level IN (2,3,4) AND city = officer_city GROUP BY officer_city` reproduced exactly (computed inside each income fn, as in PHP `func_time_event.php`).
- **getOutcome npc filter** — `it.npcState != 5` ↔ PHP `npc != 5` (`npc_state` is the opensamguk rename; precedent in `NationReadRepository`). `getOutcome(100.0, …)` matches.
- **nationsList** — self→state=7/term=null, others from `diplomacy WHERE me=id`, missing→통상(2); ordering `sortedBy { it.id }` matches PHP's no-ORDER-BY PK order; 재야(0) excluded.
- **diplomacyStateInfo + term/endYear math (FE)** — `{0 교전/red, 1 선포중/magenta, 2 통상, 7 불가침/green}` byte-identical to `defs/index.ts`; `joinYearMonth`/`parseYearMonth` match PHP utils.
- **seed nation meta** — `rate=15/bill=100/scout=0/war=0/strategic_cmd_limit=24/surlimit=72` byte-identical to `Scenario/Nation.php`; `gennum = count(generals by nation)` replicates postBuild. `secretlimit` omission faithful (PHP INSERT also omits → schema default → FE '-').
- **voteReward** — `config["develcost"] * 5` ↔ `v_vote.php` / `Vote.php`; FE title string replicated verbatim. Not fabricated.

No RNG draws involved (pure non-RNG settlement reusing golden-verified functions) → no draw-for-draw gate, no new golden needed (CLAUDE.md rule 1/5 satisfied). No Korean log strings touched.

## Correctness findings (code-reviewer)

Non-blocking; recorded for follow-up:

- **[MEDIUM] `income.gold.war` (단기수입) structurally 0** — `calcCityWarGoldIncome` reads `City.dead`, which is not persisted/mapped on the read path (no `city.dead` column in `V1__baseline.sql`; `CityReadEntity.toLogic()` does not map it → defaults 0). For a fresh seed / no battle-dead cities this is parity-correct (the term is 0 in PHP too), but the KDoc parity claim must not assert byte-parity for the war component in general. **Action:** scope the parity claim to gold.city/rice.city/rice.wall/outcome and backlog `city.dead` read-path persistence. Quarantined-with-scope, not fabricated.
- **[MEDIUM] N+1 in nationsList** — `cities.countByNationId(n)` per nation. Trivial for 1010 (2 nations); latent cost for many-nation scenarios (1030 = 21) re-fetched on every `turnCompleted` SSE. **Action:** fold into one `GROUP BY nation_id` count map (mirror existing `sumPopulationByNationId`).
- **[MEDIUM] FE nullable-type drift** — `NationFinanceResponse.income/outcome` + `NationFinancePolicy.rate/bill` typed non-optional but DTO emits nullable; tsc strict passes only because `!= null` guards + redundant `!` are legal on non-null types. Guards are cosmetic, not type-load-bearing. **Action:** mark those fields optional in `types/game.ts`.
- **[LOW] verified non-issues** — vote title guard is `voteReward != null` (no `0 && …` falsy-leak; `voteReward===0` renders "0금" intentionally, matching `develcost*5`); colSpan correct (7 headers; self 4+colSpan3, others 4+3); `nationsList` guarded against undefined (old-image safe).

## Verification evidence

- Backend compile BUILD SUCCESSFUL (infra + game-api). `F4ReadControllersTest` 31/0. `ScenarioImporterIT` asserts `meta.gennum == COUNT(general)` (Docker-gated).
- FE `web/game` `tsc --noEmit` exit 0.

## Deploy impact

- `web/game` (FE) auto-pulls on main merge.
- `game-api` (read-only, no Flyway migration) + `infra` seed land at the next **manual** `sN.env` pin bump; seed runs only on empty DB (will not touch already-seeded live servers).
- `game-engine` NOT auto-deployed (RNG/log desync rule).

## Out-of-scope discoveries (reported, not fixed here)

- `PreUpdateMonthly { true }` stub in `app/game-engine/.../config/DaemonLoopConfig.kt` → `rate_tmp` never set → live nations may compute income from `rate_tmp=0`. Needs golden + engine deploy window.
- Remaining §10 items (#2 penalty `general_access_log`, #4 history globalEvents/yearbook, #5 monthly map snapshot two-viewer, #6 `impossibleStrategicCommand`) need Flyway + engine + local DB verify + deploy-window decision.

Verdict: cleared
