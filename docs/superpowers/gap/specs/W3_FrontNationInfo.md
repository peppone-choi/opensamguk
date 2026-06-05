# W3 — FrontNationInfo enrichment

**DTO/controller:** `dto/IdentityDto.kt:74-83`, `controller/FrontInfoController.kt:100-111`, `controller/MyController.kt:163-172`, `web/game/lib/types.ts:74-83`.
**Source verified against:** V1 `nation` table, `V8__nation_power.sql`, `read/NationReadRepository.kt`.

## Verified corrections to the audit
- **`nation.power` EXISTS** — added by `V8__nation_power.sql` (`ALTER TABLE nation ADD COLUMN power`). The audit (and the stale `GeneralReadRepository.kt:168` comment "no power column → SUM(crew) proxy") are **outdated**. `power` is **enrichable NOW**, not a blocker.
- `nation` V1 columns: `id, name, color, capital_city_id, gold, rice, tech, level, type_code, meta(jsonb)` + `power`. Everything else rides `meta` or is absent.
- `gennum` already read back from `meta` (`NationReadRepository.kt:65`).

## 1. Enrichable NOW
Direct columns: `id, name, color, level, capital(capital_city_id), gold, rice, tech, type.raw(type_code), power`.
From meta: `gennum`.
Aggregates (add `@Query` to NationReadRepository, COUNT/SUM over city+general — guard N+1 with single grouped query):
`population.{cityCnt,now,max}` = COUNT/SUM(pop,pop_max) FROM city WHERE nation_id;
`crew.{generalCnt,now,max}` = COUNT/SUM(crew)/SUM(leadership)*100 FROM general WHERE nation_id AND npc_state!=5.
Computed: `type.{name,pros,cons}` via NationType class lookup on type_code (verify class exists in `:logic`); `topChiefs` = generals WHERE officer_level>=11; `impossibleStrategicCommand` via GameConst rule engine.

## 2. BLOCKED (missing opensamguk source)
None of these are V1 columns. They either ride `nation.meta` (IF the engine writes them — UNVERIFIED) or are absent:
- `bill, taxRate(rate), diplomaticLimit(surlimit), strategicCmdLimit, prohibitScout(scout), prohibitWar(war)` — no column; check whether engine writes them to `meta`; if not, needs migration + engine write. Defer.
- `onlineGen, notice` — KVStorage `game_env.online_genenerals` / `nation_env.nationNotice`. `nation_env` table exists (V3) but daemon KV population unverified. Defer to KV-wiring task.

## 3. FE consumers
`web/game/components/game/NationBasicCard.tsx` (comment lists OMITTED 성향/주민/병사/지급률/세율/속령/국력/전략/외교/전쟁 — enable only as DTO provides), `GeneralBasicCard.tsx`, `app/game/page.tsx` header, `app/game/nation/page.tsx`.

## 4. Risk
`FrontInfoController` + `NationReadRepository` co-widened → foundation-first. Aggregates must be a single grouped query, not per-nation N+1. NationType class presence in `:logic` is UNVERIFIED — confirm before promising `type.name/pros/cons`.
