# P2 Plan — Adversarial Review Report

> Multi-agent review (53 agents, 44 findings → 34 confirmed), 2026-05-29, before execution.
> Dimensions: feasibility · coherence · scope · correctness · parity-risk + OQ triage.

## Verdict: **go-with-edits**

Plan genuinely EXTENDS the GREEN P1 substrate (no re-port), the 9-source iface is widened only to domestic needs (oppose/war/inherit/scenario correctly stubbed), wave decomposition is the right shape. One BLOCKER + schema/SQL/coherence defects that would silently fail the byte gates. Surgical fixes, not a redesign.

## P0 — before ANY execution (3)

1. **Per-action RNG seed 6th component is misdescribed** (header L24-25, FG1, FR2, GR2). Verified `TurnExecutionHelper.php:310-347` + `BaseCommand.php:261-266`: it is `getRawClassName(true)` = PHP CLASS SHORT NAME **with the `che_`/`cr_` prefix** (e.g. `che_랜덤임관`, `cr_맹훈련`, `cr_건국`), NOT `definition.key` and NOT the de-spaced actionName. `che_랜덤임관` has `$actionName='무작위 국가로 임관'` (≠ class); `cr_맹훈련`/`cr_건국` carry `cr_`. P1 only worked because 상업투자/농지개간 class == che_+de-spaced coincidentally — does NOT generalize. **Add a `rawClassName: String` field per GeneralActionDefinition.** SECOND: the unique-item lottery seed uses `static::$actionName` (che_랜덤임관.php:284), a DIFFERENT token from the action seed — pin both separately. FR2 must assert `serializeSeed(...,rawClassName)` byte-matches `str(mb_strlen,value)` for `cr_맹훈련` (str(6,…)), `che_랜덤임관`, `cr_건국`. Highest byte-parity hazard.
2. **Schema gaps** (FD0). Verified V1__baseline.sql: general has NO `leadership_exp`/`strength_exp`/`dedlevel`; general_turn AND nation_turn have NO `brief`; city has NO `tech` (tech is `nation.tech`). **Decisions (applied):** (a) `leadership_exp`/`strength_exp`/`dedlevel` ride `meta` jsonb (consistent with P1's `intel_exp`/`explevel`; pin insertion order vs golden) — no migration. (b) Add a `brief text NOT NULL DEFAULT ''` column to general_turn + nation_turn via a **V2 Flyway migration** (che_거병 writes 24 nation_turn rows brief='휴식'). (c) Remove `tech`/`techMax` from the City field list + DV1 cityKey switch — tech is a nation stat (che_기술연구/DV2 writes nation.tech).
3. **Flush SQL not widened** (FF2/FD1). Verified `JdbcFlushExecutor.kt:132-176`: general/city UPDATE SET clauses hardcode the P1 subset; widened row mappers are inert. **Widen** general UPDATE += crew/train/atmos/crew_type_id/troop_id/weapon_code/book_code/horse_code/item_code/last_turn/personal_code; city UPDATE += secu/secu_max/def/def_max/wall/wall_max/pop/pop_max/trade. Add `general.last_turn` (LastTurn jsonb riding the general row; nation-command setResultTurn → nation_env turn_last KV). JdbcFlushExecutorIT round-trip assertion.

## P1 — before the relevant wave (5)

1. **Wave-3 independence is false** (L168/171/406/794, PR2/FND2). Four real shared files across same-wave families: `FoundingCascade.kt` (PR2 creates / FND2 edits), `Presets.kt` (MIL1+FND1), `DomesticHelpers.kt` (DV2+IT3), `GeneralActionModuleFactory.kt` (TD1/TP1/TN1/IT1/IT2). **Protocol (applied):** move shared creations to Tier-0/1 foundation tasks that the families CONSUME (Presets → C-PURE/C-DEST prereq; FoundingCascade → foundation or explicit FND2←PR2 edge; GeneralActionModuleFactory → S-MODULES foundation; DomesticHelpers shared key → DV/IT consume). Only then is Wave-3 truly file-disjoint (modulo append-only CommandRegistry registration per family).
2. **RANK_ROWS_PER_GENERAL = 37** now (FF1/GSat1) — RankColumn.entries.size = 37; reconcile the stale engine `40` + DatabaseHooksOrderTest 2*40. rank_data is **UPDATE over 37 pre-seeded rows, NOT UPSERT**.
3. **Map/distance (OQ9) → Wave-1 foundation** (NI5 천도, MIL5 이동). CityConst.kt already carries the bidirectional `path` adjacency (golden-locked). Ship a minimal `CalcCityDistance` (pure BFS over CityConst.path) as a Wave-1 foundation; NearCity + 천도 distance + 이동 adjacency land on it. Full pathfinding (HasRoute*/NearNation) deferred to the map/diplomacy phase.
4. **SL1 fixes**: addExperience/addDedication MUST fold the input through `onCalcStat('experience'|'dedication')` (full getActionList, affectTrigger default true) BEFORE increaseVar (personality exp*1.1 etc.); then recompute explevel/dedlevel + the PLAIN level-change logs.
5. **OQ7/PR5 ScoutMessage boundary (applied):** ship `che_등용` SEND (getCost `round(env.develcost+(dest.experience+dest.dedication)/1000)*10`, round BEFORE *10; self +exp100/+ded200/+leadership_exp1/−reqGold) + a minimal `message` row INSERT + the SEND golden. Defer 등용수락/decline + full Message/MessageTarget/buildFromArray mailbox to **P6**.

## P2 (2)

1. **GS1 = PORT `compare-command-logs.mjs`** (not "retarget a scanner") from `legacy/devsam-core2026/tools/` into `tools/php-golden/compare-command-logs/`, re-pointing PHP_ROOT → legacy/devsam-core. matched-count 0-mismatch rises.
2. **DV4 물자조달 / ReqCityTrader pin**: exp/ded computed from the PRE-front-debuff rounded score; front-debuff multiplies AFTER; log scoreText + nation credit use the POST-debuff score (che_물자조달.php:99-148).

## OQ resolutions (resolve-now)

OQ9 map → CalcCityDistance Wave-1 (above). OQ10 RANK=37 (above). OQ11 brief → V2 migration NOT NULL DEFAULT '' (above). OQ7 등용 send (above). OQ13 develcost env → keep single WorldEnvBuilder, add per-family env keys with PHP-confirmed defaults (develcost per-server; 정착장려/주민선정 develcost*2; 천도 distance cost; 발령 turnterm; 거병 scenario; 건국 init_year/month; isunited) as each family lands.

## Deferred

Full pathfinding (HasRoute*/NearNation, C-DB family) → map/diplomacy phase. 등용수락/decline mailbox → P6. RandomizeCityTradeRate monthly DRIVER + ProcessIncome/SemiAnnual tick body → P3 economy-tick (port the per-action math now; the loop is P3). per-source onCalc* hook matrix → enumerate in TRAITS-*/ITEMS, GATE-TRAIT empirical. preprocess 5-tuple seed variant → pin in FR2 alongside the 6-tuple forks.

## Residual risks

MetaJson fractional-Double byte shape (Kotlin Double.toString vs PHP serialize_precision — low blast radius in P2). 거병 diplomacy INSERT row order (getAllNationStaticInfo iteration). rank_data intra-step-8 sub-order (nation_id-sync before rankVarIncrease/Set). getStatValue StatCalc cache plumbing under-specified across F-PIPELINE→F-SUBSTRATE→resolvers. **Re-confirm P1 golden suite GREEN on the actual base commit before Wave 3 rebases.** WorldEnvBuilder precheck==full drift across the full P2 env surface.

_Full output: `tasks/ww0dtbjyc.output` (run wf_a4d2ef43-832)._
