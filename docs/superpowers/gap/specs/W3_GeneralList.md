# W3 — GeneralList (세력 장수 목록, permission-tiered)

**DTO/controller:** `dto/IdentityDto.kt` (MyGeneralsResponse/MyGeneralSummary), `controller/MyController.kt:73-100`, `controller/GeneralsController.kt` (PublicGeneral P0), `read/GeneralReadRepository.kt`.
**Source verified against:** V1 `general` table, V6 (`officer_city`), `rank_data` (V1).

## Verified corrections to the audit
- **`general.officer_city` EXISTS** — added by `V6__p4_war_columns.sql:16` (ConquerCity governor demote). Audit marked it "MISSING/unclear" — it is **enrichable NOW** once mapped in GeneralReadEntity.
- `dex1-5` confirmed **NO source** (not column, not V6, not meta). Real blocker.
- `general_access_log` (refresh_score/refresh_score_total) — **absent** from all migrations. Real blocker.
- `autorun_limit` — no column; would ride `meta` if engine writes it (unverified). Defer.

## 1. Enrichable NOW
P0: `no(id), name, nation, npc(npc_state), injury, leadership, strength, intel, gold, rice, picture, imgsvr(image_server), troop, city, special_code, special2_code, personal_code` + meta-derived `explevel, dedlevel, age, belong` (meta keys per OQ5) + computed `killturn, officerLevelText, lbonus, ownerName(V10 general_owner), honorText, dedLevelText, bill(by level)`.
P1: `experience, dedication, officer_level, officer_city(V6), crewtype, crew, train, atmos, turntime, horse/weapon/book/item codes, recent_war(recent_war_time)` + meta `specage, specage2, leadership_exp, strength_exp, intel_exp` + `reservedCommand` (general_turn turn_idx<5, GeneralTurnReadRepository exists).

## 2. BLOCKED (missing opensamguk source)
- **dex1-5** (P1) — no backing. Needs `general` column add (or meta write) + WorldSnapshot/rowmapper. Defer; SHARED blocker with FrontGeneralInfo.
- **warnum/killnum/deathnum/killcrew/deathcrew/firenum** (P1) — `rank_data` exists, zero read path. Needs `RankDataReadEntity`+`RankDataReadRepository` (value-by-(general_id,type)). Defer; SHARED with FrontGeneralInfo → build the rank read repo ONCE.
- **refresh_score / refresh_score_total** — `general_access_log` table absent. Needs migration + write path (P8 throttle). Defer.
- **defence_train, autorun_limit** — no column; verify meta; else defer.

## 3. FE consumers
`legacy GeneralList.vue` (AG-Grid, permission-gated columns, 15+ sort modes), `core2026 getGeneralList.ts`, `web/game nation/general-list` route. PHP sort = `ORDER BY turntime ASC` (verify FE expectation before changing).

## 4. Risk
Permission tiering PHP {0..4}→Kotlin {0,1,2}: verify gate at officer_level 5 boundary. `rank_data` read repo + `dex` source are the two cross-group blockers. New controller `GET /api/nation/general-list` (column-flat-array per tier). Co-widens GeneralReadEntity + GeneralReadRepository → foundation-first.
