# MATERIALIZE-SPEC — feed the real world into `AiTurnAdapter`

P5 wave goal: `AiTurnAdapter` (the F-SEAM) currently builds `AiWorldView`/`GeneralAiContext` with
**stubbed inputs** (`generals = emptyList()`, `nationTech = 0`, defaulted lambdas + an INERT
`NationPassHooks`). The do<한글> bodies + `AiWorldView` already hold the decision logic and are
unit-GREEN; the candidate buckets they pick from are simply **empty** in the LIVE path, so the bodies
are null no-ops. This wave **materialises the real in-memory world** into the adapter so every body gets
a real candidate set. THIS IS ENGINE WIRING — feed real data; do NOT re-implement decisions.

## PARITY LAW (non-negotiable)
- GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php`.
- The candidate-set ORDER fed to `AiWorldView` IS a draw-for-draw parity target:
  - generals materialised **PK-ascending** (`sortedBy { it.id }`), self-EXCLUDED (R-FACADE §1, PHP `:3516`
    `... AND no != %i`, no ORDER BY → clustered PK order).
  - cities materialised **PK-ascending** (`sortedBy { it.id }`) — `AiWorldView` re-sorts internally
    (`cityRows.sortedBy { it.id }`), but feed FULL `world.listCities()` (NOT pre-filtered to own nation:
    the facade filters by `row.nationId != ownNationId`, and the non-nation rows are needed for
    `cityGeneralCountOf`/`attackableCitiesOf`/`foundOccupiedCities`/BFS).
  - `LinkedHashMap` insertion order preserved, never re-keyed by id.
- THE ONE daemon-write rule: the AI is READ-ONLY over GAME ENTITIES (returns `(actionCode, RAW args)`);
  meta-KV routes through `recordGeneralKv`/`AiKvRecorder` ONLY. `DaemonNoEntityManagerTest` MUST stay green.

---

## §0 — The world API available (confirmed from `InMemoryTurnWorld` + `TurnWorldModel`)

`InMemoryTurnWorld` accessors (all read-only, return defensive copies):
- `listGenerals(): List<TurnGeneral>` · `getGeneralById(id)`
- `listCities(): List<City>` · `getCityById(id)`
- `listNations(): List<Nation>` · `getNationById(id)`
- `listTroops(): List<Troop>` · `getTroopById(id)`
- `listDiplomacy(): List<TurnDiplomacy>`
- `getState(): TurnWorldState` (year/month)

Conversion `engine → logic`: `PerTurnOverlay.toLogicGeneral(TurnGeneral)` / `.toLogicCity(City)`. The
engine model carries `troopId` (→ logic `General.troop`), `npcState` (→ `npcType`), `crew`, `train`,
`atmos`, `gold`, `rice`, `meta` (verbatim). **`Nation` has NO `tech` column** — `tech` rides
`Nation.meta["tech"]` (must be read defensively; default 0). City `frontState`/`supplyState`/`level`/pop
are plain columns; `trust` rides `City.meta["trust"]` (already mapped by `toLogicCity`).
`TurnGeneral.recentWarTime: Instant?` exists → the `recentWarSeconds` for `AiGeneralView`.

`AiWorldView` needs `List<AiGeneralView>` (NOT `List<General>`): each wraps the logic `General` +
`recentWarSeconds: Long?` + `reservedCommandName: String?` + `fullLeadership: Double`.

---

## §1 — STUBBED INPUTS ENUMERATED (count = 18)

Legend per entry: **(a)** which do<한글> bodies consume it · **(b)** the world API / `AiWorldView` /
`ActionPipeline` source · **(c)** PHP source + exact shape/order.

### THE 3 HIGHEST-LEVERAGE (materialise these first — they unblock the most bodies)

#### S1 — `AiWorldView.generals = emptyList()` → the 9 general buckets are EMPTY  ★★★ TOP-1
- **(a)** Consumed by EVERY nation-pass body (all 12 `do발령` in NationDeployFamily read
  `troopLeaders`/`userWarGenerals`/`userCivilGenerals`/`npcWarGenerals`/`npcCivilGenerals`/`lostGenerals`/
  `nationGenerals`/`nationCities[*].generals`/`.important`), the 5 reward bodies
  (`userWarGenerals`/`userGenerals`/`npcWarGenerals`/`npcCivilGenerals`), and `do선양` (`seonyangCandidates`).
  With `generals = emptyList()` ALL nation-pass bodies are guaranteed null → the nation pass only ever
  hits the neutral fallback. THIS IS THE SINGLE BIGGEST GAP.
- **(b)** `world.listGenerals().filter { it.nationId == nationId && it.id != selfGeneralId }`
  `.sortedBy { it.id }` (PK-ascending, self-excluded) `.map { toAiGeneralView(it) }` where
  `toAiGeneralView` = `AiGeneralView(general = PerTurnOverlay.toLogicGeneral(tg),
  recentWarSeconds = tg.recentWarTime?.let { Duration.between(it, tg.turnTime).seconds } /* null when recentWarTime==null */,
  reservedCommandName = <turn_idx 0 reserved command name; see S2>,
  fullLeadership = AiSeed.genTypeLeadership(StatCalc(toLogicGeneral(tg), pipeline)))`.
  Feed via `AiWorldView(... generals = <that list>, ...)`. The `ownNationId`/`ownGeneralId` are already
  threaded; `AiWorldView.categorizeNationGeneral()` does the bucketing (GREEN).
- **(c)** PHP `GeneralAI.php:3516` `SELECT no FROM general WHERE nation=%i AND no != %i` (no ORDER BY →
  PK-asc). `recentWarSeconds`: `General.php:286` DateInterval(recent_war, turntime) seconds, null=falsy
  recent_war (→ the 12000 sentinel in `calcRecentWarTurn`). `fullLeadership`: `getLeadership(false)`
  (G8 flavor, SAME site `calcGenType` uses). NOTE: cities MUST be fed too (S?-below) — `categorizeNationGeneral`
  attaches generals into `nationCities[*].generals` by-reference; if the city buckets are wrong the
  general buckets desync.

#### S2 — `reservedCommandName` (the `AiGeneralView` field) — currently absent  ★★★ TOP-3
- **(a)** `AiWorldView.categorizeNationGeneral` `:3577` (the non-NPC `che_집합` troopLeader rung) — drives
  whether a non-npc general lands in `troopLeaders` (which gates `do부대*발령`).
- **(b)** The daemon's reserved-turn store. The adapter resolves `reserved turn_idx 0` per general id via
  the SAME `ReservedTurnRepository`/`decodeArgs` path the adapter already uses for the acting general
  (`ReservedTurnHandler.decodeArgs`). Thread a `(generalId) -> String?` lambda from the F-SEAM caller
  (the turn loop already has the reserved-turn map). Default null = 휴식/none.
- **(c)** PHP `:3577` `$nationGeneral->getReservedTurn(0)->getName() === 'che_집합'`. Literal compare.

#### S3 — `cityDevelRateOf: (cityId) -> List<Triple<develKey, develVal, develType>>`  ★★★ TOP-2
  (default `{ emptyList() }`) AND its sibling `cityDevelRate: Map<String, Double>` (acting city)
- **(a)** `do내정워프` (GenWarMove `:3037/3062` — the `warpProp` product + `realDevelRate` over supplyCities,
  filtered by `genType and develType`), `do일반내정`/`do전쟁내정`/`do긴급내정` (GenDomestic — the acting
  city's `cityDevelRate` per-develKey thresholds). Without it `do내정워프` always `availableTypeCnt==0 → null`
  and the domestic cmdList is empty → null.
- **(b)** REUSE the GREEN `RatesPromoFamily.calcCityDevelRate(CityDevelInput, throwaway-rng)` per city
  (the adapter already does this for the acting city in `cityDevelRateOf(city)` → `cityDevelRate` map).
  For the per-cityId TRIPLE lambda: build `CityDevelInput` from `toLogicCity(world.getCityById(cityId))`,
  call `calcCityDevelRate`, map each `(develKey, DevelScore(score, statType))` → `Triple(develKey, score,
  statTypeToFlag(statType))` where `statTypeToFlag`: LEADERSHIP→`T_TONGSOLJANG`(4), INTEL→`T_JIJANG`(2),
  STRENGTH→`T_MUJANG`(1) (AiInstanceState flags). PRESERVE the `trust,pop,agri,comm,secu,def,wall`
  insertion order (the GREEN `linkedMapOf` already does). ZERO draws (throwaway rng, never the decision rng).
- **(c)** PHP `calcCityDevelRate` `:3965-3976` returns `develKey → [ratio, statType]`; `do내정워프` walks the
  pairs filtered by `genType & develType`. The acting-city `cityDevelRate` (GenDomestic) is
  `Util::squeezeFromArray(calcCityDevelRate(city), 0)` (`:2131/2275`) — index-0 (the ratio), keyed by develKey.

### THE REMAINING 15

#### S4 — `nationTech = 0`
- **(a)** `do일반내정`/`do전쟁내정` (`nextTech = tech%1000 + 1` 기술연구 weight base), AND the reward
  `unitCostWithTechOf` base (S15).
- **(b)** `(world.getNationById(nationId)?.meta?.get("tech") as? Number)?.toInt() ?: 0`. (Nation has NO
  tech column — meta-backed.)
- **(c)** PHP `$nation['tech']` (`:2190/2327`).

#### S5 — `techLimited = true` / `techLimitedNextGrade = true`
- **(a)** `do일반내정`/`do전쟁내정` — gate the `che_기술연구` append.
- **(b)** REUSE the GREEN `TechLimit(startyear, year, tech)` helper (H-HELPERS §4; confirm its home — likely
  `logic/.../ai/AiUtils` or a tech helper). `techLimited = TechLimit(startYear, year, nationTech)`;
  `techLimitedNextGrade = TechLimit(startYear, year, nationTech + 1000)`.
- **(c)** PHP `:2187/2191` (일반), `:2324/2328` (전쟁).

#### S6 — `attackableCitiesOf: (nearCityIds, attackableNations) -> List<Int>`  (default `{ _,_ -> emptyList() }`)
- **(a)** `do출병` (`:2759-2769`) — the sortie target candidate list.
- **(b)** `world.listCities().filter { it.nationId in attackableNations && it.id in nearCityIds }
  .sortedBy { it.id }.map { it.id }`. PK-ascending (MariaDB `WHERE nation IN %li AND city IN %li`, no
  ORDER BY). The body owns the INPUT order (nearCityIds = `CityConst.byId(cityId).path.keys`, attackableNations
  from warTargetNation); the adapter runs the DB-row query and returns PK-asc.
- **(c)** PHP `:2759-2763` `SELECT city, nation FROM city WHERE nation IN %li AND city IN %li`.

#### S7 — `cityGeneralCountOf: (cityId) -> Int`  (default `{ 0 }`)
- **(a)** `do내정워프` (`:3076`) — the `1/(realDevelRate*sqrt(gens+1))` weight.
- **(b)** `world.listGenerals().count { it.cityId == cityId }`. PHP reads
  `count($candidate['generals'] ?? [])` = the `nationCities[cityId].generals` attached count — but the
  candidate cities are own-nation supplyCities, so `count generals in city` = the attached count. Confirm
  against `nationCities[cityId].generals.size` (post-categorize) for byte-parity; the raw
  `world.listGenerals().count { cityId }` matches when all in-city generals are own-nation (supplyCity is
  own-nation by construction).
- **(c)** PHP `:3076` `count($candidate['generals'] ?? [])`.

#### S8 — `wanderOccupiedCities = emptySet()` / `foundOccupiedCities = emptySet()`
- **(a)** `do방랑군이동` (`:3146-3152`), `do거병` (`:3238-3247`) — skip occupied targets.
- **(b)** The SAME two-SELECT union: lord cities (`officer_level=12 AND city.nation=0`) ∪ nation cities
  (`city.nation != 0`). `world.listCities().filter { it.nationId != 0 }.map { it.id }.toSet()` ∪
  `world.listGenerals().filter { it.officerLevel == 12 }.mapNotNull { it.cityId.takeIf {
  world.getCityById(it)?.nationId == 0 } }`. Membership-only (insertion not read), so a plain `Set`.
- **(c)** PHP `:3146-3152` / `:3238-3247` (identical occupied-set queries).

#### S9 — `selfCityLevel = 0` / `dupLordAtSelfCity = 0` / `movingTargetCityId = null`
- **(a)** `do방랑군이동` (`:3131/3137/3154`), `do거병` (`:3232` via cityLevel).
- **(b)** `selfCityLevel = world.getCityById(general.cityId)?.level ?: 0`;
  `dupLordAtSelfCity = world.listGenerals().count { it.officerLevel == 12 && it.cityId == general.cityId }`;
  `movingTargetCityId = (general.meta["movingTargetCityID"] as? Number)?.toInt()`.
- **(c)** PHP `:3137 getRawCity()['level']`, `:3131 COUNT(*) ... officer_level=12 AND city=cityID`,
  `:3154 getAuxVar('movingTargetCityID')`.

#### S10 — `selfMakeLimit = 0` / `selfGeneralName = ""` / `selfAffinity = 0`
- **(a)** `do거병` (`:3221 makelimit`), `do건국` (`:3307 name`), `do국가선택` (`:3359 affinity`).
- **(b)** `selfMakeLimit = (general.meta["makelimit"] as? Number)?.toInt() ?: 0`;
  `selfGeneralName = general.name`; `selfAffinity = (general.meta["affinity"] as? Number)?.toInt() ?: 0`.
- **(c)** PHP `getVar('makelimit')`, `getName()`, `getVar('affinity')`.

#### S11 — `nationCount = 0` / `notFullNationCount = 0`
- **(a)** `do국가선택` early-임관 abort (`:3365-3371`).
- **(b)** `nationCount = world.listNations().size`;
  `notFullNationCount = world.listNations().count { gennum(it) < initialNationGenLimit }` where
  `gennum` = `world.listGenerals().count { it.nationId == nation.id }` (or a nation meta field if present)
  and `initialNationGenLimit` = `GameConst.initialNationGenLimit`.
- **(c)** PHP `:3365 SELECT count(nation) FROM nation`, `:3366 ... WHERE gennum < initialNationGenLimit`.

#### S12 — `seonyangCandidates = emptyList()` / `orankaeRulerCandidates = emptyList()`
- **(a)** `do선양` (`:3324`), `do국가선택`-오랑캐 (`:3345`). F-QUAR: BOTH unreachable in 1010 (0 npc==5,
  0 npc==9) — tail paths, NOT gate paths. Still wire them faithfully (PK-asc General list).
- **(b)** `seonyangCandidates = world.listGenerals().filter { it.nationId == nationId }.sortedBy { it.id }
  .map { toLogicGeneral(it) }`; `orankaeRulerCandidates = world.listGenerals()
  .filter { it.officerLevel == 12 && it.npcState == 9 && it.nationId != 0 }.sortedBy { it.id }
  .map { toLogicGeneral(it) }`. The GREEN `min(no)` substitute applies the WHERE filter itself (0-draw).
- **(c)** PHP `:3324`/`:3345` ORDER BY RAND() (DRBG-unaffected; min(no) substitute — see G4-rand-quarantine).

#### S13 — `tradeDecision: () -> ChosenCommand?`  (default `{ null }`)
- **(a)** `do금쌀구매` (GenDomestic `:2367-2480`) — the deterministic buy/sell ladder. ZERO draws.
- **(b)** The adapter materialises the full ladder over the acting general's gold/rice + the city `trade`
  rate + `instance.maxResourceActionAmount` + `deathRate` (`$death/$kill` from general rank stats) +
  `GameConst.maxResourceActionAmount`, using `GenDomesticFamily.tradeAmount`. This is the LARGEST single
  materialisation (the whole `:2367-2480` ladder; non-trivial — may stage to a later sub-task if the gate
  doesn't exercise 금쌀구매). Default null = the body is a null no-op (catalog-sanctioned skip).
- **(c)** PHP `do금쌀구매` `:2367-2480`.

#### S14 — `recruitArmType` / `recruitArmTypeWeights` / `recruitCrewScoresFor` / `recruitFinalize`
  (defaults null/empty/`{ emptyList() }`/`{ null }`)
- **(a)** `do징병` (GenDomestic `:2526-2650`).
- **(b)** `recruitArmType = (general.meta["armType"] as? Number)?.toInt()` AFTER the genType filter
  (`:2526-2533` — null when no usable preset); `recruitArmTypeWeights` = the FOOTMAN/ARCHER/CAVALRY/WIZARD
  weight map built from fullStrength/fullIntel gates (`:2544-2552`); `recruitCrewScoresFor(armType)` =
  `GameUnitConst.byType(armType)` valid crews → pickScore(tech) in iteration order; `recruitFinalize(crewTypeId)`
  = the deterministic cost ladder (`:2585-2650`, can고급병종/can모병/half-crew/rice gate). REUSE
  `GameUnitConst` / `GenDomesticFamily.halfCrew`. Non-trivial — may stage to a later sub-task; default no-op.
- **(c)** PHP `do징병` `:2483-2651`.

#### S15 — `unitCostWithTechOf: (AiGeneralView) -> Double`  (default `{ 0.0 }`)
- **(a)** the 4 포상 reward bodies (`:1260/1364/1460/1557`) — the reqMoney base.
- **(b)** per general: `getCrewTypeObj().costWithTech(nation.tech, toInt(getLeadership(false)))`. REUSE the
  GREEN `GameUnitConst`/crew-type cost table + `nationTech` (S4) + `AiSeed.genTypeLeadership` (no-injury).
- **(c)** PHP `:1260` etc.

#### S16 — `recruitPopScoreOf: (AiGeneralView) -> Double` / `leadershipNoInjuryOf` / `reservedIsRecruitOf`
  (defaults `{ 0.0 }` / `{ it.fullLeadership }` / `{ false }`)
- **(a)** `do부대유저장후방발령`/`do유저장후방발령`/`doNPC후방발령` (recruitPopScore `<= 1` gate `:585/681/981`;
  leadershipNoInjury `:697/997` minRecruitPop; reservedIsRecruit `:601-609` che_징병 filter).
- **(b)** `recruitPopScoreOf(gv)` = `onCalcDomestic('징집인구','score',100)` pipeline score — REUSE
  `ActionPipeline`/`GeneralActionPipeline` over `toLogicGeneral(gv.general)` (the GREEN stat pipeline the
  resolve shares); `leadershipNoInjuryOf(gv)` = `AiSeed.genTypeLeadership(StatCalc(toLogicGeneral(gv.general),
  pipeline))` (no-injury, default already `it.fullLeadership` which IS that value — OK);
  `reservedIsRecruitOf(gv)` = `<reserved turn_idx 0 name> == "che_징병"` (same source as S2).
- **(c)** PHP `:585`/`:697`/`:601-609`.

#### S17 — the cutTurn/last발령 lambdas: `chiefTurnTime` / `turnTimeOf` / `last발령Of`
  (defaults `""` / `{ "" }` / `{ null }`)
- **(a)** `do부대전방발령`/`do부대후방발령` (one-deploy-per-turn skip `:320-331/429-440`),
  `do부대유저장후방발령`/`do유저장구출발령` (turnTime compares `:589-594/795`).
- **(b)** `chiefTurnTime = cutTurn(acting.turnTime, turnterm)` formatted; `turnTimeOf(gv) =
  cutTurn(gv.general.turnTime, turnterm)` — BUT `General` (logic) carries no turnTime; thread the engine
  `TurnGeneral.turnTime` via a `(generalId) -> Instant` lookup closed over the world, formatted by the
  GREEN `cutTurn` helper (H-HELPERS §2, engine wall-clock math — NOT a logic column). `last발령Of(gv) =
  (gv.general.meta["last발령"] as? Number)?.toInt()`.
- **(c)** PHP `:305/322/320` `cutTurn(getTurnTime(), turnterm)` / `getAuxVar('last발령')`.

#### S18 — `NationPassHooks` (currently only `nationGeneralId` + `useAutoNationTurn` set; the rest INERT)
- **(a)** `GeneralAI.chooseNationTurn` (`:3616-3683`) — `updateNationInstance` / `categorizeNationGeneral` /
  `categorizeNationCities` prologue; `choosePromotion` / `chooseNonLordPromotion` (MAY draw — L-RATES);
  `chooseTexRate` / `chooseGoldBillRate` / `chooseRiceBillRate` (NO draw — RatesPromoFamily); `logFailString`;
  `nationTurnTimeHm`.
- **(b)** Wire the derive hooks to the SAME `instance.updateInstance()` + `worldView.categorizeNationGeneral()`
  /`.categorizeNationCities()` calls (they are idempotent lazy-once). The rate hooks call the GREEN
  `RatesPromoFamily.texRate`/`...BillRate` over `calcNationDevelopedRate(supplyCities)` and route the chosen
  rate through `recordNationKv` (decision #12). The promotion hooks are L-RATES (MAY draw) — wire over the
  nation general buckets. `nationTurnTimeHm = getTurnTime(TURNTIME_HM)` of the chief. This is the nation-pass
  counterpart to S1+S3 and depends on them.
- **(c)** PHP `:3616-3683`.

---

## §2 — Dependency / sequencing notes
- S1 (generals) + the city feed are the FOUNDATION — every nation-pass body + S18 hooks depend on them.
  Materialise generals+cities first; the unit-GREEN `AiWorldView` bucketing then lights up.
- S2 (reservedCommandName) + S16's `reservedIsRecruitOf` share ONE reserved-turn lookup — thread it once.
- S3 (cityDevelRate triples) + S7 (cityGeneralCount) light up `do내정워프`; S3's acting-city map lights up
  the domestic family (already partially wired via `cityDevelRateOf(logicCity)`).
- S13 (trade) + S14 (recruit finalize) are the two HEAVY deterministic ladders; if the representative gate
  general does NOT exercise 금쌀구매/징병, they MAY stage to a follow-up sub-task (default no-op is
  catalog-sanctioned) — but the TDD integration test should pick a general whose first non-null body is
  cheaply materialisable (e.g. a 통솔장 with a low-trust supply city → `do일반내정`/`do전쟁내정`, OR a war
  nation chief → a real `do발령` once S1 lands).
- The NATION-pass diplo inputs (`NationDiploFamily.DiploInput/WarInput/RelocateInput`) are passed as `null`
  to `fromFamilies` today → those 3 bodies are null no-ops (decision #11 / m10 — SELECTION+boolean+draw is
  P5 scope; the diplo RESULT internals are P6). Materialising them is OPTIONAL this wave; if wired, follow
  the data-class field docs (recv_assist KV / income / isNeighbor / Floyd maps) — all read-only world reads.

## §3 — TDD red→green plan (this wave)
1. RED: an engine integration test — a representative do<한글> per family fires a REAL non-neutral command
   over a real fixture world (extend `AiTurnAdapterE2ETest`): e.g. (i) a 통솔장 npc==2 in a low-dev supply
   city fires a develop `do일반내정`/`do전쟁내정` (already passes — keep as regression); (ii) a war nation
   CHIEF with 2+ nation generals fires a real `do발령`/`do포상` (FAILS today — generals empty); (iii) a
   wandering 재야/lord-level general fires `do방랑군이동`/`do거병` (FAILS — occupied/cityLevel stubbed).
2. GREEN: materialise S1→S3 (+ the cheap S4-S12, S16-S17) into `AiTurnAdapter.chooseGeneralTurn`/
   `chooseNationTurn`; stage S13/S14/S18-promotion if the gate general doesn't need them.
3. Verify: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test :logic:test 2>&1 | tail -40`
   → BUILD SUCCESSFUL + counts; `DaemonNoEntityManagerTest` green.

## §4 — Constants/helpers to REUSE (do NOT re-implement)
`AiWorldView` (categorize, GREEN) · `RatesPromoFamily.calcCityDevelRate`/`calcNationDevelopedRate`/`texRate`/
`...BillRate` · `AiSeed.genTypeLeadership/Strength/Intel` · `AiInstanceState` flags (T_MUJANG=1/T_JIJANG=2/
T_TONGSOLJANG=4) · `AiDistance` (BFS) · `CityConst.path` (name-order adjacency) · `GameUnitConst` (crew cost) ·
`PerTurnOverlay.toLogicGeneral/toLogicCity` · `GeneralActionPipeline`/`StatCalc` · the GREEN `cutTurn`/
`joinYearMonth`/`TechLimit` helpers (H-HELPERS).
