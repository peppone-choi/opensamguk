# W3 — ChiefCenter (사령부) read contract — verified audit

(Companion to `W3_ChiefCenter.md` written by the sibling agent; this is the source-verification pass.)

**DTO/controller:** `dto/F4Dto.kt:132-150`, `controller/ChiefCenterController.kt:36-71`.

## Enrichable NOW — ZERO source blockers (all backing verified)
- `name←general.name`, `npcType←general.npc_state` (mapped in GeneralReadEntity).
- `turnTime←general.turn_time` — column EXISTS; add mapping + Korean full-format formatter (PHP `General::getTurnTime(TURNTIME_FULL)`).
- `myGeneralId / myOfficerLevel` — via GeneralResolver / ResolvedGeneral (in place).
- `nationName←nation.name` (재야 fallback `F4StateText.NEUTRAL_NATION_NAME`), `nationLevel←nation.level` (resolver loads it).
- `year/month/turnTerm` — `game_env` via `GameKvReadRepository` (pattern used elsewhere).
- reserved `turnIdx/actionCode/brief` — already in ChiefReservedTurn (NationTurnReadEntity 29/32/35; brief = V2).

## BLOCKED
None. Only open item = exact TURNTIME_FULL format string (formatter-parity, not a missing source).

## Risk
LOWEST of all 6 groups. Separate controller (does NOT touch FrontInfoController) → ships in parallel with the foundation wave. Shares the TurnTime formatter with GeneralList → build the formatter once.
