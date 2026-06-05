# W3 — FrontGeneralInfo enrichment

**DTO/controller:** `app/game-api/.../dto/IdentityDto.kt` (FrontGeneralInfo), `controller/FrontInfoController.kt:83-99,158-174`, `controller/MyController.kt:47-69`.
**Source of truth verified against:** `infra/src/main/resources/db/migration/V1__baseline.sql` (general table), `read/GeneralReadRepository.kt` (GeneralReadEntity).

## Verified corrections to the audit
- `special_code`, `special2_code`, `personal_code`, `penalty (jsonb)` **EXIST as `general` columns** (V1 baseline). They are simply **NOT mapped** in `GeneralReadEntity` → easy add (add `@Column` + `ddl-auto: validate` passes). NOT a schema blocker.
- `dex1-5` are **NOT columns**, **NOT in V6 war columns**, and have **zero references** in any meta key list / rowmapper / snapshot. Real blocker.
- `rank_data` **table exists** (V1:162) but has **NO read entity/repo/query anywhere** in game-api. Real blocker for warnum/killnum/deathnum/killcrew/deathcrew/firenum.

## 1. Enrichable NOW (source exists)
Already mapped in GeneralReadEntity → just thread into DTO:
`picture, experience, dedication, train, atmos, injury, crewTypeId, horseCode, weaponCode, bookCode, itemCode, officerLevel, troopId`.
One-line entity additions (columns exist, add `@Column` mapping):
`specialDomestic←special_code`, `specialWar←special2_code`, `personal←personal_code`, `penalty←penalty jsonb`.

## 2. BLOCKED (missing opensamguk source)
- **dex1-5** — no column, no meta key. Needs a `general` column add (or engine meta write) + WorldSnapshot/rowmapper plumbing. Defer.
- **warnum/killnum/deathnum/killcrew/deathcrew/firenum** — `rank_data` table exists but is **write-only** (no read path). Needs `RankDataReadEntity`+`RankDataReadRepository` (or `@Query` returning value-by-type). Defer as one infra unit (shared with GeneralList group).

## 3. FE consumers
`web/game/app/game/page.tsx` (무력 mojibake bug L92), `generals/page.tsx`, `[general]/page.tsx` detail, `rankings/generals.tsx`. Components: GeneralCard(dex), WarStats(rank_data), EquipmentSlots, TraitDisplay.

## 4. Risk
Shared-file: `GeneralReadEntity` + `FrontInfoController` co-widened by 3 groups (this, FrontNationInfo, FrontGlobalInfo, GeneralList) → **foundation-first**. The `rank_data` read repo is a shared dependency of THIS group + GeneralList → build once.
