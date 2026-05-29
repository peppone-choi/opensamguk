# P2: Non-Combat Command Surface + Full Constraint Library + Real 9-Source Stat Stack — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development`. Execute each task as a checkbox step (`- [ ]`), **TDD red→green** (write the failing test first, then the impl), **one logical commit per task**, ending every commit message with the `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` trailer. The orchestrator (not you) creates branches and commits after review; treat the inline `git`/commit lines as the intended commit boundaries, not as instructions to run git yourself. **Verify build success by tail/grep of the gradle output, never by exit code** (project memory: task-notification exit 0 is unreliable — pipe `... | tail -40` and grep for `BUILD SUCCESSFUL` / the test count). Every gradle invocation MUST be prefixed `JAVA_HOME=$(/usr/libexec/java_home -v 21)` — Gradle 8.x/9.x fails to parse Java 25. Run from repo root `/Users/apple/Desktop/개인프로젝트/opensamguk` via `./gradlew`. **Do NOT re-port the P1 substrate** — the shared constraint library / `CommandRegistry` / `GeneralActionPipeline` / `JdbcFlushExecutor` / `ChangeRecorder` / `tools/php-golden` harness are GREEN (281 tests, byte-parity gate closed); this plan **EXTENDS** them. **PHP is grand truth; TS (core2026) is a structural template only — TS reason strings, the 군량매매 buyRice overflow tax, and rounding (TS `Math.round`) ALL diverge; never use TS as the oracle.**

**Goal (design §11 P2):** Widen the proven P1 vertical slice into the full **non-combat command surface** of the devsam-core grand truth: ~36 non-combat General/Nation commands (내정 개발, 군사/이동/모병, 인사, 건국/거병, 자원교역, 비외교 nation 내부명령), the full ~73-class constraint library with PHP-exact reason strings, the **real (non-identity) 9-source stat stack** (국가타입·관직·내정특기·전투특기·성격·병종·계승·시나리오·아이템) with `getStatValue` cache + `*_exp` `checkStatChange` level-up/down logs, the 8 내정특기 + 12 성격 + 15 국가타입 + declarative item modules that feed it, the satellite write-set (rank_data / general_record→log_entry / LastTurn term-stack / cooldown KV), and the two-tier log-golden gate over all of it.

**P2 GATE (design §11 P2):** per-command **action-log byte-match** vs the PHP golden; **matched-count 0-mismatch** dashboard rises monotonically; **precheck == full** (the single shared constraint library gives identical judgments from `:app:game-api` DB-snapshot and `:app:game-engine` overlay); **flush byte-comparable** (row + jsonb byte-identical to the golden DB dump, including the new rank_data / nation / nation_turn / KV write-sets). All four green per command = that command is P2-closed.

**Architecture — EXTEND the P1 framework, never reshape it.** P1 proved the 8-step CQRS loop (Next.js→precheck→Redis reserve→daemon drain→resolve→ChangeRecorder→JDBC flush→`turnCompleted` SSE) byte-faithfully for `che_상업투자`/`che_농지개간` through an **identity** stat pipeline, a **7-preset** constraint library, a **cache-free** `getStatValue`, and a **two-step-exercised** 10-step flush. P2 fills in the breadth on that exact substrate:
- `:logic` — `CommerceInvestment` generalizes from `{comm,agri}` to all 6 develop targets; the 9-source `GeneralActionPipeline` swaps `emptyList()` for the **real ordered module list** (`getActionList` order); `getStatValue` gains `calcCache`; `EvaluateConstraints`/`Presets`/`ConstraintTypes` gain ~66 constraints + `RequirementKey.Dest*`/`NationList`/`GeneralList`/`Diplomacy`; `GeneralActionDefinition`/`GeneralActionResolveContext`/`GeneralActionDraft` gain `buildMinConstraints`/`parseArgs`/`destGeneral`/created-set/cascade + the `LastTurn` 4-field VO + capset-seq term-stack helper; `CommandRegistry` registers ~36 commands; `WorldEnvBuilder` widens to the full env surface.
- `:infra` — `JdbcFlushExecutor` implements step-8 rank_data UPSERT + the nation-cascade steps (step-3 createMany / step-5/6 cascade) the contract reserved; new `NationRowMapper`/`DiplomacyRowMapper`/`NationTurnRowMapper`; `MetaJson` reused verbatim (byte-comparable jsonb); `ReservedTurnRepository` gains a nation_turn ring.
- `:app:game-engine` — `DirtyState` (already carries nations/troops/diplomacy/created/deleted slots) gains `rankDirty`/`nationTurnDirty`/`kvDirty`; `ChangeRecorder` gains `diffNation`/dest-general diff/widened `diffCity`; `ReservedTurnHandler` gains the seed fork (`generalCommand`/`nationCommand`), the capset-seq term-stack, the per-poll KV side-effects, and the unique-item lottery seam; `PerTurnOverlay`/`WorldStateViewAdapter` thread the new columns.
- `:app:game-api` — `PrecheckStateViewFactory`/`CommandPrecheckService` thread the new entities + `parseArgs` + dest-* through the SAME `:logic` library (no re-implementation); `CommandController` accepts reqArg bodies.
- `tools/php-golden` — the proven `capture_che.php` harness generalizes to `capture_command.php` (Nation 4-arg + `nationCommand` seed branch, per-command expected-line-count, broadened `restoreBaseline`) emitting one fixture per command; the matched-count gate is the PORTED `compare-command-logs.mjs` (PHP_ROOT→`legacy/devsam-core/hwe/sammo/Command`) re-pointed PHP↔Kotlin.

**Tech stack (unchanged from P1):** Kotlin 2.1 / Spring Boot 3.4 / Spring Data JPA (read/precheck only, `:app:game-api`) / **JDBC batch** (`NamedParameterJdbcTemplate` over a pinned `DataSourceTransactionManager`, daemon write path — NO `EntityManager`) / Lettuce Redis / SHA-512 `LiteHashDrbg` (`:common`) / Testcontainers (`postgres:16-alpine`, `redis:7-alpine`; macOS Docker Desktop quirk pinned: `api.version=1.44`, `DOCKER_CONTEXT=default`, `RYUK_DISABLED`). Module layout (all exist): `:common`, `:logic`, `:infra`, `:app:game-api`, `:app:game-engine`. Test command base: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :<module>:test --tests '<fqcn>' | tail -40`.

**Parity discipline (non-negotiable, carried from P0/P1):** RNG draw-for-draw byte parity; Korean action-log byte parity (`JosaUtil` / color-tag / `<C>●</>{month}월:` prefix / `<1>HH:MM</>` / ordering); **precheck == full via the SINGLE shared constraint library — NO double implementation**; the daemon write path **NEVER** uses a JPA `EntityManager` (enforced structurally + by `DaemonNoEntityManagerTest` + `InfraNoEntityManagerTest`); **PHP is grand truth, TS is structural template**; `Util::round` = half-AWAY-from-zero (`phpRound`); `Util::toInt` = truncate-toward-zero; integer division; `number_format` comma grouping; jsonb key INSERTION order.

**Per-action RNG seed (PHP grand truth — `TurnExecutionHelper.php:340-347` General, `:310-317` Nation fork; preprocess fork `:280-286`):**
```
General:     serializeSeed(hiddenSeed, "generalCommand", year, month, generalId, rawClassName)
Nation:      serializeSeed(hiddenSeed, "nationCommand",  year, month, generalId, rawClassName)
Preprocess:  serializeSeed(hiddenSeed, "preprocess",     year, month, generalId)            # 5-tuple, NO classname
```
`rawClassName` = `$cmdObj->getRawClassName()` = `getRawClassName(true)` (`BaseCommand.php:261-266` → `Util::getClassNameFromObj($this)` → `ReflectionClass->getShortName()`) = **the registry class SHORT NAME INCLUDING the `che_`/`cr_` prefix** (e.g. `che_랜덤임관`, `cr_맹훈련`, `cr_건국` — `cr_건국` is a DISTINCT class from `che_건국`). It is **NOT** `definition.key`, **NOT** a de-spaced `actionName`. The seed component is the short class name verbatim; carry it explicitly per command as `GeneralActionDefinition.rawClassName` (see F-SUBSTRATE) — pin it for `che_랜덤임관`/`cr_맹훈련`/`cr_건국` where class ≠ `che_`+de-spaced-name. (상업투자/농지개간 worked in P1 only because there the class name coincidentally equals `che_`+de-spaced action name.) `hiddenSeed` is a per-game random captured ONCE per install in G1 and committed as a golden FIXTURE INPUT (not a `:common` constant), shared across all fixtures from the same install. The **preprocess** fork (`preprocessCommand`) uses the 5-tuple variant above with NO classname — pin it separately. `tryUniqueItemLottery` uses a SEPARATE rng (`genGenericUniqueRNGFromGeneral`, NPCType>=2 short-circuit) seeded with a DIFFERENT token: `serializeSeed(hiddenSeed,'unique',year,month,generalID, static::$actionName)` — the lottery token is `static::$actionName` (e.g. `che_랜덤임관.php:284` passes `static::$actionName = '무작위 국가로 임관'`), NOT `rawClassName`. Pin BOTH tokens separately: action seed = `rawClassName`; lottery seed = `actionName`. The lottery fires AFTER all DB writes + `checkStatChange` and must not perturb the action draw stream.

**Reference sources (read before transcribing):**
- P2 RESEARCH (the consolidated legacy mining + Tier-0..Tier-3 dependency graph + 15 OQs): `docs/superpowers/research/2026-05-29-p2-research.md` — every Unit's `Source refs`, `Pinned port_notes`, `Gaps` are the per-task transcription spec.
- PHP grand truth: `legacy/devsam-core/hwe/sammo/Command/{General,Nation}/*.php`, `Constraint/*.php`, `GameUnitConstraint/*.php`, `ActionSpecialDomestic/*.php`, `ActionPersonality/*.php`, `ActionNationType/*.php`, `ActionItem/*.php`, `General.php`, `Util.php`, `func_*.php`, `GameConstBase.php`, `LastTurn.php`, `KVStorage.php`, `Event/Action/RandomizeCityTradeRate.php`/`RaiseNPCNation.php`.
- P1 plan (FORMAT + the substrate P2 extends): `docs/superpowers/plans/2026-05-29-p1-vertical-slice.md`.
- Design roadmap §11 P2 / §12 / §14: `docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md`.

---

## File Structure

All paths relative to repo root `/Users/apple/Desktop/개인프로젝트/opensamguk`. `[NEW]` = create, `[EDIT]` = modify existing P1 substrate. **Do NOT re-create** the constraint library / `CommandRegistry` / pipeline / flush — EXTEND them.

```
logic/src/main/kotlin/opensamguk/logic/
  domain/
    LogicEntities.kt                         # [EDIT] General += crew/train/atmos/crewTypeId/troop/horse/weapon/book/item/npcType + aux (leadershipExp/strengthExp/dedlevel ride meta — NO columns); City += security/defense/wall/pop(+each _max)/trade/region (NO city.tech); Nation full shape (name/color/typeCode/gold/rice/tech/gennum/capset/capitalCityId + meta rate/bill/surlimit/secretlimit/strategicCmdLimit)
    Diplomacy.kt                             # [NEW] directional-pair diplomacy logic entity (me,you,state,term)
    NationTurn.kt                            # [NEW] per-(nation,officerLevel,turnIdx) reserved nation-command row VO
    GeneralMeta.kt                           # [EDIT] +*_exp/explevel/dedlevel/npcType/last발령 accessors (metaInt/metaDouble/withMeta reused)
    NpcType.kt                               # [NEW] NPCType taxonomy (0,1,2,3,5,6,9) + getNpcType(general)
    LastTurn.kt                              # [NEW] 4-field VO {command,arg,term,seq}; toRaw OMITS null term/seq (delete-on-default jsonb)
  constraints/
    ConstraintTypes.kt                       # [EDIT] RequirementKey += DestGeneral/DestCity/DestNation/NationList/GeneralList/Diplomacy; ConstraintContext += destGeneralId/destCityId/destNationId
    Presets.kt                               # [EDIT] +~66 presets (PHP reason strings + order); keep the 7 P1 presets verbatim
    Comparators.kt                           # [NEW] compareValues (8 comparators) + parsePercent + derived reason text (Req*Value family)
  stats/
    ActionPipeline.kt                        # [EDIT] GeneralActionModule iface += onCalcStrategic/onCalcNationalIncome/onCalcOpposeStat + aux param; GeneralActionPipeline += those folds; module-order constant
    GetStatValue.kt                          # [EDIT] add calcCache (floored/unfloored write asymmetry + clear-on-mutate) — cross-stat/clamp/truncate already correct
    GeneralActionModuleFactory.kt            # [NEW] build the ordered 9-source module list for a General (getActionList order)
  traits/
    ActionSpecialDomestic.kt                 # [NEW] 8 domestic-specialty modules (경작/상재/발명/축성/수비/통찰/인덕/귀모) + None/거상 inert
    ActionPersonality.kt                     # [NEW] 12 personality modules + PersonalityRegistry
    ActionNationType.kt                      # [NEW] 15 nation-type modules + NationTypeRegistry (neutral che_중립)
    OfficerLevelModule.kt                    # [NEW] TriggerOfficerLevel onCalc* (source #2)
  items/
    ItemModules.kt                           # [NEW] BaseStatItem factory (declarative +stat books/weapons/horses) + ItemRegistry + 4-slot dedup
    ItemHooks.kt                             # [NEW] domestic-hook items (납금박산로/조달주판/계략 books) + override-stat items (능력치 year-scaled/동작 addDex/평만지장도 delay)
  domestic/
    DomesticHelpers.kt                       # [EDIT] + TechLimit + exchangeFee/tradeRate + getExpLevel/getDedLevel/getDedLevelText/getBillByLevel + income helpers (calcCityGold/Rice/WallIncome/getOutcome)
    StatChange.kt                            # [NEW] checkStatChange + addExperience + addDedication PLAIN logs (레벨업/다운/승급/강등); upgradeLimit=30
    UniqueItemLottery.kt                     # [NEW] genGenericUniqueRNGFromGeneral + tryUniqueItemLottery (NPCType>=2 short-circuit, separate rng)
  actions/
    GeneralActionDefinition.kt               # [EDIT] += buildMinConstraints (min/full split) + parseArgs(argsSchema)
    GeneralActionResolveContext.kt           # [EDIT] GeneralActionDraft += destGeneral/destCity/destNation + created-set (Nation/Diplomacy/NationTurn lists) + cascade collections; ctx += addLogTo(target)/addGlobalActionLog/addPlainLog
    CommerceInvestment.kt                    # [EDIT] cityKey/statKey switch widens to {comm,agri,secu,def,wall} (NO tech — che_기술연구 writes nation.tech, see DV2); statKey-driven getStatValue
    develop/{CheGisulYeongu,CheJeongchakJangnyeo,CheJuminSeonjeong,CheMuljaJodal,CheGunryangMaemae}.kt  # [NEW] develop commands not pure CommerceInvestment subclasses
    military/{RecruitAlgorithm,CheHullyeon,CrMaenghullyeon,CheSagiJinjak,CheSojipHaeje,CheIdong,CheJiphap,UnitSetTable}.kt  # [NEW] military/movement
    personnel/{CheImgwan,CheJangsuDaesangImgwan,CheRandomImgwan,CheHaya,CheBangrang,CheEuntwe,CheDeungyong}.kt  # [NEW] personnel (등용 SEND only; 등용수락/ScoutMessage mailbox DEFERRED to P6)
    founding/{CheGeobyeong,CheGeonguk,CrGeonguk,CheMujakwiGeonguk,FoundingCascade}.kt   # [NEW] 건국/거병/방랑(shared w/ personnel)
    nation/{NationCommand,CheGamchuk,CheJeungchuk,CheBallyeong,ChePosang,CheGukhoByeongyeong,CheGukgiByeongyeong,CheCheondo,CheMujakwiSudoIjeon}.kt  # [NEW] nation-internal (extends NationCommand base)
    trade/{CheJeungyeo,CheHeonnap,CheJangbiMaemae}.kt   # [NEW] trade (군량매매 lives in develop/)
    CommandRegistry.kt                       # [EDIT] register ~36 commands + category('내정'/'군사'/'인사'/'국가'/'교역') + argsSchema + non-reservable flag
  world/
    RandomizeCityTradeRate.kt                # [NEW] monthly seeded world-tick (per-city RNG); used by 군량매매 golden precondition
    CalcCityDistance.kt                      # [NEW] F-MAP Wave-1 foundation: pure BFS over CityConst path adjacency; 이동(MIL5)/천도(NI5)/NearCity land on it (full pathfinding HasRoute*/NearNation DEFERRED)
  src/test/kotlin/opensamguk/logic/...       # [NEW] per-task tests (mirroring P1 layout: constraints/, stats/, traits/, items/, domestic/, actions/{develop,military,personnel,founding,nation,trade}/, golden/)
  src/test/resources/golden/p2/              # [NEW] committed per-command PHP goldens (<command>-fixtures.json) + golden DB fragments

infra/src/main/kotlin/opensamguk/infra/persistence/
  NationRowMapper.kt                         # [NEW] nation row ↔ logic Nation (jsonb meta order-preserving via MetaJson)
  DiplomacyRowMapper.kt                      # [NEW] diplomacy row ↔ logic Diplomacy
  NationTurnRowMapper.kt                     # [NEW] nation_turn row ↔ NationTurn
  JdbcFlushExecutor.kt                       # [EDIT] widen step-7 general/city UPDATE SET; implement step-3 createMany (nation/diplomacy/nation_turn/general), step-5/6 cascade (deleteMany + nation revert), step-8 rank_data UPDATE (37 pre-seeded rows/general — RANK_ROWS_PER_GENERAL=37) + nation_id sync; 10-step ORDER FROZEN
  ReservedTurnRepository.kt                  # [EDIT] + nation_turn ring (MAX_CHIEF_TURNS=12, key nation_id×officer_level×turn_idx) + rotate-to-휴식 on pull
  GeneralRowMapper.kt                        # [EDIT] map the new General columns + aux jsonb delete-on-null
  CityRowMapper.kt                           # [EDIT] map the new City columns (tech/security/defense/wall/pop + each _max/trade)

app/game-engine/src/main/kotlin/opensamguk/engine/
  turn/
    DirtyState.kt                            # [EDIT] += rankDirty (Map<generalId, Map<RankColumn, Increment|Set>>) + nationTurnDirty + kvDirty (nation_env key→json|delete); nations/troops/diplomacy/created/deleted ALREADY present
    TurnWorldModel.kt                        # [EDIT] TurnGeneral/City/Nation expose the new columns; RankColumn enum; KV map
    ChangeRecorder.kt                        # [EDIT] += diffNation (gold/rice/capset/capital/name/color/aux) + destGeneral diff + widened diffCity + rankVar 3-Map collapse
    ReservedTurnHandler.kt                   # [EDIT] seed fork (generalCommand/nationCommand) + capset-seq addTermStack + per-poll KV side-effects (last천도Trial) + unique-item lottery + dual-general apply + multi-general cascade
    PerTurnOverlay.kt                        # [EDIT] thread the new columns; multi-entity staged writes (created nations / cascade)
    WorldStateViewAdapter.kt                 # [EDIT] resolve Dest*/NationList/GeneralList/Diplomacy RequirementKeys
  flush/
    DatabaseHooks.kt                         # [EDIT] map widened DirtyState → FlushPayload; keep 10-step order; thread rank/nation_turn/KV
    FlushOp.kt                               # [EDIT] FlushOpRecorder gains rank_data/nation/nation_turn/diplomacy op tags (order tests)

app/game-api/src/main/kotlin/opensamguk/gameapi/
  precheck/{PrecheckStateViewFactory,CommandPrecheckService}.kt   # [EDIT] load new entities + dest-* + parseArgs through the SAME :logic library
  read/{NationReadRepository,DiplomacyReadRepository}.kt          # [NEW]/[EDIT] JPA read repos for nation/diplomacy (precheck only)
  web/CommandController.kt                                        # [EDIT] accept reqArg request bodies (amount/destGeneralId/destCityId/destNationId/nationName)

tools/php-golden/
  capture_command.php                        # [NEW] generalize capture_che.php: Nation 4-arg + nationCommand seed branch, per-command expected-line-count, broadened restoreBaseline, multi-general/broadcast capture
  probe_command.php                          # [NEW] generalize probe_picks.php: module-free general + distinct reachable outcomes per command (synthetic-seed fallback)
  manifest.json                              # [NEW] the ~36-command P2 manifest (per-command: ctor type, RNG draws, log-line count, cross-general/broadcast, level-cross)
  compare-command-logs/                      # [NEW] PORT of legacy/devsam-core2026/tools/compare-command-logs.mjs (PHP_ROOT→legacy/devsam-core/hwe/sammo/Command), re-pointed PHP↔Kotlin: keep PHP extractor + normalizer + ignore-list; new Kotlin source extractor; matched-count report
  compare-command-logs.ignore.json           # [NEW] the ignore-list backlog (~57-of-93 deferred commands explicitly listed)
```

> **Build prerequisites (one-time, performed by the relevant Tier-0 task):**
> - No new module deps: `logic → common`, `infra → common + logic`, apps → all. Acyclic (P1 already wired `:infra → :logic`).
> - `spring-jdbc` already on the `:infra` classpath via `spring-boot-starter-data-jpa` (boot BOM) — do NOT pin a version.
> - `:logic` test deps (`kotlinx.serialization.json`, `kotlin("test")`) already present from P1 — verify, never re-add.

---

# Dependency graph & parallelization (Tier 0 → Tier 1 → Tier 2 → Tier 3)

The research's area decomposition (research lines 925-989) is the authority. The P2 areas below map 1:1 onto its Tier-0..Tier-3 graph. **Three foundation tiers gate the per-command fan-out; the resolver families + trait/item families run in parallel once their foundations land.**

```
Tier 0 (Foundations — no deps among themselves except as noted; the FIRST parallel wave):
  F-DOMAIN     (logic+infra: entity expansion + row mappers + read repos)          ── gates F-FLUSH, all CMD-* that mutate new columns
  F-SUBSTRATE  (logic: action-substrate widening + LastTurn VO + capset helper)    ── gates personnel/founding/nation resolvers
  F-PIPELINE   (logic: GeneralActionModule iface widen + getStatValue calcCache)   ── gates all trait/item/stat-stack work
  F-RNG        (logic: unique-item lottery seam + NPCType + RNG-kernel verify)      ── gates any per-command golden
  F-FLUSH      (engine+infra: DirtyState/ChangeRecorder/JdbcFlushExecutor satellite) ── depends on F-DOMAIN
  F-GOLDEN-0   (tools: P2 command manifest + capture_command.php generalize)        ── depends on P1 harness GREEN (it is)
  F-MAP        (logic/common: CalcCityDistance — pure BFS over CityConst path adj.)  ── gates 이동 (MIL5) + 천도 (NI5) distance; full pathfinding (HasRoute*/NearNation) DEFERRED to map/diplomacy phase

Tier 1 (Libraries — depend on Tier 0):
  C-PURE   ← F-DOMAIN              (~30 pure state constraints + 6 comparators + percent)
  C-DEST   ← F-SUBSTRATE, C-PURE   (dest-* constraints + RequirementKey.Dest*/list kinds)
  S-MODULES ← F-PIPELINE           (9-source module registry: nationType/officer/specialDomestic/personality/crew-stub/inherit-stub/scenario-stub/items)
  S-LEVEL  ← F-PIPELINE            (checkStatChange + addExperience/addDedication PLAIN logs)

Tier 2 (Resolver families — parallel once foundations land):
  CMD-DEVELOP        ← F-DOMAIN,S-MODULES,C-PURE,F-RNG,F-GOLDEN-0
  CMD-MILITARY       ← F-DOMAIN,C-PURE(+11 mil),F-RNG,F-GOLDEN-0,F-MAP(이동 only)
  CMD-PERSONNEL      ← F-SUBSTRATE,C-DEST,F-RNG,F-GOLDEN-0
  CMD-FOUNDING       ← F-SUBSTRATE,F-DOMAIN,C-PURE+C-DEST,F-FLUSH,F-RNG  (FND2 depends-on PR2: shared FoundingCascade.kt)
  CMD-NATION-INTERNAL← F-SUBSTRATE,F-DOMAIN,C-DEST,F-FLUSH,F-GOLDEN-0,F-MAP(천도 only)
  CMD-TRADE          ← F-DOMAIN,C-PURE(+trader/dest),F-RNG,F-GOLDEN-0  (장비매매 AFTER ITEMS)
Tier 2 (Traits/items — depend on S-MODULES):
  TRAITS-DOMESTIC    ← S-MODULES
  TRAITS-PERSONALITY ← S-MODULES
  TRAITS-NATION      ← S-MODULES
  ITEMS              ← F-DOMAIN(item slots), S-MODULES   (feeds CMD-TRADE.장비매매)

Tier 3 (Gates — depend on the families they cover; serial close):
  GATE-RUNTIME   ← F-GOLDEN-0 + each CMD-*           (per-command Kotlin golden tests)
  GATE-STATIC    ← each CMD-* + TRAITS-*             (compare-command-logs PHP↔Kotlin matched-count 0-mismatch)
  GATE-SATELLITE ← F-FLUSH + CMD-NATION/PERSONNEL/FOUNDING  (rank_data/log_entry/nation_turn/KV write-set goldens)
  GATE-TRAIT     ← TRAITS-* + ITEMS                 (non-default-general non-identity-fold golden)
```

**Parallel worktree fan-out (the executor runs `{…}∥` waves like P1's `{A}→{B,D}→…`):**

- **Wave 1 (Tier 0, mostly independent):** `{F-DOMAIN, F-SUBSTRATE, F-PIPELINE, F-RNG, F-GOLDEN-0, F-MAP}∥`. These touch disjoint files (F-DOMAIN = entities+mappers; F-SUBSTRATE = actions/ + LastTurn; F-PIPELINE = stats/; F-RNG = domestic/UniqueItemLottery + NpcType; F-GOLDEN-0 = tools/; F-MAP = `logic/world/CalcCityDistance.kt` (or `:common`) — pure BFS over `CityConst.kt`'s existing golden-locked bidirectional `path` adjacency, no shared edits). **One sync edge:** F-DOMAIN edits `LogicEntities.kt` which F-SUBSTRATE/F-PIPELINE also import — land F-DOMAIN's `LogicEntities.kt` entity-shape commit FIRST (its Task 0), then the rest of Wave 1 rebases on the new shape. **F-FLUSH waits for F-DOMAIN** (needs the new entities/mappers) → runs in Wave 1.5 alongside the tail of Wave 1.
- **Wave 2 (Tier 1):** `{C-PURE, C-DEST(after C-PURE), S-MODULES, S-LEVEL}∥`. C-PURE and S-MODULES/S-LEVEL are fully independent (constraints/ vs stats/+traits/). C-DEST depends on C-PURE + F-SUBSTRATE.
- **Wave 3 (Tier 2 resolvers + traits, the big fan-out):** `{CMD-DEVELOP, CMD-MILITARY, CMD-PERSONNEL, CMD-FOUNDING, CMD-NATION-INTERNAL, TRAITS-DOMESTIC, TRAITS-PERSONALITY, TRAITS-NATION, ITEMS}∥` — **all 9 file-disjoint within their own `actions/<family>/` + `traits/<family>/` + `items/` subtrees**, with the **shared-file protocol** below making the cross-family edits collision-free. There are **4 real shared Wave-3 files** (NOT just `CommandRegistry.kt`); each is moved to a Tier-1/Tier-2-prerequisite OWNER so no two Wave-3 families ever co-edit:
>   - **`Presets.kt`** — ALL preset additions are a **Wave-2 prerequisite**: `C-PURE` CREATES the pure/comparator presets and `C-DEST` CREATES the dest/diplomacy presets; the founding/military families (FND1, MIL1) **only CONSUME** them. (No "skip if already added" hedge — `FND1`/`MIL1` HARD-depend on C-PURE/C-DEST having created every preset they reference; if a founding/military-only preset is missing, it is added in C-PURE/C-DEST, not in the family task.)
>   - **`GeneralActionModuleFactory.kt`** — CREATED in `S-MODULES` (Tier-1 foundation); the trait/item families (TD1/TP1/TN1/IT1/IT2) only **register INTO it via append** (each family contributes its module list to a per-family registry that S-MODULES aggregates) — `S-MODULES` is the sole OWNER/editor of the factory body.
>   - **`FoundingCascade.kt`** — CREATED by a Tier-1 foundation step that BOTH `PR2` (하야/방랑) and `FND2` (방랑 resolver) consume; the dependency graph carries an explicit **cross-edge `FND2 depends-on PR2`** (foundation route): PR2 creates `FoundingCascade.kt`, FND2 wires 방랑 onto it. They never co-create it.
>   - **`DomesticHelpers.kt`** — a pre-existing P1 file; the level-conversion helpers (`getExpLevel`/`getDedLevel`/`getBillByLevel`/TechLimit base) are added ONCE by `SL1` (S-LEVEL, Tier-1) as the shared substrate; `DV2` (TechLimit consumer) and `IT3` (income helpers) only add/consume **disjoint helper keys** within it (no overlapping function bodies) — S-LEVEL owns the conversion-helper block.
>   - **`CommandRegistry.kt`** — the only genuinely per-family append point; append-only registration per family (no two families touch the same `when` arm) so merges stay trivial.
>   With this protocol each Wave-3 family touches ONLY its own `actions/<family>/` + `traits/<family>/` + `items/` subtree + the append-only `CommandRegistry.kt` registration. **CMD-TRADE** runs in this wave for 군량매매/증여/헌납 but its 장비매매 task waits for ITEMS (cross-edge). **천도 (in CMD-NATION-INTERNAL) and 이동 (in CMD-MILITARY)** both land on the `CalcCityDistance` map module (now a **Wave-1 foundation**, Q9 RESOLVED) — see Wave 1; they no longer block on a Wave-3 module.
- **Wave 4 (Tier 3 gates, serial):** `GATE-RUNTIME → GATE-STATIC → GATE-SATELLITE → GATE-TRAIT`. Each gate aggregates the per-command goldens its covered families produced in Wave 3; run after the resolver waves are green.

**Independent area-sets (safe to run in the same parallel worktree wave):** Wave 1 `{F-DOMAIN, F-SUBSTRATE, F-PIPELINE, F-RNG, F-GOLDEN-0}`; Wave 2 `{C-PURE, S-MODULES, S-LEVEL}` (+ C-DEST after C-PURE); Wave 3 `{CMD-DEVELOP, CMD-MILITARY, CMD-PERSONNEL, CMD-FOUNDING, CMD-NATION-INTERNAL, CMD-TRADE, TRAITS-DOMESTIC, TRAITS-PERSONALITY, TRAITS-NATION, ITEMS}`.

---

# TIER 0 — Foundations

## AREA F-DOMAIN — Entity expansion + row mappers + read repos (gates everything that mutates new columns)

> **Port target = PHP `General.php`/`City` schema + `LastTurn.php` for shape; the V1 baseline columns for names.** Research §Cross-cutting #1, Units 1/2/4/5/11, OQ2. **Land Task FD0 (the `LogicEntities.kt` shape commit) FIRST** so the rest of Wave 1 rebases on it.

### Task FD0 — Expand `LogicEntities` (General/City/Nation) + new entities
**Files:** edit `logic/domain/LogicEntities.kt`; create `logic/domain/{Diplomacy,NationTurn,NpcType,LastTurn}.kt`; edit `logic/domain/GeneralMeta.kt`; create tests `logic/src/test/.../domain/{LogicEntitiesTest,LastTurnTest,NpcTypeTest}.kt`.
Steps:
- [ ] Extend `General` (keep all P1 fields; ADD): `crew: Int`, `train: Double`, `atmos: Double`, `crewTypeId: Int`, `troop: Int`, `horse: String?`, `weapon: String?`, `book: String?`, `item: String?`, `npcType: Int`, `lastTurn: LastTurn` (rides the `general.last_turn` jsonb column — general-command `setResultTurn` target; `toRaw()` delete-on-default). **`leadershipExp`/`strengthExp`/`dedlevel` ride the general `meta` jsonb (accessors via meta, NOT new columns) — DECISION: verified against `V1__baseline.sql` (the `general` table has crew/crew_type_id/train/atmos/weapon_code/book_code/horse_code/item_code/last_turn/personal_code columns but NO `leadership_exp`/`strength_exp`/`dedlevel`/`intel_exp` columns); consistent with P1's `intel_exp`/`explevel` which already ride meta. NO migration for these — pin their `meta` insertion order against the golden `general.meta` dump (GATE-SATELLITE).** `aux` jsonb rides `meta` (delete-on-null).
- [ ] Extend `City` (ADD): `security/securityMax`, `defense/defenseMax`, `wall/wallMax`, `population/populationMax`, `trade: Int?` (95..105 or null), `region: Int`. Names align to V1 `secu/def/wall/pop/trade` columns (verified against `V1__baseline.sql`: city has pop/pop_max/agri/agri_max/comm/comm_max/secu/secu_max/trust/trade/def/def_max/wall/wall_max/region — **NO city.tech**). **There is NO `city.tech` — tech is a NATION stat (`V1 nation.tech double precision`); do NOT add tech/techMax to City.**
- [ ] Replace minimal `Nation` with full shape: `id, level, capitalCityId, name, color, typeCode, gold, rice, tech, gennum, capset, meta(rate/bill/surlimit/secretlimit/strategicCmdLimit/aux)`.
- [ ] Create `Diplomacy(me: Int, you: Int, state: Int, term: Int)`; `NationTurn(nationId, officerLevel, turnIdx, action: String, arg: Map?, brief: String = "")` — **brief column DECISION (research OQ11): PHP writes `brief` on both general_turn AND nation_turn (verified `che_거병.php:151` inserts `'brief'=>'휴식'` on all 24 nation_turn rows); V1 baseline `general_turn`/`nation_turn` tables have NO `brief` column → add it via a NEW V2 Flyway migration (see FD0a below). Model `brief` as a non-null `String` defaulting `''`.**
- [ ] Create `NpcType.kt`: the taxonomy (0=user,1,2=NPC-lite,3,5,6,9=이민족) + `getNpcType(general): Int` (research OQ8 — port once, used by lottery short-circuit / 하야 gennum / trade-without-trader).
- [ ] Create `LastTurn.kt`: 4-field VO `(command: String, arg: Map<String,Any?>?=null, term: Int?=null, seq: Int?=null)`; `toRaw()` ALWAYS emits `command` (default `휴식`), emits `arg`/`term`/`seq` ONLY when non-null (delete-on-default jsonb — `LastTurn.php:74-88`).
- [ ] Tests: `LogicEntitiesTest` round-trips a fully-populated General/City/Nation; `LastTurnTest` asserts `toRaw()` of `LastTurn("휴식")` == `{"command":"휴식"}` (no term/seq keys) and a 4-field one emits all keys in order; `NpcTypeTest` covers each taxonomy branch.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.domain.*' | tail -40`.
- [ ] Commit: `feat(logic/domain): expand General/City/Nation + Diplomacy/NationTurn/NpcType/LastTurn (P2 entity shape)`.

### Task FD0a — V2 migration: `brief` column on general_turn + nation_turn
**Files:** create `infra/src/main/resources/db/migration/V2__p2_brief.sql`; test `infra/src/test/.../persistence/V2BriefMigrationTest.kt` (Testcontainers postgres + Flyway).
Steps:
- [ ] Add a NEW V2 Flyway migration `V2__p2_brief.sql` that adds `brief text NOT NULL DEFAULT ''` to BOTH `general_turn` AND `nation_turn` (the V1 baseline tables have no `brief`; PHP writes it — `che_거병.php:151` inserts `'brief'=>'휴식'` on all 24 nation_turn rows). Do NOT touch V1 (frozen baseline). Bare `ALTER TABLE ... ADD COLUMN brief text NOT NULL DEFAULT ''` for each table.
- [ ] Test: Flyway migrates V1→V2 clean; `general_turn.brief` and `nation_turn.brief` exist with default `''`.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests 'opensamguk.infra.persistence.V2BriefMigrationTest' | tail -30`.
- [ ] Commit: `feat(infra/migration): V2__p2_brief — brief column on general_turn + nation_turn`.

### Task FD1 — Thread new columns through StateView + JDBC row mappers
**Files:** edit `infra/persistence/{GeneralRowMapper,CityRowMapper}.kt`; create `infra/persistence/{NationRowMapper,DiplomacyRowMapper,NationTurnRowMapper}.kt`; tests `infra/src/test/.../persistence/*MapperTest.kt`.
Steps:
- [ ] Extend `GeneralRowMapper`/`CityRowMapper` to read+write the new columns (aux jsonb via `MetaJson` delete-on-null; train/atmos as float columns; exp/ded truncate-toward-zero at flush per P1). **`leadership_exp`/`strength_exp`/`dedlevel` are read/written through `meta` (NO dedicated columns) — `GeneralRowMapper.toColumns` packs them into the `meta` jsonb (matching the golden insertion order), and the row→entity path reads them back from `meta`, exactly as P1 does for `intel_exp`/`explevel`. Map `general.last_turn` (jsonb) ↔ `General.lastTurn` via `LastTurn.toRaw()` (delete-on-default, byte-comparable jsonb via `MetaJson`).
- [ ] Create `NationRowMapper` (jsonb meta order-preserving via `MetaJson` — the proven P1 encoder), `DiplomacyRowMapper`, `NationTurnRowMapper` (`arg` jsonb + `brief` as a plain `text` column — NOT jsonb; V2 added it). The general_turn ring mapper (ReservedTurnRepository) likewise reads/writes the new `general_turn.brief text` column.
- [ ] Tests: round-trip each entity row→entity→column-map; assert jsonb key INSERTION order preserved + integers serialize as `5` not `5.0` (reuse the P1 `MetaJson` assertion pattern).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests 'opensamguk.infra.persistence.*MapperTest' | tail -40`.
- [ ] Commit: `feat(infra/persistence): Nation/Diplomacy/NationTurn row mappers + widened General/City mappers`.

### Task FD2 — JPA read repos (precheck only) for the new entities
**Files:** edit `app/game-api/read/{GeneralReadRepository,CityReadRepository,NationReadRepository}.kt`; create `read/DiplomacyReadRepository.kt`; edit `precheck/PrecheckStateViewFactory.kt`; test `app/game-api/src/test/.../read/ReadRepositoryIT.kt`.
Steps:
- [ ] Widen the read `@Entity` mappings for the new general/city/nation columns (read path; order need not be preserved on read). Add a `DiplomacyReadRepository`. The read entity MUST NOT declare `intel_exp` (lives in meta, OQ5).
- [ ] Edit `PrecheckStateViewFactory` to load the new columns into the `MemoryStateView` + the diplomacy view + nation full shape.
- [ ] Test (Testcontainers postgres + Flyway): seed full rows, load, assert materialization.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests 'opensamguk.gameapi.read.ReadRepositoryIT' | tail -30`.
- [ ] Commit: `feat(game-api/read): widened read repos + diplomacy repo for precheck`.

**F-DOMAIN gate:** mappers round-trip byte-comparable jsonb; finalized against GATE-SATELLITE golden DB fragments.

## AREA F-SUBSTRATE — Action-substrate widening + capset term-stack (gates dest/nation/founding resolvers)

> **Port target = PHP `BaseCommand.php`/`NationCommand.php`/`GeneralCommand` ctor + `LastTurn` capset-seq term-stack.** Research §Cross-cutting #2, Units 3/4/5/13.

### Task FS1 — Widen `GeneralActionDefinition` + `GeneralActionResolveContext`/`Draft`
**Files:** edit `logic/actions/{GeneralActionDefinition,GeneralActionResolveContext}.kt`; test `logic/src/test/.../actions/ActionSubstrateTest.kt`.
Steps:
- [ ] `GeneralActionDefinition` += `buildMinConstraints(ctx): List<Constraint>` (min/precheck split — default delegates to `buildConstraints` for commands without a split) + `parseArgs(raw: Map<String,Any?>): Map<String,Any?>` (default identity) + `argsSchema` + `reservable: Boolean = true` + `category: String` + **`rawClassName: String`** (the PHP short class name incl `che_`/`cr_` prefix = `getRawClassName(true)` = the 6th RNG seed component; carried EXPLICITLY per command, NOT derived from `key`/`actionName`). Pin `rawClassName` for the divergent commands where class ≠ `che_`+de-spaced-name: `che_랜덤임관` (vs actionName `무작위 국가로 임관`), `cr_맹훈련`, `cr_건국` (DISTINCT from `che_건국`). Note 상업투자/농지개간 passed in P1 only by coincidence (their class == `che_`+de-spaced-name). Keep the lottery token (`static::$actionName`) as a SEPARATE definition field where used (e.g. che_랜덤임관) — do NOT conflate it with `rawClassName`.
- [ ] `GeneralActionDraft` += `var destGeneral: General?`, `var destCity: City?`, `var destNation: Nation?`, `val createdNations: MutableList<Nation>`, `val createdDiplomacy: MutableList<Diplomacy>`, `val createdNationTurns: MutableList<NationTurn>`, `val cascadeGenerals: MutableList<General>`, `val cascadeCities: MutableList<City>`, `val cascadeDiplomacy: MutableList<Diplomacy>`.
- [ ] `GeneralActionResolveContext` += `addLogTo(targetGeneralId, text)` (dest-general logger — separate scope), `addGlobalActionLog(text)` (broadcast, general_id=0), `addPlainLog(text)` (no MONTH prefix — `<1>HH:MM</>`-only lines like 이동/집합 per-target). Keep the P1 `addLog` (MONTH prefix) verbatim.
- [ ] Test: a definition with `buildMinConstraints != buildConstraints`; a draft carrying a destGeneral + a created nation; `parseArgs` round-trip; the three log scopes emit distinct buckets.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.ActionSubstrateTest' | tail -30`.
- [ ] Commit: `feat(logic/actions): widen action definition/draft/context (min-vs-full, args, dest, created-set, cascade, log scopes)`.

### Task FS2 — Capset-seq term-stack helper + LastTurn integration
**Files:** create `logic/actions/TermStack.kt`; test `logic/src/test/.../actions/TermStackTest.kt`.
Steps:
- [ ] Port the capset-seq `addTermStack` (research Unit 5 port_notes): `LastTurn(name, arg, term, seq=nation.capset)`. Reset `term→1` when command/arg differs OR `lastTurn.seq < nation.capset`; else if `term < preReqTurn` → `term+1`; else ready. On success → `setResultTurn(LastTurn(name, arg, 0))`. Any 감축/증축/천도 bumps `nation.capset`, invalidating in-flight stacks of all three.
- [ ] Test: differing command resets term=1; `seq < capset` resets; term increment until preReqTurn; ready state; capset bump invalidation.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.TermStackTest' | tail -30`.
- [ ] Commit: `feat(logic/actions): capset-seq term-stack helper (LastTurn 4-field)`.

**F-SUBSTRATE gate:** none external; exercised by personnel/founding/nation goldens.

## AREA F-PIPELINE — GeneralActionModule iface widen + getStatValue calcCache (gates all stat-stack work)

> **Port target = PHP `General.php:787-799` (getActionList) + `:815-875` (onCalc* folds) + `:359-403` (getStatValue cache).** Research §Cross-cutting #3, Units 8/9/10/11/12, OQ3/OQ5(cache). **EXTEND `ActionPipeline.kt` — do not reshape the P1 call sites.**

### Task FP1 — Widen `GeneralActionModule` iface + pipeline folds + aux
**Files:** edit `logic/stats/ActionPipeline.kt`; test `logic/src/test/.../stats/ActionPipelineWidenTest.kt`.
Steps:
- [ ] Add to `GeneralActionModule` (all identity defaults): `onCalcStrategic(general, varType['delay'|'globalDelay'], value): Double = value`; `onCalcNationalIncome(general, type['gold'|'rice'|'pop'], value): Double = value`; `onCalcOpposeStat(general, statName, value): Double = value` (stub for war-adjacency); add an optional `aux: Map<String,Any?> = emptyMap()` param to `onCalcStat`/`onCalcDomestic` (PHP/TS pass `['armType'=>...]`; default empty keeps P1 call sites compiling — overload or default-arg).
- [ ] Add to `GeneralActionPipeline` the matching folds (`onCalcStrategic`/`onCalcNationalIncome`/`onCalcOpposeStat`). **onCalcNationalIncome ASYMMETRY (research Unit 12):** income is folded over the NATION-TYPE source ONLY, not the full general stack — expose a `nationIncomeFold(nationTypeModule, type, value)` separate path, NOT the general pipeline.
- [ ] Add a `MODULE_ORDER` constant documenting the getActionList fold order (nationType→officer→specialDomestic→specialWar→personality→crew→inherit→scenario→item[horse,weapon,book,item]).
- [ ] Test: empty pipeline = identity for all 5 hooks; a one-module stub folds each; aux is threaded; the income fold uses only the nation-type source.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.stats.ActionPipelineWidenTest' | tail -30`.
- [ ] Commit: `feat(logic/stats): widen GeneralActionModule (strategic/income/oppose + aux) + income-only-nation-type fold`.

### Task FP2 — `getStatValue` calcCache (floored/unfloored asymmetry + clear-on-mutate)
**Files:** edit `logic/stats/GetStatValue.kt`; test `logic/src/test/.../stats/GetStatValueCacheTest.kt`.
Steps:
- [ ] Add the calcCache (research Unit 8 — the #1 cache trap): cacheKey = `"{statName}_{withInjury}_{withIActionObj}_{withStatAdjust}"` (useFloor NOT in key). The cache WRITE sits AFTER the useFloor early-return → only the UN-FLOORED value is cached; floored reads apply `Util::toInt` (truncate-toward-zero) on the cached unfloored value. Full clear on any var mutation. **OQ5/cache decision:** since `General` is immutable, attach the cache as a per-resolve mutable companion (a `StatCalc(general)` wrapper holding the cache map) rather than mutating the data class — clear = new wrapper / `cache.clear()` on the wrapper after any draft `.copy()`.
- [ ] Test: two `getStatValue(useFloor=false)` calls reuse the cache (assert the pipeline fold runs once via a draw-counting stub module); a `useFloor=true` call reads the cached unfloored value and truncates; a var mutation clears the cache (next call recomputes); cacheKey excludes useFloor (floored + unfloored share one entry).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.stats.GetStatValueCacheTest' | tail -30`.
- [ ] Commit: `feat(logic/stats): getStatValue calcCache (unfloored-only write + clear-on-mutate)`.

**F-PIPELINE gate:** GATE-TRAIT (non-identity fold golden) is the byte oracle; unit tests pin cache asymmetry.

## AREA F-RNG — Unique-item lottery seam + NPCType + RNG-kernel verification (gates any per-command golden)

> **Port target = PHP `func_gamerule.php:954-986` (genGenericUniqueRNG[FromGeneral]) + `func.php:1611-1657` (tryUniqueItemLottery NPC>=2 short-circuit).** Research §Cross-cutting #5, OQ6, Units 1/2/3/6/14.

### Task FR1 — Unique-item lottery seam
**Files:** create `logic/domestic/UniqueItemLottery.kt`; test `logic/src/test/.../domestic/UniqueItemLotteryTest.kt`.
Steps:
- [ ] Port `genGenericUniqueRNGFromGeneral`: a SEPARATE `LiteHashDrbg` seeded `serializeSeed(hiddenSeed, "unique", year, month, generalId, actionName)`. `tryUniqueItemLottery`: human-only — `getNpcType(general) >= 2` short-circuits with NO draw (so it never perturbs the action stream); humans draw but for P2 domestic the default general wins nothing (assert no acquisition for the captured goldens). Fires AFTER all DB writes + checkStatChange.
- [ ] Test: NPCType>=2 → zero draws on the lottery rng (assert draw count unchanged); human → seeded draw, deterministic; the lottery rng is INDEPENDENT of the action rng (same action seed + different lottery outcome ⇒ action stream identical).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.domestic.UniqueItemLotteryTest' | tail -30`.
- [ ] Commit: `feat(logic/domestic): unique-item lottery seam (separate rng, NPCType>=2 short-circuit)`.

### Task FR2 — Verify RNG kernel primitives + serializeSeed nation fork
**Files:** test `logic/src/test/.../rng/RngKernelParityTest.kt` (no new prod code if the P0-B kernel already exposes them).
Steps:
- [ ] Verify `:common` `RandUtil` exposes `choiceUsingWeight(LinkedHashMap)`, `choiceUsingWeightPair`, `nextBool(p)`, `nextRangeInt(lo,hi)`, `nextFloat1()` with PHP-faithful draw order; verify `serializeSeed` handles ALL THREE forks: the `generalCommand`/`nationCommand` 6-tuple (component 2 literal + `rawClassName` as component 6) AND the `preprocess` 5-tuple (NO classname component). If any primitive is missing, ADD it to `:common` with a draw-order test against a hand-computed cumulative cutoff.
- [ ] Test: `choiceUsingWeight(["fail","success","normal"])` fixed draw == hand-computed bucket; `nextBool(0.4)` + `nextRangeInt(95,105)` draw order matches `RandomizeCityTradeRate` per-city expectation. **serializeSeed byte-match assertions** (PHP `simpleSerialize`: each string → `str(mb_strlen,value)`, each int → `int(value)`, joined by `|`): assert `serializeSeed(hidden,"nationCommand",y,m,g,"cr_맹훈련")` emits the `cr_맹훈련` component as `str(6,cr_맹훈련)` (mb_strlen of `cr_맹훈련` = 6: `c`,`r`,`_`,`맹`,`훈`,`련`); also byte-match `che_랜덤임관` (component `str(6,che_랜덤임관)`) and `cr_건국` (component `str(5,cr_건국)`) — the distinct-class cases. Assert the **6-tuple action fork** (with `rawClassName`) and the **5-tuple preprocess fork** (`serializeSeed(hidden,'preprocess',y,m,g)` — NO classname) BOTH byte-match the PHP `simpleSerialize` token shape alongside each other.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test --tests '*RngKernelParityTest' | tail -30`.
- [ ] Commit: `test(logic/rng): RNG-kernel + serializeSeed nationCommand-fork parity`.

**F-RNG gate:** every per-command golden depends on this; verified by the gates.

## AREA F-FLUSH — DirtyState/ChangeRecorder/JdbcFlushExecutor satellite write-set (depends on F-DOMAIN)

> **Port target = PHP `General.php:704-751` (applyDB rank_data flush) + `func_command.php:36-200` (ring rotate) + `KVStorage.php` (delete-on-null KV) + `LastTurn.php` (toRaw).** Research Unit 13, OQ10/OQ11/OQ12. **10-step flush ORDER is FROZEN — new ops slot into the reserved slots (step-3/5/6/8/10), never reorder.**

### Task FF1 — Extend `DirtyState` + `ChangeRecorder`
**Files:** edit `app/game-engine/turn/{DirtyState,TurnWorldModel,ChangeRecorder}.kt`; test `app/game-engine/src/test/.../turn/ChangeRecorderNationTest.kt`.
Steps:
- [ ] `DirtyState` += `rankDirty: Map<Int, Map<RankColumn, RankDelta>>` (RankDelta = Increment(Int) | Set(Int) — the 3-Map collapse: at most one per type), `nationTurnDirty: List<NationTurn>`, `kvDirty: Map<String, Any?>` (nation_env key→json|null-deletes). (`nations`/`troops`/`diplomacy`/`created*`/`deleted*`/`deletedNationSnapshots` ALREADY exist.)
- [ ] `TurnWorldModel` += `RankColumn` enum (**exactly 37 cases — VERIFIED against the PHP `sammo\Enums\RankColumn` enum (`hwe/sammo/Enums/RankColumn.php`, `RankColumn::cases()` = 37). The 37 string values (the enum `case` backing values, the rank_data column names): `firenum, warnum, killnum, deathnum, killcrew, deathcrew, ttw, ttd, ttl, ttg, ttp, tlw, tld, tll, tlg, tlp, tsw, tsd, tsl, tsg, tsp, tiw, tid, til, tig, tip, betwin, betgold, betwingold, killcrew_person, deathcrew_person, occupied, inherit_earned, inherit_spent, inherit_earned_dyn, inherit_earned_act, inherit_spent_dyn`** — note the last 6 enum case NAMES differ from their backing VALUES, e.g. `inherit_point_earned = 'inherit_earned'`; the rank_data column uses the backing VALUE). **Set `RANK_ROWS_PER_GENERAL = 37 NOW** (`RankColumn.entries.size`); reconcile the stale engine constant `40` in `DatabaseHooks.kt:38`.** The nation_env KV map; the new general/city/nation columns on `TurnGeneral`/`City`/`Nation`.
- [ ] `ChangeRecorder` += `diffNation(pre,post)` (gold/rice/capset/capital/name/color + aux meta-diff), dest-general diff (dual-general commands emit >1 dirty general), widened `diffCity` (tech/secu/def/wall/pop + each _max), and the rankVar 3-Map collapse. Keep it the SINGLE dirty source (no direct `world.updateX`).
- [ ] Test: a nation gold+capset change → diffNation lists exactly those + capset; a dual-general appoint → two dirty generals; rank Increment+Set on the same type collapses to one write; unchanged columns absent from the patch; jsonb key order preserved.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.turn.ChangeRecorderNationTest' | tail -40`.
- [ ] Commit: `feat(game-engine/turn): DirtyState rank/nationTurn/KV + ChangeRecorder diffNation/dest/widened-city/rank-collapse`.

### Task FF2 — `JdbcFlushExecutor` step-3/5/6/8 + nation_turn ring
**Files:** edit `infra/persistence/{JdbcFlushExecutor,ReservedTurnRepository}.kt`; edit `app/game-engine/flush/{DatabaseHooks,FlushOp}.kt`; edit `app/game-engine/src/test/.../flush/DatabaseHooksOrderTest.kt` (2*40→2*37); tests `infra/src/test/.../persistence/{JdbcFlushExecutorSatelliteIT,NationTurnRingIT}.kt`.
Steps:
- [ ] **Widen the step-7 general + city `UPDATE SET` (P1 hardcoded only the P1 columns — verified `JdbcFlushExecutor.kt:132-176`):** the **general** UPDATE SET must add `crew, train, atmos, crew_type_id, troop_id, weapon_code, book_code, horse_code, item_code, last_turn, personal_code` (on top of the P1 set: nation_id/city_id/leadership/strength/intel/injury/experience/dedication/officer_level/gold/rice/meta). `last_turn` is the general-command `setResultTurn` target → `LastTurn.toRaw()` jsonb on the general row (delete-on-default; nation-command `setResultTurn` instead flushes to `nation_env` `turn_last_{officer_level}` KV, NOT this column). The **city** UPDATE SET must add `secu, secu_max, def, def_max, wall, wall_max, pop, pop_max, trade` (on top of the P1 set: nation_id/level/comm/comm_max/agri/agri_max/supply_state/front_state/trust/meta). Add `general.last_turn` to the FD0 General field list + FD1 `GeneralRowMapper` (LastTurn jsonb, delete-on-default, riding the general row UPDATE).
- [ ] Implement the reserved flush steps (keep the FROZEN order): step-3 createMany general/nation/troop/diplomacy/nation_turn (guarded >0); step-5 deleteMany general then rank_data; step-6 nation cascade (diplomacy, nation_turn, nation revert); step-7 general + city UPDATE (widened above) + nation UPDATE (excl created); step-8 rank_data **UPDATE (the 37 rows per general are pre-seeded at general creation — NOT an UPSERT; the flush UPDATEs the affected rows)** (rankVarIncrease then rankVarSet) + the nation_id-sync op (when nation changed, ALL the general's rank_data rows get nation_id := new) — preserve the exact `getRankVar` missing-type exception string `'인자가 없음 : '+type`; step-10 KV writes (`next_execute_*` bare int / `turn_last_{officer_level}` LastTurn.toRaw object / per-general aux delete-on-null rides the general UPDATE).
- [ ] `ReservedTurnRepository` += nation_turn ring (MAX_CHIEF_TURNS=12, key nation_id×officer_level×turn_idx, rotate vacated slots to `휴식`/`{}` on pull; pull AFTER run, BEFORE flush). general_turn ring (MAX=30) reused.
- [ ] **Set `DatabaseHooks.RANK_ROWS_PER_GENERAL = 37` (was the stale `40` at `DatabaseHooks.kt:38`) = `RankColumn.entries.size` (37, verified against the PHP enum); update `DatabaseHooksOrderTest`'s `2 * 40` expectation to `2 * 37` (and the `count is 40 per updated general` test → 37).** `DatabaseHooks` maps the widened `DirtyState` → `FlushPayload` (extend FlushPayload with rank/nation/nationTurn/diplomacy/KV lists); `FlushOpRecorder` gains the new op tags so order tests assert them.
- [ ] Tests (Testcontainers postgres): `JdbcFlushExecutorSatelliteIT` flushes a payload with a rank Increment + nation UPDATE + KV write + nation_id sync; asserts row+jsonb byte-comparable + op ORDER == contract; **plus a widened-step-7 round-trip: a general `crew`/`train`/`atmos` + `last_turn` change AND a city `secu`/`def`/`wall`/`pop` change flush and read back byte-comparable** (proves the widened UPDATE SET). `NationTurnRingIT` push/pull/rotate-to-휴식.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests 'opensamguk.infra.persistence.JdbcFlushExecutorSatelliteIT' --tests 'opensamguk.infra.persistence.NationTurnRingIT' | tail -40`.
- [ ] Commit: `feat(infra+engine/flush): rank_data UPSERT + nation cascade + nation_turn ring + KV in frozen 10-step order`.

**F-FLUSH gate:** `RANK_ROWS_PER_GENERAL=37` and the `brief` column are PINNED here (not deferred); GATE-SATELLITE CONFIRMS the 37-row rank_data + brief after-image byte-comparable.

## AREA F-GOLDEN-0 — P2 command manifest + capture harness generalization (depends on P1 harness GREEN)

> **Port target = the PROVEN `tools/php-golden/capture_che.php` + `probe_picks.php`.** Research Unit 14, OQ1. P1 harness is GREEN on disk (the gate is satisfied) — generalize it.

### Task FG1 — The ~36-command manifest + classification
**Files:** create `tools/php-golden/manifest.json`.
Steps:
- [ ] Enumerate the exact P2 command subset into `manifest.json`, classifying each by `{ctor: general|nation, rawClassName (the PHP short class name incl che_/cr_ prefix — the 6th seed component; pin the divergent ones: che_랜덤임관, cr_맹훈련, cr_건국), lotteryActionName (static::$actionName — the SEPARATE lottery seed token, distinct from rawClassName), rngDraws, logLines, crossGeneral|broadcast, levelCross}`. The 36: develop {상업투자(done),농지개간(done),성벽보수,수비강화,치안강화,기술연구,정착장려,주민선정,물자조달,군량매매}; military {징병,모병,훈련,맹훈련,사기진작,소집해제,이동,집합}; personnel {임관,장수대상임관,랜덤임관,하야,방랑,은퇴,등용 (SEND only; 등용수락 DEFERRED to P6)}; founding {거병,건국,cr_건국,무작위건국}; nation {감축,증축,발령,포상,국호변경,국기변경,천도(,무작위수도이전 flag)}; trade {증여,헌납,장비매매}. Mark `물자원조` OUT (diplomatic). Mark the ~57-of-93 NOT-in-P2 as ignore-list backlog.
- [ ] Commit: `chore(tools): P2 command manifest (36 commands classified for golden capture)`.

### Task FG2 — Generalize `capture_che.php` → `capture_command.php` + `probe_command.php`
**Files:** create `tools/php-golden/{capture_command.php,probe_command.php}`.
Steps:
- [ ] Generalize `capture_che.php`: parameterize `(command, generalId, month, picks)`; add the Nation branch (4-arg `buildNationCommandClass(General,env,LastTurn,arg)` + `nationCommand` seed); replace the hard "exactly 1 action line" assert with a per-command EXPECTED-line-count (from the manifest); broaden `restoreBaseline` to restore ALL touched rows (dest-general, created nation, cascade cities). Capture dest-general logs + broadcast `globalAction` lines + per-target PLAIN logs. Emit one fixture per command into `logic/src/test/resources/golden/p2/<command>-fixtures.json`.
- [ ] Generalize `probe_picks.php` → `probe_command.php`: per command, find a module-free general satisfying `hasFullConditionMet` + distinct reachable outcomes; synthetic-seed fallback where scenario_0 has no module-free general.
- [ ] Keep the hidden-seed capture (one per install, committed as fixture input) verbatim from P1.
- [ ] Commit: `chore(tools): capture_command.php + probe_command.php (Nation fork, multi-line, broadened restore)`.

**F-GOLDEN-0 gate:** the harness produces a valid fixture for ≥1 new command end-to-end (proof it works) before the resolver families consume it.

## AREA F-MAP — Minimal CalcCityDistance map module (gates 이동 + 천도 distance)

> **Port target = PHP `calcCityDistance` (BFS over the city adjacency) + `CityConst.kt`'s golden-locked bidirectional `path` map (verified present: `CityConst.kt` builds a name→id bidirectional `path` adjacency).** Research OQ9. **Minimal scope: pure BFS only — DEFER full pathfinding (HasRoute*/NearNation, the C-DB constraint family) to the map/diplomacy phase.**

### Task FM1 — `CalcCityDistance` (pure BFS over CityConst.path)
**Files:** create `logic/world/CalcCityDistance.kt` (or `:common` if no `:logic` dep needed); test `logic/src/test/.../world/CalcCityDistanceTest.kt`.
Steps:
- [ ] Port `calcCityDistance(from, to, blockedNationIds?): Int?` as a pure BFS over `CityConst.kt`'s existing bidirectional `path` adjacency (no new data — the adjacency is golden-locked). Return hop-count distance; `null` (→ caller's `?? 50` fallback) when unreachable / blocked. Expose a `nearCity(from, radius)` helper (the preloaded-distance set the C-PURE/C-DEST `nearCity` constraint key consumes). **NO full pathfinding** (HasRoute*/NearNation, C-DB family) — that is DEFERRED to the map/diplomacy phase.
- [ ] Test: hop distance between known adjacent + 2-hop cities; blocked-nation exclusion; unreachable → null; `nearCity(radius=1)` set matches the direct `path` neighbors.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.world.CalcCityDistanceTest' | tail -30`.
- [ ] Commit: `feat(logic/world): CalcCityDistance pure BFS over CityConst path adjacency (이동/천도 foundation)`.

**F-MAP gate:** `이동` (MIL5) NearCity adjacency + `천도` (NI5) calcCityDistance land on this; the full HasRoute*/NearNation pathfinding stays DEFERRED.

---

# TIER 1 — Constraint + stat-stack libraries

## AREA C-PURE — ~30 pure constraints + 6 comparators + percent (← F-DOMAIN)

> **Port target = PHP `Constraint/*.php` (reason strings + REQ_VALUES + test order) — NOT TS (research Unit 7 lists the confirmed TS divergences).** EXTEND `Presets.kt` (keep the 7 P1 presets verbatim).

### Task CP1 — Pure general/city/nation-state constraints (~30)
**Files:** edit `logic/constraints/Presets.kt`; test `logic/src/test/.../constraints/PresetsPureTest.kt`.
Steps:
- [ ] Add the pure-state presets (each a `Constraint{name,requires,test}`, PHP-exact reason): `beNeutral('재야가 아닙니다.')`, `beChief('수뇌가 아닙니다.')`, `notChief`, `beLord`, `notLord`, `mustBeNPC('NPC여야 합니다.')`, `mustBeTroopLeader`, `reqTroopMembers`, `reqGeneralCrew`, `reqGeneral{Atmos,Train,Crew}Margin`, `allowJoinAction` (GameConst.joinActionLimit), `noPenalty`, `wanderingNation('방랑군이어야 합니다'` — **NO trailing period**), `notCapital`, `occupiedCity(allowNeutral)` (ADD the `allowNeutral` branch the P1 preset omitted — `if(arg && nation==0) pass`), `notOccupiedCity`, `neutralCity`, `constructableCity`, `reqCityTrust`, `reqCityTrader` (VERIFIED `ReqCityTrader.php:25-30`: PASSES when `city.trade !== null || arg >= 2`, else DENIES with reason `'도시에 상인이 없습니다.'`; the `hasMinConditionMet` also requires the `trade` key present in city else throws `"require trade in city"`), `remainCityTrust`, `battleGroundCity`, `beOpeningPart`, `notOpeningPart`, `allowWar`/`allowStrategicCommand` (pure from nation.war), `reqNationGold`, `reqNationRice`, **plus the pure military presets the Wave-3 protocol moves here from MIL1: `availableRecruitCrewType`, `reqCityCapacity`, `suppliedCity` (`reqCityTrust`/`reqGeneralCrew`/`reqGeneral{Train,Atmos,Crew}Margin`/`mustBeTroopLeader`/`reqTroopMembers` are already in this list)**. Reuse `JosaUtil.pick` (은 for RemainCity*; 이 for ReqCityCapacity/Req*Value).
- [ ] Test (`PresetsPureTest`): one deny + one allow per preset, asserting EXACT reason strings byte-for-byte. Explicit assertions for the TS-divergent ones (MustBeNPC, BeNeutral, WanderingNation-no-period).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.constraints.PresetsPureTest' | tail -40`.
- [ ] Commit: `feat(logic/constraints): ~30 pure state presets (PHP reason strings + occupiedCity allowNeutral)`.

### Task CP2 — Comparator family (Req*Value) + percent
**Files:** create `logic/constraints/Comparators.kt`; edit `Presets.kt` (reqGeneralValue/reqCityValue/reqNationValue/reqDestCityValue/reqDestNationValue/reqNationAuxValue/reqEnvValue + reqCityCapacity percent); test `logic/src/test/.../constraints/ComparatorsTest.kt`.
Steps:
- [ ] Port `compareValues` (8 comparators `> >= == <= < != === !==`) + the derived reason matrix (research Unit 7 port_notes: `<`/`<=`→`'너무 많습니다.'`; `==`/`!=`/`===`/`!==`→`'올바르지 않은 {keyNick} 입니다.'`; `>=`→ src==1 `'없습니다'`(NO period) else `'부족합니다.'`; `>`→ src==0 `'없습니다'` else `'부족합니다.'`; final `"{keyNick}{JosaUtil.pick(keyNick,'이')} {derived}"` — note the SPACE + `'없습니다'` has NO period). Port `parsePercent` (`/^(\d+(?:\.\d+)?)%$/ → /100`, compares `city[key]` vs `city[key_max]*reqFloat`). `reqEnvValue`/`reqNationAuxValue` reason = ALWAYS the caller errMsg (no derived text).
- [ ] Test (`ComparatorsTest`): table-driven over all 8 comparators × {derived/errMsg} × {src==0/1/other}; percent path with *_max; josa-이 on the keyNick.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.constraints.ComparatorsTest' | tail -40`.
- [ ] Commit: `feat(logic/constraints): Req*Value comparator family + parsePercent (PHP derived-reason matrix)`.

**C-PURE gate:** per-constraint reason-string byte test; feeds every command's constraint pack.

## AREA C-DEST — Dest-* constraints + RequirementKey extension (← F-SUBSTRATE, C-PURE)

### Task CD1 — RequirementKey + ConstraintContext extension
**Files:** edit `logic/constraints/ConstraintTypes.kt`; edit `app/game-engine/turn/WorldStateViewAdapter.kt` + `app/game-api/precheck/PrecheckStateViewFactory.kt` (resolve the new keys); test `logic/src/test/.../constraints/RequirementKeyTest.kt`.
Steps:
- [ ] `RequirementKey` += `DestGeneral(id)`, `DestCity(id)`, `DestNation(id)`, `NationList`, `GeneralList`, `Diplomacy(me,you)`. `ConstraintContext` += `destGeneralId/destCityId/destNationId`. `StateView` resolves all new keys (the two adapters preload them — DB-backed constraints NEVER call DB inside `test()`).
- [ ] Test: `MemoryStateView.has/get` for each new key; `NationList`/`GeneralList` return the full collections (CheckNationNameDuplicate / ReqNationValue('gennum')).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.constraints.RequirementKeyTest' | tail -30`.
- [ ] Commit: `feat(logic/constraints): RequirementKey Dest*/NationList/GeneralList/Diplomacy + ctx dest ids`.

### Task CD2 — Dest-* pure constraints
**Files:** edit `logic/constraints/Presets.kt`; test `logic/src/test/.../constraints/PresetsDestTest.kt`.
Steps:
- [ ] Add: `friendlyDestGeneral('아국 장수가 아닙니다.')`, `existsDestGeneral('없는 장수입니다.')`, `differentDestNation`, `differentNationDestGeneral`, `notNeutralDestCity`, `notSameDestCity`, `notOccupiedDestCity`, `occupiedDestCity`, `suppliedDestCity`, `nearCity` (needs adjacency — flag map dep Q9, stub a preloaded-distance StateView key), `checkNationNameDuplicate('존재하는 국가명입니다.')`, `allowJoinDestNation` (4-branch order), `allowDiplomacyStatus`, `reqDestCityValue`, `reqDestNationValue`. PHP reason strings (NOT TS).
- [ ] Test: deny+allow per dest-* preset; the AllowJoinDestNation 4-branch order; CheckNationNameDuplicate against the NationList.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.constraints.PresetsDestTest' | tail -40`.
- [ ] Commit: `feat(logic/constraints): dest-* + diplomacy/name-dup presets (PHP reason strings)`.

**C-DEST gate:** per-constraint reason byte test; the DB/pathfinding-backed subset (HasRoute*/NearNation, research Unit 7 C-DB) is FLAGGED as likely spilling past the P2 domestic boundary — port only the StateView-preloadable forms; defer pathfinding to the map module (Q9).

## AREA S-MODULES — The 9-source module registry (← F-PIPELINE)

### Task SM1 — `GeneralActionModuleFactory` + officer + crew/inherit/scenario stubs
**Files:** create `logic/stats/GeneralActionModuleFactory.kt`, `logic/traits/OfficerLevelModule.kt`; test `logic/src/test/.../stats/ModuleFactoryOrderTest.kt`.
Steps:
- [ ] Create the factory that, given a `General` (+ nation type + personality + specialty codes + 4 item slots), builds the ordered module list in `MODULE_ORDER` (nationType→officer→specialDomestic→specialWar(stub)→personality→crew(stub)→inherit(stub)→scenario(stub)→items). null/empty slot → skipped. Port `OfficerLevelModule` (TriggerOfficerLevel `onCalcStat(leadership)` lbonus — research Unit 12). crew/inherit/scenario are identity stubs in P2 domestic (war/inheritance are P4/P6). **Wave-3 shared-file protocol: S-MODULES is the SOLE OWNER/editor of `GeneralActionModuleFactory.kt`. The factory resolves each source by LOOKUP into per-family registries (`NationTypeRegistry`/`PersonalityRegistry`/`SpecialDomesticRegistry`/`ItemRegistry`) — the trait/item families (TD1/TP1/TN1/IT1/IT2) populate THEIR OWN registry file (append), never the factory body. Define the registry interfaces here so the families register into them without touching this file.**
- [ ] Test: factory output order == MODULE_ORDER for a fully-equipped general; null slots skipped; a default general → empty effective list (identity); officer lbonus folds.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.stats.ModuleFactoryOrderTest' | tail -30`.
- [ ] Commit: `feat(logic/stats): 9-source module factory (getActionList order) + officer module + war/crew/inherit/scenario stubs`.

(The concrete domestic-specialty / personality / nation-type / item modules are TRAITS-* / ITEMS in Tier 2; each registers into its OWN per-family registry (defined here) — the factory body stays S-MODULES-owned and unedited by the families.)

**S-MODULES gate:** GATE-TRAIT; unit test pins fold order.

## AREA S-LEVEL — checkStatChange + addExperience/addDedication PLAIN logs (← F-PIPELINE)

### Task SL1 — Level-change helpers + PLAIN logs
**Files:** create `logic/domestic/StatChange.kt`; edit `logic/domestic/DomesticHelpers.kt` (level-conversion helpers); test `logic/src/test/.../domestic/StatChangeTest.kt`.
Steps:
- [ ] Port `getExpLevel`/`getDedLevel`/`getDedLevelText`/`getBillByLevel` (`func_converter.php:602-670`) + `maxDedLevel`/`maxLevel`/`upgradeLimit=30` consts. Port `checkStatChange` (iterate 통솔/무력/지력; `exp>=30` → stat+1 (cap 255) + `'<S>{nick}</>이 <C>1</> 올랐습니다!'` + `exp-=30` unconditionally; `exp<0` → stat-1 + `'<R>{nick}</>이 <C>1</> 떨어졌습니다!'` + `exp+=30`; PLAIN).
- [ ] Port `addExperience`/`addDedication` EXACTLY per `General.php:448-495` (verified): **each MUST first FOLD the input through `onCalcStat($this, 'experience'|'dedication', value)` (the full `getActionList` stack, `affectTrigger` default TRUE — e.g. the personality `experience*1.1` multiplier) BEFORE `increaseVar`**; THEN recompute `explevel = getExpLevel(experience)` / `dedlevel = getDedLevel(dedication)`, compare via `<=>` against the stored level, and on a NON-zero change emit the PLAIN level-change log: `addExperience` → up `"<C>Lv {n}</>{josa로} <C>레벨업</>!"` / down `"<C>Lv {n}</>{josa로} <R>레벨다운</>!"`; `addDedication` → up `"<Y>{dedText}</>{josa로} <C>승급</>하여 봉록이 <C>{bill}</>{josa로} <C>상승</>했습니다!"` / down `"<Y>{dedText}</>{josa로} <R>강등</>되어 봉록이 <C>{bill}</>{josa로} <R>하락</>했습니다!"` (josa-로 on BOTH `{dedText}` and `{bill}`; `{bill}` = `number_format(getBillByLevel(n))`). All PLAIN. No log when level unchanged. Locate the exact PHP per-turn-finalize CALL SITE (research Unit 8 gap) — checkStatChange runs at per-turn finalize, NOT per-command.
- [ ] Test: addExperience folds through `onCalcStat('experience')` (a personality *1.1 module changes the increment) BEFORE increaseVar; addDedication folds through `onCalcStat('dedication')`; exp 30→stat+1, exp-=30 unconditional; exp<0 down; level boundary cross pushes the exact PLAIN log byte-for-byte; josa-로 on `'Lv 3'` / 품관 text + bill text; no log on unchanged level; cap at maxLevel.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.domestic.StatChangeTest' | tail -40`.
- [ ] Commit: `feat(logic/domestic): checkStatChange + addExperience/addDedication PLAIN level logs (upgradeLimit=30)`.

**S-LEVEL gate:** per-log golden in GATE-RUNTIME (some P2 fixtures INTENTIONALLY cross a level — the inverse of P1's no-cross assertion).

---

# TIER 2 — Per-command resolver families (parallel fan-out)

> Each family below is an INDEPENDENT parallel worktree (disjoint `actions/<family>/` subtree). Per the **Wave-3 shared-file protocol** (see "Parallel worktree fan-out" above + Self-Review): the 4 real shared files (`Presets.kt`, `GeneralActionModuleFactory.kt`, `FoundingCascade.kt`, `DomesticHelpers.kt`) are each created/owned by a foundation task that the families only CONSUME; `CommandRegistry.kt` is the lone per-family append point (append-only per family). Each task is TDD red→green with a per-command resolve test; the per-command BYTE golden is finalized in GATE-RUNTIME (the family's resolve test asserts structural correctness + determinism until the golden lands).

## AREA CMD-DEVELOP — 내정 개발 (← F-DOMAIN, S-MODULES, C-PURE, F-RNG, F-GOLDEN-0)

> **Port target = PHP `che_상업투자.php` + the develop family. Research Unit 1** (source refs, RNG draw order, rounding, front-debuff, log strings all pinned there).

### Task DV1 — Generalize `CommerceInvestment` (성벽보수/수비강화/치안강화)
**Files:** edit `logic/actions/CommerceInvestment.kt`; edit `CommandRegistry.kt`; tests `logic/src/test/.../actions/develop/CommerceInvestmentGeneralizeTest.kt`.
Steps:
- [ ] Widen the `cityKey/statKey` switch from `{comm,agri, hardcoded 'intelligence'}` to `{comm,agri,secu,def,wall}` (**NO `tech` arm — tech is a NATION stat written by che_기술연구/DV2 into `nation.tech`; there is no `city.tech`**) with statKey-driven `getStatValue` (intel|strength|leadership). `calcBaseScore` (currently hardcodes 'intelligence' + comm/agri) reads `statKey`/`cityKey`. Register `che_성벽보수`(wall/strength/성벽), `che_수비강화`(def/strength/수비), `che_치안강화`(secu/strength/치안) as `CommerceInvestment` instances (the 농지개간-extends-상업투자 pattern). RNG draw order + front-debuff + rounding UNCHANGED from P1.
- [ ] Test: each new command mutates the right city column via the right stat; RNG draw order identical; front-debuff applies; log uses the spaced action name + josa.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.develop.CommerceInvestmentGeneralizeTest' | tail -40`.
- [ ] Commit: `feat(logic/actions/develop): generalize CommerceInvestment + register 성벽보수/수비강화/치안강화`.

### Task DV2 — `che_기술연구`
**Files:** create `logic/actions/develop/CheGisulYeongu.kt`; edit `DomesticHelpers.kt` (TechLimit); edit `CommandRegistry.kt`; test `develop/CheGisulYeonguTest.kt`.
Steps:
- [ ] Port `che_기술연구.php:25-143`: no RemainCityCapacity, no front-debuff; `score/=4` if TechLimit; `genCount=valueFit(nation.gennum,10)`; `nation.tech += score/genCount` (NATION write, confirm downstream clamp for FLOAT flush byte-match, research OQ). Port `TechLimit` (`func_converter.php:684-697`).
- [ ] Test: TechLimit /4 branch; nation.tech increment; no RemainCityCapacity constraint; resolve determinism.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.develop.CheGisulYeonguTest' | tail -30`.
- [ ] Commit: `feat(logic/actions/develop): che_기술연구 (TechLimit /4, nation.tech write, no front-debuff)`.

### Task DV3 — `che_정착장려` + `che_주민선정` (second parameterized base)
**Files:** create `develop/{CheJeongchakJangnyeo,CheJuminSeonjeong}.kt`; edit `CommandRegistry.kt`; tests `develop/{CheJeongchakJangnyeoTest,CheJuminSeonjeongTest}.kt`.
Steps:
- [ ] Port `che_정착장려.php` (pop, leadership, develcost*2 RICE, score*=10, pop clamp, `'주민이 …명 증가'` log) + `che_주민선정.php` (trust, RICE*2, score/=10, NO round (keeps fractional), `number_format(,1)`, RemainCityTrust). These are NOT pure CommerceInvestment subclasses (research Unit 1 gap) → a second parameterized base (leadership-stat, no trust factor) OR per-command resolvers. 주민선정 passes UN-rounded score to `updateMaxDomesticCritical`.
- [ ] Test: 정착장려 score×10 + pop clamp; 주민선정 score÷10 + one-decimal format + fractional max_domestic_critical; RICE×2 cost; RemainCityTrust.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.develop.CheJeongchakJangnyeoTest' --tests 'opensamguk.logic.actions.develop.CheJuminSeonjeongTest' | tail -40`.
- [ ] Commit: `feat(logic/actions/develop): che_정착장려 + che_주민선정 (leadership, RICE×2, score×10/÷10, RemainCityTrust)`.

### Task DV4 — `che_물자조달`
**Files:** create `develop/CheMuljaJodal.kt`; edit `CommandRegistry.kt`; test `develop/CheMuljaJodalTest.kt`.
Steps:
- [ ] Port `che_물자조달.php:65-148` (VERIFIED): getCost [0,0]; **EXTRA leading draw** `rng->choice([['금','gold'],['쌀','rice']])` is draw #1 (line 75) BEFORE `nextRange(0.8,1.2)` (line 82); fixed success 0.1/fail 0.3 (then `onCalcDomestic('조달',...)`); **`$score = Util::round($score)` (line 99) FIRST** — then `exp = score*0.7/3` and `ded = score*1.0/3` are computed from this **PRE-front-debuff ROUNDED** score (lines 101-102). The front-debuff (`$score *= $debuffFront`, lines 106-121, only when `city.front in [1,3]`; capital ramp `relYear<25`) multiplies the score **AFTER** exp/ded. Both the log `scoreText = number_format($score,0)` (line 123) AND the nation credit (`nation.{gold|rice} += $score`, lines 146-148) use the **POST-debuff** score. weighted `incStat = choiceUsingWeight([leadership_exp=>getLeadership(false,false,false,false), strength_exp=>..., intel_exp=>...])` (RAW stats, lines 136-140) then `increaseVar(incStat,1)`; log hardcodes `'을'` (NOT josa-computed, lines 127-133).
- [ ] Test: 5-draw order (choice→nextRange→choiceUsingWeight(pick)→CriticalScoreEx→choiceUsingWeight(incStat)); fixed ratios; **exp/ded from the PRE-debuff rounded score; log scoreText + nation credit from the POST-debuff score** (a front-city fixture where debuff≠1 separates the two); nation resource increment; hardcoded 을.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.develop.CheMuljaJodalTest' | tail -30`.
- [ ] Commit: `feat(logic/actions/develop): che_물자조달 (resource-choice leading draw, fixed ratios, /3, weighted incStat, nation mutation)`.

### Task DV5 — `che_군량매매` (reqArg, ReqCityTrader, trade/exchange math)
**Files:** create `develop/CheGunryangMaemae.kt`; create `logic/world/RandomizeCityTradeRate.kt`; edit `CommandRegistry.kt`; tests `develop/CheGunryangMaemaeTest.kt`, `world/RandomizeCityTradeRateTest.kt`.
Steps:
- [ ] Port `che_군량매매.php:22-199` with the EXACT PHP buyRice/sellRice tax algo (research Unit 6 port_notes — NOT TS, which diverges on the overflow tax): buyRice `sell=clamp(amount*rate,max=gold); tax=sell*0.01; if sell+tax>gold then sell=sell*(gold/(sell+tax)) and tax=gold-sell; buy=sell/rate; sell=sell+tax`. sellRice `sell=clamp(amount,max=rice); buy=sell*rate; tax=buy*0.01; buy=buy-tax`. rate=`city.trade/100` (or 1.0 if null && npc>=2). argTest `Util::round(amount,-2)` then `valueFit(amount,100,10000)`. weighted incStat; nation gold tax; fixed exp30/ded50. `increaseVar(key,0)` is a NO-OP. Constraints: ReqCityTrader (pin from PHP `ReqCityTrader.php:25-30`: pass iff `city.trade !== null || arg >= 2`, deny reason `'도시에 상인이 없습니다.'`) + OccupiedCity(allowNeutral=TRUE) + SuppliedCity.
- [ ] Port `RandomizeCityTradeRate.php:11-48` (monthly seeded world-tick, per-city `prob={4:0.2,5:0.4,6:0.6,7:0.8,8:1}`, `nextBool(prob)?nextRangeInt(95,105):null`, draw order load-bearing) — this populates `city.trade`, the 군량매매 golden precondition.
- [ ] Tests: buyRice overflow tax exact float order; sellRice; argTest double-round; ReqCityTrader; RandomizeCityTradeRate per-city draw order for a fixed year/month.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.develop.CheGunryangMaemaeTest' --tests 'opensamguk.logic.world.RandomizeCityTradeRateTest' | tail -40`.
- [ ] Commit: `feat(logic/actions/develop+world): che_군량매매 (PHP tax algo) + RandomizeCityTradeRate monthly tick`.

**CMD-DEVELOP gate:** per-command Kotlin golden across pick branches + front/non-front cities (GATE-RUNTIME); precheck==full; flush byte-comparable.

## AREA CMD-MILITARY — 군사/이동/모병 (← F-DOMAIN, C-PURE+11 mil constraints, F-RNG, F-GOLDEN-0)

> **Port target = PHP `che_징병.php` + the military family. Research Unit 2** (maxCrew, getCost order, train/atmos blend, NONE draw from turn RNG). **모병 = a 9-line subclass of 징병 → ONE `RecruitAlgorithm` instantiated twice (the 농지개간 pattern).**

### Task MIL1 — unit-set stat table (military constraints land in C-PURE/C-DEST)
**Files:** create `logic/actions/military/UnitSetTable.kt`; tests `military/{MilitaryConstraintsTest,UnitSetTableTest}.kt`. (Does NOT edit `Presets.kt` — see below.)
Steps:
- [ ] **Wave-3 shared-file protocol: `Presets.kt` is a Wave-2 prerequisite — the 11 military constraints are CREATED in C-PURE/C-DEST, NOT here.** The set (`reqGeneralCrew`, `reqGeneral{Train,Atmos,Crew}Margin`, `availableRecruitCrewType`, `reqCityCapacity`, `reqCityTrust`, `notSameDestCity`, `nearCity` (consumes F-MAP `CalcCityDistance`), `suppliedCity`, `mustBeTroopLeader`, `reqTroopMembers`) is added to C-PURE/C-DEST (a HARD dependency — if a military-only preset is missing there, add it to C-PURE/C-DEST, do not add it here). MIL1 only CONSUMES them. PHP reason strings + first-deny order are pinned in C-PURE/C-DEST; `MilitaryConstraintsTest` here asserts the CONSUMED presets behave correctly for the military commands (it does not define them).
- [ ] Create `UnitSetTable` (declarative unit-set stat data: cost/rice/armType + `getTechCost` curve, `GameUnitDetail.php:120-128`) — pure data, no RNG.
- [ ] Tests: military constraint deny+allow with exact reasons + first-deny order (against the C-PURE/C-DEST presets); unit-set cost/getTechCost values.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.military.MilitaryConstraintsTest' --tests 'opensamguk.logic.actions.military.UnitSetTableTest' | tail -40`.
- [ ] Commit: `feat(logic/actions/military): unit-set stat table + getTechCost (military constraints consumed from C-PURE/C-DEST)`.

### Task MIL2 — `RecruitAlgorithm` (징병/모병)
**Files:** create `military/RecruitAlgorithm.kt`; edit `CommandRegistry.kt`; test `military/RecruitAlgorithmTest.kt`.
Steps:
- [ ] Port `che_징병.php:21-236` as a shared `RecruitAlgorithm` parameterized by `costOffset` + train/atmos defaults; instantiate `징병`(costOffset 1, Low 40/40) + `모병`(costOffset 2, High 70/70). maxCrew=`leadership*100`; cost on APPLIED (post-cap) crew; getCost order (reqGold round(costWithTech→onCalcDomestic('징병','cost')*costOffset) — costOffset BEFORE round; reqRice round(maxCrew/100→onCalcDomestic('징병','rice'))); train/atmos blend stores RAW float (no round — FOLLOW PHP not TS); city mutation (reqCrewDown via onCalcDomestic('징집인구','score'); newTrust=valueFit(trust-(reqCrewDown/pop)/costOffset*100,0); pop-=reqCrewDown). **getLeadership(useFloor=true) drives maxCrew — PHP does NOT round through onCalcStat (unlike TS).** NONE draw from turn RNG; only the trailing unique lottery.
- [ ] Test: maxCrew cap; cost on applied crew; train/atmos raw-float blend; trust loss /costOffset (모병 halves); 모병 vs 징병 deltas; zero turn-RNG draws.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.military.RecruitAlgorithmTest' | tail -40`.
- [ ] Commit: `feat(logic/actions/military): RecruitAlgorithm → 징병/모병 (shared, costOffset 1/2, raw-float blend)`.

### Task MIL3 — 훈련 + 맹훈련
**Files:** create `military/{CheHullyeon,CrMaenghullyeon}.kt`; edit `CommandRegistry.kt`; test `military/TrainTest.kt`.
Steps:
- [ ] Port `che_훈련.php` (score `clamp(round(leadership*100/crew*30),0,clamp(100-train,0))`; addDex(crew,score,false); exp100 ded70) + `cr_맹훈련.php` (`round(leadership*100/crew*30*2/3)`; increaseVarWithLimit train/atmos [0..100]; addDex score*2; cost [0,500] rice; exp150 ded100).
- [ ] Test: both score formulas + clamps; addDex multiplier; exp/ded; increaseVarWithLimit [0..100].
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.military.TrainTest' | tail -30`.
- [ ] Commit: `feat(logic/actions/military): che_훈련 + cr_맹훈련`.

### Task MIL4 — 사기진작 + 소집해제
**Files:** create `military/{CheSagiJinjak,CheSojipHaeje}.kt`; edit `CommandRegistry.kt`; test `military/MoraleAndDisbandTest.kt`.
Steps:
- [ ] Port `che_사기진작.php` (atmos shape of 훈련; cost gold=round(crew/100)) + `che_소집해제.php` (crewUp via onCalcDomestic('징집인구','score'); pop+=crewUp; crew=0; exp70 ded100; NO leadership_exp, NO addDex, NO lottery).
- [ ] Test: 사기진작 atmos score + gold cost; 소집해제 pop add + crew=0 + no lottery.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.military.MoraleAndDisbandTest' | tail -30`.
- [ ] Commit: `feat(logic/actions/military): che_사기진작 + che_소집해제`.

### Task MIL5 — 이동 + 집합 (multi-general write)
**Files:** create `military/{CheIdong,CheJiphap}.kt`; edit `CommandRegistry.kt`; test `military/MoveAndGatherTest.kt`.
Steps:
- [ ] Port `che_이동.php` (cost [env.develcost,0]; atmos -=5 FLOOR 20; exp50; **roaming-leader** officer_level==12 && nation.level==0 → moves ALL nation generals + per-target PLAIN log; uses NearCity(1) over the **F-MAP `CalcCityDistance`/`nearCity` module (Wave-1 foundation, already landed)** — no longer gated on a Wave-3 module) + `che_집합.php` (troop-based multi-general city UPDATE; per-target PLAIN log; troopName read). These are the first multi-row general UPDATE flush shape (research Unit 2).
- [ ] Test: 이동 atmos floor 20; roaming-leader moves all + PLAIN logs; 집합 troop multi-general; multi-dirty-general via ChangeRecorder.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.military.MoveAndGatherTest' | tail -40`.
- [ ] Commit: `feat(logic/actions/military): che_이동 (roaming-leader multi-general) + che_집합 (troop multi-general)`.

**CMD-MILITARY gate:** per-command golden + multi-general log byte-match (GATE-RUNTIME); all getPreReqTurn=0 → SIMPLE 0-turn cooldown KV.

## AREA CMD-PERSONNEL — 인사 (← F-SUBSTRATE, C-DEST, F-RNG, F-GOLDEN-0)

> **Port target = PHP personnel family. Research Unit 3** (JOIN/LEAVE families, 랜덤임관 the only RNG-heavy one with the in-loop affinity BUG-FAITHFUL replication, ScoutMessage lifecycle).

### Task PR1 — 임관 + 장수대상임관 (JOIN effect template)
**Files:** create `personnel/{CheImgwan,CheJangsuDaesangImgwan}.kt`; edit `CommandRegistry.kt`; test `personnel/JoinTest.kt`.
Steps:
- [ ] Port `che_임관.php` + `che_장수대상임관.php`: exp 700 if destNation.gennum<10 else 100; JOIN transition (nation=dest, officer_level=1, officer_city=0, belong=1, troop=0, city=destLord's city); gennum+1; `increaseInheritancePoint(active_action,1)`. Join constraints (AllowJoinDestNation/AllowJoinAction/BeNeutral). No gameplay RNG (only trailing lottery).
- [ ] Test: exp 700/100 branch; JOIN transition fields; gennum+1; constraint pack.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.personnel.JoinTest' | tail -30`.
- [ ] Commit: `feat(logic/actions/personnel): che_임관 + che_장수대상임관 (JOIN template)`.

### Task PR2 — 하야 + 방랑 (cross-entity + bulk cascade)
**Files:** create `personnel/{CheHaya,CheBangrang}.kt`; create `logic/actions/founding/FoundingCascade.kt` (shared with founding); edit `CommandRegistry.kt`; test `personnel/LeaveTest.kt`.
Steps:
- [ ] Port `che_하야.php` (betray multiply `exp*=(1-0.1*betray) ded*=(1-0.1*betray)`, betray+1 cap 9; gold/rice clamp+return; nation=0/officer_level=0/belong=0/makelimit=12; `gennum-=(NPCType!=5?1:0)`; troop dissolution — first cross-entity personnel write) + `che_방랑.php` (lord-only, bulk UPDATE…WHERE: nation revert + all generals officer_level reset + cities nation=0/front=0/conflict='{}' + diplomacy state=2/term=0 — the **방랑 cascade ORDER load-bearing**, research Unit 4). 방랑 has NO lottery.
- [ ] Test: 하야 betray formula + gold/rice return + gennum-1 (NPCType!=5); 방랑 cascade order; bulk-cascade dirty via ChangeRecorder.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.personnel.LeaveTest' | tail -40`.
- [ ] Commit: `feat(logic/actions/personnel): che_하야 (betray/return/gennum) + che_방랑 (cascade order)`.

### Task PR3 — 랜덤임관 (deterministic RNG harness — highest parity risk)
**Files:** create `personnel/CheRandomImgwan.kt`; edit `CommandRegistry.kt`; test `personnel/RandomImgwanTest.kt`.
Steps:
- [ ] Port `che_랜덤임관.php:27-289` BUG-FAITHFUL: NPC-foreign branch `shuffle($nations)` FIRST, then per-nation `score=log2(affinity+1)+nextFloat1()+sqrt(gennum/allGen)`, keep MIN (maxScore init 1<<30), **$affinity reassigned inside the loop (accumulates) — replicate exactly**. ELSE branch weighted pair `(1/(warpower+develpower))^3` via `choiceUsingWeightPair`; talkList `choice` AFTER dest chosen. Order: [shuffle|query]→score loop→choice(talk)→unique lottery.
- [ ] Test: NPC-foreign in-loop affinity accumulation reproduced; weighted-pair branch; draw order; determinism with a fixed seed.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.personnel.RandomImgwanTest' | tail -40`.
- [ ] Commit: `feat(logic/actions/personnel): che_랜덤임관 (bug-faithful in-loop affinity + weighted-pair)`.

### Task PR4 — 은퇴 + rebirth + 2-turn handling
**Files:** create `personnel/CheEuntwe.kt`; edit `logic/domain` (rebirth helper); edit `CommandRegistry.kt`; test `personnel/EuntweTest.kt`.
Steps:
- [ ] Port `che_은퇴.php` (reqAge=60, getPreReqTurn=1 → 2-turn; if env.isunited==0 call CheckHall BEFORE rebirth) + `General::rebirth()` (stats*0.85 floor10, exp/ded*0.5, age=20, dex*0.5, RankColumn=0). NOT calling increaseInheritancePoint. CheckHall + InheritancePointManager are succession subsystems → **guarded stub if out of P2 phase** (research Unit 3 gap — port rebirth math, stub CheckHall).
- [ ] Test: reqAge=60 constraint; 2-turn cooldown; rebirth math; no inheritance bump.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.personnel.EuntweTest' | tail -30`.
- [ ] Commit: `feat(logic/actions/personnel): che_은퇴 + rebirth math + 2-turn (CheckHall stubbed)`.

### Task PR5 — 등용 SEND only (등용수락 + full mailbox DEFERRED to P6)
**Files:** create `personnel/CheDeungyong.kt`; edit `CommandRegistry.kt`; test `personnel/ScoutTest.kt`. (NO `CheDeungyongSurak.kt`/`ScoutMessage.kt` in P2 — deferred to P6.)
Steps:
- [ ] **SCOPE DECISION (research OQ7 — RESOLVED, not executor-deferred):** **ship `che_등용` SEND ONLY.** Port `che_등용.php` (verified): `getCost()` = `Util::round(env.develcost + (dest.experience + dest.dedication)/1000) * 10` (**round BEFORE the `*10`** — `che_등용.php:111-114`); on run() push the send log `"<Y>{destName}</>에게 등용 권유 서신을 보냈습니다. <1>{date}</>"`, then self-mutation `addExperience(100)` / `addDedication(200)` / `increaseVar('leadership_exp', 1)` / `increaseVarWithLimit('gold', -reqGold, 0)` (`che_등용.php:161-169`); write a MINIMAL `message` row INSERT (scout JSON option — the letter payload only, no mailbox lifecycle); does NOT transition anyone. The SEND golden (action-log + cost + self-mutation) byte-matches. `che_등용` does NOT call `increaseInheritancePoint`. **DEFER to P6 (state explicitly): `등용수락` (accept) + decline + the full `Message`/`MessageTarget`/`agreeMessage`/`buildFromArray` mailbox subsystem + `SetScoutMsg`/`BlockScout`.** Do NOT ship 등용수락 in P2.
- [ ] Test: 등용 getCost round-BEFORE-`*10` (assert against a `(experience+dedication)` value where rounding order changes the result); self rewards (+exp100/+ded200/+leadership_exp1/−reqGold gold-floor 0); send log byte-match; minimal `message` row INSERT shape; no transition. (등용수락/decline DEFERRED to P6 — no test here.)
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.personnel.ScoutTest' | tail -40`.
- [ ] Commit: `feat(logic/actions/personnel): che_등용 SEND only (round-before-*10 cost, self-mutation, minimal message INSERT; 등용수락/mailbox deferred to P6)`.

**CMD-PERSONNEL gate:** global-history (방랑) + succession lines in GATE-RUNTIME; 랜덤임관 RNG-parity golden; 등용 SEND golden (action-log + cost + self-mutation). (등용수락/decline dual-logger DEFERRED to P6.)

## AREA CMD-FOUNDING — 건국/거병 (← F-SUBSTRATE created-set/cascade, F-DOMAIN Nation-full, C-PURE+C-DEST, F-FLUSH, F-RNG)

> **Port target = PHP founding family. Research Unit 4** (the FIRST commands that CREATE entities — 거병 INSERTs nation+diplomacy+24 nation_turn — and CASCADE-mutate — 방랑). 거병 nationName '㉥' dedup + GetNationColors 33-element array verbatim.

### Task FND1 — Founding-preset verification (presets owned by C-PURE/C-DEST)
**Files:** test `logic/src/test/.../constraints/PresetsFoundingTest.kt`. (Does NOT edit `Presets.kt` — see below.)
Steps:
- [ ] **Wave-3 shared-file protocol: `Presets.kt` is a Wave-2 prerequisite — the founding presets are CREATED in C-PURE/C-DEST, NOT here.** The founding set is: `constructableCity`/`neutralCity` + `wanderingNation('방랑군이어야 합니다'` — NO trailing period) + `reqNationValue('gennum')` + `beOpeningPart`/`notOpeningPart` (all in **C-PURE**); `checkNationNameDuplicate('존재하는 국가명입니다.')` + `allowDiplomacyStatus` (in **C-DEST**). If any founding-only preset is missing from C-PURE/C-DEST, **add it there** (a HARD dependency) — FND1 only CONSUMES. FND1's `PresetsFoundingTest` is a CONSUMPTION test: it asserts the founding commands see the byte-exact PHP reasons (incl the no-period wandering reason) via the C-PURE/C-DEST presets; it does not define them.
- [ ] Test: each founding deny+allow with byte-exact PHP reasons (against the C-PURE/C-DEST presets); the no-period wandering reason.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.constraints.PresetsFoundingTest' | tail -30`.
- [ ] Commit: `test(logic/constraints): founding-preset consumption verification (presets owned by C-PURE/C-DEST)`.

### Task FND2 — 방랑 resolver (pure cascade — do first)
**Files:** CONSUME `logic/actions/founding/FoundingCascade.kt` (**CREATED by PR2 — explicit cross-edge `FND2 depends-on PR2`, the Wave-3 foundation route; FND2 does NOT create it**); wire 방랑 (a founding-cascade variant); test `founding/BangrangCascadeTest.kt`.
Steps:
- [ ] Confirm 방랑 (shared with personnel PR2) writes the cascade in the load-bearing ORDER: DeleteConflict → nation revert → general makelimit=12 (all) → demote non-lords officer_level=1 → cities nation=0/front=0/conflict='{}' → diplomacy state=2/term=0. active_action+1. NO created-set.
- [ ] Test: cascade order; created-set empty; dirty cascade collections populated.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.founding.BangrangCascadeTest' | tail -30`.
- [ ] Commit: `feat(logic/actions/founding): che_방랑 cascade (load-bearing order)`.

### Task FND3 — 거병 (canonical created-set)
**Files:** create `founding/CheGeobyeong.kt`; create `logic/domain/GetNationColors.kt` (33-element array); edit `CommandRegistry.kt`; test `founding/GeobyeongTest.kt`.
Steps:
- [ ] Port `che_거병.php:25-186`: INSERT nation (color='#330000', gold=0, rice=2000, rate=20, bill=100, strategic_cmd_limit=12, surlimit=72, secretlimit=3 or 1 if scenario>=1000, type='che_중립', gennum=1); '㉥' (U+3265) dedup with width-based truncation; diplomacy for EVERY other nation (TWO rows {me:dest,you:new}+{me:new,you:dest} state=2 term=0, in nation-id ascending order); nation_turn outer loop [12,11] inner 0..11 → 24 rows action='휴식'; general move; exp/ded+100; active_action+1; trailing unique lottery (reason='거병') AFTER all writes. GetNationColors 33-element array verbatim (`func_legacy.php:105-114`).
- [ ] Test: nation INSERT literals; '㉥' dedup (single + double prefix); 24 nation_turn rows; diplomacy 2×(N-1) rows in ascending order; lottery fires last.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.founding.GeobyeongTest' | tail -40`.
- [ ] Commit: `feat(logic/actions/founding): che_거병 (INSERT nation + diplomacy + 24 nation_turn + ㉥ dedup)`.

### Task FND4 — 건국 + cr_건국 + 무작위건국
**Files:** create `founding/{CheGeonguk,CrGeonguk,CheMujakwiGeonguk}.kt`; edit `CommandRegistry.kt`; test `founding/GeongukTest.kt`.
Steps:
- [ ] Port `che_건국.php` (level 0→1, city claim, aux can_국기변경=1, exp/ded+1000, active_action+1 AND **unifier+250**, same-month guard → alternative che_인재탐색) + `cr_건국.php` (NeutralCity instead of ConstructableCity, NO unifier+250 — divergence) + `che_무작위건국.php` (rng->choice cities WHERE level>=5 AND <=6 AND nation=0 DB-ascending, relocate all generals, aux can_무작위수도이전=1, NO unifier). RNG choice draw order (two consumers fixed order for 무작위건국). Same-month block needs env.init_year/init_month.
- [ ] Test: 건국 unifier+250; cr_건국 no-unifier divergence; 무작위건국 RNG choice draw order + relocate; aux jsonb key order; same-month alternative.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.founding.GeongukTest' | tail -40`.
- [ ] Commit: `feat(logic/actions/founding): che_건국 + cr_건국 + che_무작위건국 (unifier divergence + RNG choice)`.

**CMD-FOUNDING gate:** GATE-SATELLITE (created-set + cascade flush byte-comparable: nation INSERT, 24 nation_turn, 2×diplomacy). RaiseNPCNation deferred (reuses FND infra). che_인재탐색/che_해산 alternative stubs.

## AREA CMD-NATION-INTERNAL — 국가 내정 (← F-SUBSTRATE destGeneral/LastTurn/capset, F-DOMAIN, C-DEST, F-FLUSH, F-GOLDEN-0)

> **Port target = PHP `NationCommand.php` base + the 6+2 nation-internal commands. Research Unit 5** (NationCommand 3-arg ctor + per-NATION cooldown KV; EXP/DED = 5*(preReqTurn+1); INHERITANCE; COST; capset term-stack; 천도 KV side-effect; 발령 dual-general; 포상 double-clamp; 국호변경 runtime dup-name fail).

### Task NI1 — `NationCommand` base + KV plumbing
**Files:** create `logic/actions/nation/NationCommand.kt`; test `nation/NationCommandBaseTest.kt`.
Steps:
- [ ] Port `NationCommand.php:8-59` base: `(General, env, LastTurn, arg)` ctor; `resultTurn = lastTurn.duplicate()`; the per-NATION `next_execute_{actionName}` KV protocol (`setNextAvailable` math: joinYearMonth+postReqTurn-preReqTurn; only when postReqTurn truthy). EXP/DED formula `5*(getPreReqTurn()+1)`. Port the KV plumbing now (dead for this 8-command set, all postReqTurn=0, but part of the contract — research OQ12).
- [ ] Test: ctor seeds resultTurn from lastTurn; EXP/DED = 5*(preReqTurn+1) (감축/증축→30, 천도 variable, 국호/국기→5); next_execute KV math.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.nation.NationCommandBaseTest' | tail -30`.
- [ ] Commit: `feat(logic/actions/nation): NationCommand base (3-arg ctor, next_execute KV, exp=5*(preReqTurn+1))`.

### Task NI2 — 감축 + 증축 (capset term-stack, lowest blast radius)
**Files:** create `nation/{CheGamchuk,CheJeungchuk}.kt`; edit `CommandRegistry.kt`; test `nation/GamchukJeungchukTest.kt`.
Steps:
- [ ] Port `che_감축.php` (cost develcost*500+30000 REFUNDED; custom capset-seq addTermStack; city decrements pop=greatest(pop-100000,30000)/agri/comm/secu/def/wall=greatest(x-2000,0)/level-1/all *_max-=increment can go negative; `【감축】<M>` log; setResultTurn term=0; preReqTurn=5 → exp/ded+30; active_action+1) + `che_증축.php` (cost develcost*500+60000 SPENT; current UNCHANGED, *_max+=increment, level+1; `【증축】<C>`). Both bump nation.capset.
- [ ] Test: 감축 refund + city floors + level-1 + *_max negative; 증축 spend + *_max only + level+1; capset bump; exp/ded+30; term-stack reset to 0 on success.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.nation.GamchukJeungchukTest' | tail -40`.
- [ ] Commit: `feat(logic/actions/nation): che_감축 + che_증축 (capset term-stack, cost refund/spend, *_max math)`.

### Task NI3 — 발령 + 포상 (destGeneral + dual applyDB)
**Files:** create `nation/{CheBallyeong,ChePosang}.kt`; edit `CommandRegistry.kt`; test `nation/BallyeongPosangTest.kt`.
Steps:
- [ ] Port `che_발령.php` (self-target AlwaysFail; getFailString override; destGeneral mutate + applyDB-both; last발령 = yearMonth +1 if actor/dest in different turn buckets, on dest aux jsonb; apply ORDER setResultTurn→applyDB(general)→applyDB(dest)→handleEvent; grants ZERO exp/ded; NO inheritance) + `che_포상.php` (reward gold/rice; **DOUBLE-CLAMP** argTest round(amount,-2)+valueFit(100,10000), run() RE-clamp valueFit(amount,0,nation[resKey]-reserve) reserve=basegold0/baserice2000; nation debit; apply ORDER setResultTurn→handleEvent→applyDB(general)→applyDB(dest); ZERO exp/ded; NO inheritance).
- [ ] Test: 발령 self AlwaysFail + getFailString + dual applyDB order + last발령 turn-bucket +1; 포상 double-clamp + nation debit + apply order; both grant zero exp/ded.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.nation.BallyeongPosangTest' | tail -40`.
- [ ] Commit: `feat(logic/actions/nation): che_발령 (dual applyDB, last발령) + che_포상 (double-clamp)`.

### Task NI4 — 국호변경 + 국기변경 (aux-gate + runtime dup-name fail)
**Files:** create `nation/{CheGukhoByeongyeong,CheGukgiByeongyeong}.kt`; edit `CommandRegistry.kt`; test `nation/RenameTest.kt`.
Steps:
- [ ] Port `che_국호변경.php` (ReqNationAuxValue can_국호변경 gate; **RUNTIME dup-name fail** after constraints pass — run() SELECT dup → pushes exact log `이미 같은 국호를 가진 곳이 있습니다. {국호변경} 실패 <1>{date}</>` and returns FALSE WITHOUT consuming aux; on success aux=0; `【국호변경】<S>`) + `che_국기변경.php` (aux-gate, aux=0 on success; `【국기변경】<S>`). preReqTurn=0 → exp/ded+5 (NOT +30 — TS hardcodes +30, research Unit 5). active_action+1. jsonb key order via Json::encode byte-match.
- [ ] Test: aux-gate denial; runtime dup-name fail WITHOUT consuming aux (the exact log + FALSE); success aux=0; exp/ded+5; inline-style log.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.nation.RenameTest' | tail -40`.
- [ ] Commit: `feat(logic/actions/nation): che_국호변경 (runtime dup-fail, no aux consume) + che_국기변경`.

### Task NI5 — 천도 (map/distance dep) + 무작위수도이전 (scope-flag)
**Files:** create `nation/{CheCheondo,CheMujakwiSudoIjeon}.kt`; CONSUME `logic/world/CalcCityDistance.kt` (the F-MAP Wave-1 foundation — already created in FM1, NOT created here); edit `CommandRegistry.kt`; test `nation/CheondoTest.kt`.
Steps:
- [ ] Port `che_천도.php` (cost develcost*5*2^distance where distance=calcCityDistance(capital,dest,[nationId]) ?? 50; preReqTurn=dist*2 → variable exp/ded; **run() does NOT debit gold/rice** — cost is precondition only; last천도Trial KV `[officer_level, turnTime]` on EVERY availability poll; refreshNationStaticInfo invalidation; `【천도】<S>`). **천도 consumes the F-MAP `CalcCityDistance` Wave-1 foundation (FM1) for `distance` — no longer gated on a Wave-3 map module.** `che_무작위수도이전.php` (rng->choice the only RNG here; multi-general move; aux can_무작위수도이전-=1) — **SCOPE FLAG: it is 'adjacent', not in the named 6; confirm in/out of P2 scope (research Unit 5 gap) — default IN if the map module lands, else DEFER**.
- [ ] Test: 천도 variable preReqTurn/cost; cost-only (no debit); last천도Trial KV every poll; calcCityDistance; refreshNationStaticInfo. 무작위수도이전 RNG choice + multi-general (if in scope).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.nation.CheondoTest' | tail -40`.
- [ ] Commit: `feat(logic/actions/nation): che_천도 (variable cost/turn, last천도Trial KV) + che_무작위수도이전 (scope-gated)`.

**CMD-NATION-INTERNAL gate:** GATE-SATELLITE (diffNation + rank_data + lastTurn jsonb + 4 extra log scopes + KV after-image byte-comparable); precheck==full via buildMinConstraints.

## AREA CMD-TRADE — 자원교역 (← F-DOMAIN, C-PURE +trader/dest, F-RNG, F-GOLDEN-0)

> **Port target = PHP trade family. Research Unit 6.** 군량매매 is in CMD-DEVELOP (DV5); here are 증여/헌납/장비매매. 물자원조 OUT (diplomatic).

### Task TR1 — 증여 + 헌납
**Files:** create `trade/{CheJeungyeo,CheHeonnap}.kt`; edit `CommandRegistry.kt`; test `trade/GiftTributeTest.kt`.
Steps:
- [ ] Port `che_증여.php` (valueFit against minimum; dest PLAIN log — dual-entity flush; exp70/ded100/leadership_exp+1; argTest `Util::round(amount,-2)`+valueFit(100,10000) + destGeneralID != self; constraints NotBeNeutral+OccupiedCity+SuppliedCity+ExistsDestGeneral+FriendlyDestGeneral) + `che_헌납.php` (nation treasury add; getCommandDetailTitle '(통솔경험)'; fixed leadership_exp+1). 1:1 resource move (no trade rate). `increaseVar(key,0)` NO-OP. Trailing lottery only.
- [ ] Test: 증여 dest PLAIN log + dual flush + argTest self-reject; 헌납 nation add; both 1:1; no-op on zero.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.trade.GiftTributeTest' | tail -30`.
- [ ] Commit: `feat(logic/actions/trade): che_증여 (dual log) + che_헌납`.

### Task TR2 — 장비매매 (after ITEMS)
**Files:** create `trade/CheJangbiMaemae.kt`; edit `CommandRegistry.kt`; test `trade/JangbiMaemaeTest.kt`.
Steps:
- [ ] **Depends on ITEMS** (declarative item stats + the 도기 onArbitraryAction hook). Port `che_장비매매.php:27-205` (itemMap; getCost; buy/sell; sell refund cost/2; non-buyable Global broadcast).
- [ ] Test: buy/sell against the item registry; sell refund cost/2; non-buyable global broadcast.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.trade.JangbiMaemaeTest' | tail -30`.
- [ ] Commit: `feat(logic/actions/trade): che_장비매매 (item buy/sell, sell refund, global broadcast)`.

**CMD-TRADE gate:** per-command golden (군량매매 nation-gold tax, 증여 dual log) in GATE-RUNTIME; float-vs-int int-cast-on-write byte-match (research Unit 6 #1 risk).

---

# TIER 2 (parallel) — Trait + item modules

## AREA TRAITS-DOMESTIC — 8 내정특기 (← S-MODULES)

> **Port target = PHP `ActionSpecialDomestic/*.php`. Research Unit 9** (exactly 8 — NOT ~30; the "~30" conflates domestic(8)+war(21); design §11's "~30" is the aggregate). Each overrides ONLY `onCalcDomestic`. ZERO RNG in hooks.

### Task TD1 — 8 domestic-specialty modules + None/거상 inert
**Files:** create `logic/traits/ActionSpecialDomestic.kt` (incl its `SpecialDomesticRegistry`); register into the S-MODULES-defined registry (do NOT edit `GeneralActionModuleFactory.kt`); test `traits/SpecialDomesticTest.kt`.
Steps:
- [ ] Port the 8 (경작/상재/발명/축성/수비/통찰/인덕/귀모) as `GeneralActionModule` impls overriding only `onCalcDomestic`. 6/8 uniform: matching turnType → score*1.1, cost*0.8, success+0.1. 인덕 gates `(turnType=='민심'||'인구')`. 귀모 outlier `turnType=='계략' && varType=='success' → +0.2`. turnType↔special↔actionKey table (농업→경작, 상업→상재, 기술→발명, 성벽→축성, 수비→수비, 치안→통찰, 민심/인구→인덕, 계략→귀모; 통찰 gates '치안' but STAT_STRENGTH-typed). None/거상(id=999) inert (identity).
- [ ] Test: each special's magnitude on its turnType; non-matching turnType identity; 인덕 two-key; 귀모 +0.2; 거상 inert; ZERO RNG (draw count unchanged).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.traits.SpecialDomesticTest' | tail -40`.
- [ ] Commit: `feat(logic/traits): 8 domestic-specialty modules (uniform 1.1/0.8/+0.1, 인덕 two-key, 귀모 +0.2)`.

## AREA TRAITS-PERSONALITY — 12 성격 (← S-MODULES)

### Task TP1 — 12 personality modules + PersonalityRegistry
**Files:** create `logic/traits/ActionPersonality.kt` (incl its `PersonalityRegistry`); register into the S-MODULES-defined registry (do NOT edit `GeneralActionModuleFactory.kt`); test `traits/PersonalityTest.kt`.
Steps:
- [ ] Port the 12 (왕좌/대의/의협/패권/정복/할거/출세/재간/유지/안전 + 은둔 + None) — `ActionPersonality/*.php`. Effects on onCalcStat (experience*1.1/0.9, dedication*0.9, bonusTrain/bonusAtmos ±5 — bonusTrain/atmos flow to WAR-init only, implement but consumer is war-side) + onCalcDomestic [징병|모병].cost. ADDITIVE exceptions: 은둔 단련 success+0.1. NAME/INFO = PHP `getInfo()` "{pros} {cons}" single space (NOT TS '장점:X / 단점:Y'). neutral='None'.
- [ ] Test: each personality magnitude; multiplicative vs additive; bonusTrain/atmos ±5; name/info PHP format; None no-op.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.traits.PersonalityTest' | tail -40`.
- [ ] Commit: `feat(logic/traits): 12 personality modules + PersonalityRegistry (PHP getInfo format)`.

## AREA TRAITS-NATION — 15 국가타입 (← S-MODULES)

### Task TN1 — 15 nation-type modules + NationTypeRegistry + onCalcStrategic/income
**Files:** create `logic/traits/ActionNationType.kt` (incl its `NationTypeRegistry`); register into the S-MODULES-defined registry (do NOT edit `GeneralActionModuleFactory.kt`); test `traits/NationTypeTest.kt`.
Steps:
- [ ] Port the 15 (덕가/도가/도적/명가/묵가/법가/병가/불가/오두미도/유가/음양가/종횡가/태평도 + che_중립 + None) — `ActionNationType/*.php`. Effects on onCalcDomestic (치안/민심/인구/기술/농업/상업/수비/성벽/계략), onCalcNationalIncome (gold/rice/pop — pop branch gated `&& amount>0`), onCalcStrategic (음양가 delay=round(v*4/3), 종횡가 delay=round(v*3/4)/globalDelay=round(v/2) — **Util::round mid-fold, order MATTERS**). ADDITIVE: 도적 계략 success+0.1. NAME/INFO PHP format. neutral='che_중립'.
- [ ] Test: each nation-type magnitude on its turnType; income pop>0 guard; strategic mid-fold rounding (음양가/종횡가); che_중립 no-op.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.traits.NationTypeTest' | tail -40`.
- [ ] Commit: `feat(logic/traits): 15 nation-type modules (domestic+income+strategic mid-fold round)`.

## AREA ITEMS — 선언적 아이템 스탯 (← F-DOMAIN item slots, S-MODULES)

> **Port target = PHP `ActionItem/*.php` + `BaseStatItem.php`. Research Unit 11** (declarative +stat parsed from class name; fold LAST horse→weapon→book→item; parent-first within a single item; ability items year-scaled).

### Task IT1 — Declarative +stat item modules + registry + dedup
**Files:** create `logic/items/ItemModules.kt` (incl its `ItemRegistry`); register into the S-MODULES-defined registry (do NOT edit `GeneralActionModuleFactory.kt`); test `items/ItemModulesTest.kt`.
Steps:
- [ ] Port `BaseStatItem` as a `createStatItemModule` factory (ITEM_TYPE 명마→leadership/무기→strength/서적→intel; encode the three fields explicitly per item, NOT runtime reflection); the ITEM_KEYS registry (pure-declarative book/weapon/horse modules); 4-slot dedup (`listEquippedItemKeys` skips falsy AND dedups). `BaseStatItem.onCalcStat`: `if statName===statType return value+statValue`. Fold LAST, horse→weapon→book→item insertion order. None/empty → identity. Preserve name `"$rawName(+$statValue)"` + info `"$statLabel +$statValue"`.
- [ ] Test: +stat for each type; fold-last order; dedup same item in two slots applies once; None identity; name/info strings.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.items.ItemModulesTest' | tail -40`.
- [ ] Commit: `feat(logic/items): declarative +stat item modules + registry + 4-slot dedup`.

### Task IT2 — Domestic-hook + override-stat items
**Files:** create `logic/items/ItemHooks.kt`; register into the S-MODULES-defined `ItemRegistry` (do NOT edit `GeneralActionModuleFactory.kt`); test `items/ItemHooksTest.kt`.
Steps:
- [ ] Port the extra-hook items in P2 domestic scope: 납금박산로 (8 turnTypes success+0.15), 조달주판 (조달 success+0.2 score*2), 계략 books (삼략 계략 success+0.2), ability items 능력치 year-scaled (`value+5+valueFit(intdiv(relYear,4),0,12)` — relYear from WorldEnv), 동작 addDex*1.20 (MULTIPLICATIVE), 평만지장도 delay*0.80 (`Util::round(value*0.80)`). **parent-first within a single item** (base +stat THEN extra delta — base+decorator). War-side hooks (getBattle*/getWarPowerMultiplier) STUBBED (out of domestic scope, research Unit 11 decision); the 도기 onArbitraryAction (장비매매) ties to TR2.
- [ ] Test: each hook magnitude; year-scaled ability item; addDex*1.20; 평만지장도 phpRound; parent-first order.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.items.ItemHooksTest' | tail -40`.
- [ ] Commit: `feat(logic/items): domestic-hook + override-stat items (year-scaled/addDex/delay, parent-first)`.

### Task IT3 — onCalcNationalIncome tick + onCalcStrategic consumers (INCOME-TICK)
**Files:** edit `logic/domestic/DomesticHelpers.kt` (income helpers); create `logic/domestic/IncomeTick.kt`; test `domestic/IncomeTickTest.kt`.
Steps:
- [ ] Port the income fold (research Unit 12 INCOME-TICK): PER CITY compute float → `nationIncomeFold(nationType, type)` → `Util::round` → int; SUM rounded ints; THEN `cityIncome *= (taxRate/20)` with NO final round; `getOutcome` rounds the SUM. capital factor gold/rice `1+(1/(3/level))` vs wall `1+1/(3*level)` (note parenthesization, `func_time_event.php`). onCalcStrategic consumers DOUBLE rounding (consumer rounds base THEN module rounds). Income uses the NATION-TYPE source ONLY (the FP1 `nationIncomeFold` path).
- [ ] Test: per-city round-then-sum-then-scale; income nation-type-only; getOutcome SUM round; capital factor parenthesization; strategic double-round.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.domestic.IncomeTickTest' | tail -40`.
- [ ] Commit: `feat(logic/domestic): income tick (per-city round-sum-scale, nation-type-only) + strategic double-round`.

**TRAITS/ITEMS gate:** GATE-TRAIT (non-default-general non-identity fold byte-match).

---

# TIER 3 — Gates (serial close)

## AREA GATE-RUNTIME — Per-command Kotlin golden tests (← F-GOLDEN-0 + each CMD-*)

### Task GR1 — Per-command golden capture (manual host, committed)
**Files:** run `tools/php-golden/capture_command.php` per manifest command; output committed `logic/src/test/resources/golden/p2/<command>-fixtures.json` + golden DB fragments.
Steps:
- [ ] **One-shot manual host step — never CI** (project memory quirks: j_install twice, getopt =, reflection creds, install non-idempotent, byte-identical dumps). For each manifest command: capture distinct reachable outcomes (success/normal/fail picks via `probe_command.php`); module-free general (assert special/special2/personal empty + 8 effect slots null + itemObjs empty) OR synthetic-seed where none exists; full env + per-action seed string; before/after rows char-for-char; action-log row(s) per the expected-line-count (including dest-general + broadcast + PLAIN lines); the per-game hiddenSeed (committed as fixture input). For TRAITS/ITEMS goldens, capture a general WITH the trait/item active (the GATE-TRAIT non-identity case). For S-LEVEL, capture a fixture that INTENTIONALLY crosses a level (the inverse of P1's no-cross assertion).
- [ ] Commit: `chore(tools): P2 per-command PHP goldens + DB fragments (committed fixtures)`.

### Task GR2 — Per-command Kotlin golden tests (Tier-1 byte gate)
**Files:** create `logic/src/test/.../golden/<Family>GoldenTest.kt` (one per family, parameterized over its commands).
Steps:
- [ ] For each command via `CommandRegistry`: build general/city/nation/env from the golden BEFORE state; seed the per-action rng with the captured SIX-component seed (generalCommand OR nationCommand fork, component 6 = the definition's `rawClassName` = PHP short class name incl che_/cr_ prefix); set `context.date`; resolve; assert the FULL ordered `context.logs()` (+ dest/broadcast/PLAIN buckets) == golden action-log byte-for-byte; assert post-state numbers (gold/exp/ded/city columns/nation/meta/aux) == golden AFTER. Determinism: two runs same seed identical.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.golden.*GoldenTest' | tail -60`.
- [ ] Commit: `test(logic/golden): per-command action-log + post-state byte-match PHP golden`.

**GATE-RUNTIME:** every command's log + post-state byte-match GREEN.

## AREA GATE-STATIC — compare-command-logs PHP↔Kotlin matched-count (← each CMD-* + TRAITS-*)

### Task GS1 — PORT compare-command-logs.mjs (PHP↔Kotlin) + ignore-list backlog
**Files:** create `tools/php-golden/compare-command-logs/` (Kotlin source extractor), `compare-command-logs.ignore.json`.
Steps:
- [ ] **PORT** `legacy/devsam-core2026/tools/compare-command-logs.mjs` into `tools/php-golden/compare-command-logs/`, **re-pointing `PHP_ROOT` from `legacy/hwe/sammo/Command` → `legacy/devsam-core/hwe/sammo/Command`** (the PHP grand-truth tree). KEEP the PHP extractor + `normalizeTemplate` (strip `/<1>.*?<\/>/`, strip `<b>`, replace `${...}`/`$var` with `${}`, collapse whitespace, trim) + the default excludes (guard logs + target logs) + the report; REPLACE the TS source extractor with a Kotlin source extractor (scan `:logic/actions` resolver sources, mirror the mjs normalizer — static source scan, no build). Per-command key General/<name>, Nation/<name>. Seed `ignore.json` with the ~57-of-93 deferred commands (each explicitly listed). The matched-count 0-mismatch rises monotonically as families land.
- [ ] CI assertion: `mismatches == 0` AFTER filtering AND every ignored key documented; matched-count rises monotonically.
- [ ] Commit: `chore(tools): PORT compare-command-logs.mjs PHP↔Kotlin (PHP_ROOT→devsam-core) + ignore-list backlog (0-mismatch gate)`.

**GATE-STATIC:** matched-count 0-mismatch (design §11 P2 "matched-count 0 mismatch 상승").

## AREA GATE-SATELLITE — Satellite/KV write-set goldens (← F-FLUSH + CMD-NATION/PERSONNEL/FOUNDING)

### Task GSat1 — Satellite write-set byte-comparable golden
**Files:** create `infra/src/test/.../persistence/SatelliteFlushGoldenIT.kt` (Testcontainers postgres).
Steps:
- [ ] For commands that touch satellites (감축/증축 nation+capset; 발령/포상 dual-general; 거병 created-set+24 nation_turn+diplomacy; 방랑 cascade; 천도 KV): flush the post-state payload; SELECT rank_data + nation + nation_turn + nation_env KV + log_entry; assert row+jsonb byte-comparable to the golden DB fragment. **`RANK_ROWS_PER_GENERAL = 37` is PINNED (verified against the PHP `RankColumn` enum, `RankColumn::cases()` = 37) — this golden CONFIRMS the 37-row rank_data write-set per general (not a re-resolution). The NationTurn `brief` column is PINNED present (V2 migration; `che_거병` writes `brief='휴식'` on all 24 nation_turn rows) — this golden CONFIRMS the brief after-image.**
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests 'opensamguk.infra.persistence.SatelliteFlushGoldenIT' | tail -50`.
- [ ] Commit: `test(infra/golden): satellite write-set (rank_data/nation_turn/KV) byte-comparable golden + pin 37/brief`.

**GATE-SATELLITE:** flush byte-comparable for the satellite write-sets (design §11 P2 "flush byte-comparable").

## AREA GATE-TRAIT — Non-default-general non-identity-fold golden (← TRAITS-* + ITEMS)

### Task GT1 — Non-identity stat-stack golden
**Files:** create `logic/src/test/.../golden/NonIdentityFoldGoldenTest.kt`.
Steps:
- [ ] Feed a non-default general (specialDomestic 경작 running 농지개간; a personality; a nation-type; an item set) through `getStatValue` + the relevant resolver; byte-match vs the PHP golden (GR1 captured the non-identity fixtures). Proves the 9-source module list folds in PHP `getActionList` order byte-faithfully.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.golden.NonIdentityFoldGoldenTest' | tail -40`.
- [ ] Commit: `test(logic/golden): non-identity 9-source fold byte-match (trait+item general)`.

### Task GT2 — precheck==full cross-call-site + full-suite sanity
**Files:** create `logic/src/test/.../constraints/PrecheckEqualsFullP2Test.kt` + an e2e cross-call-site test.
Steps:
- [ ] Extend the P1 `PrecheckEqualsFull` pattern to the P2 commands: for a representative command per family, evaluate `buildMinConstraints` (precheck) and `buildConstraints` (full) over the SAME view; assert identical Allow/Deny/Unknown + reason. Cross-call-site: drive the REAL `:app:game-api` `CommandPrecheckService` AND the REAL `:app:game-engine` full-mode evaluation against the same seeded world; assert identical outcome + reason for an AVAILABLE and a denying fixture per family. Add the designed-divergence case (data drifts between precheck and full → clean deny→rest, NOT a parity failure).
- [ ] Full-suite sanity: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test :infra:test :app:game-api:test :app:game-engine:test | tail -40` → all `BUILD SUCCESSFUL`.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.constraints.PrecheckEqualsFullP2Test' | tail -40`.
- [ ] Commit: `test: precheck==full cross-call-site (per family) + full P2 suite sanity`.

**GATE-TRAIT:** non-identity fold byte-match + precheck==full preserved.

---

## Self-Review

**Consistency hazards (verify before/while executing):**
- **ONE constraint library (precheck==full invariant preserved):** all constraint logic lives ONLY in `:logic/constraints/{Presets,Comparators,EvaluateConstraints}.kt`. `:app:game-api` (CommandPrecheckService) and `:app:game-engine` (ReservedTurnHandler full-mode) both IMPORT it; NEITHER re-implements `test()`. Guards: (a) grep-scan — preset names + `evaluateConstraints` appear ONLY in `:logic`; (b) `PrecheckEqualsFullP2Test` same-view PRECHECK==FULL per family; (c) the cross-call-site test driving the REAL api + engine entry points against the same world. The new `buildMinConstraints` (min/precheck) vs `buildConstraints` (full) split is the ONLY allowed divergence and it is the SAME library — the min set is a subset, never a re-implementation; `PrecheckEqualsFullP2Test` asserts min⊆full agreement.
- **JDBC-only write path preserved:** `DaemonNoEntityManagerTest` (`:app:game-engine`) + `InfraNoEntityManagerTest` (`:infra`) extend to the new write classes (NationRowMapper/DiplomacyRowMapper/NationTurnRowMapper + the satellite flush steps) — assert they reference only `org.springframework.jdbc.*` for persistence, NO `jakarta.persistence.EntityManager`. The JdbcFlushExecutor stays on the pinned `DataSourceTransactionManager`. JPA `@Entity`/repos live ONLY in `:app:game-api/read`. **Add a guard fixture test asserting the new mappers/flush classes are EntityManager-free** (extend the P1 guard, do not duplicate it).
- **10-step flush ORDER FROZEN:** new ops slot into the RESERVED slots (step-3 createMany / step-5 deleteMany / step-6 cascade / step-8 rank_data / step-10 KV+reserved_turns) — never reorder the contract. `FlushOpRecorder` order tests assert the new tags appear in-slot.
- **PHP grand truth, never TS:** the confirmed TS divergences (군량매매 buyRice overflow tax, constraint reason strings, `Math.round`, BaseNation.getInfo reformat, 감축 dropped capset/inheritance, 방랑 incomplete TODOs, 종횡가 tax) are each pinned in the relevant task's port_notes. Every reason-string test asserts the PHP byte.
- **RNG draw order + seed fork:** the generalCommand/nationCommand seed fork uses **`rawClassName` = `getRawClassName(true)` = the PHP short class name INCL the `che_`/`cr_` prefix** as the 6th component (NOT `definition.key`, NOT a de-spaced actionName — pin `che_랜덤임관`/`cr_맹훈련`/`cr_건국`, distinct classes; carried as `GeneralActionDefinition.rawClassName`); the **preprocess** fork is a 5-tuple `(hidden,'preprocess',y,m,g)` with NO classname; the unique-item lottery runs on a SEPARATE rng (NPCType>=2 short-circuit) seeded with a DIFFERENT token, `static::$actionName` (e.g. `che_랜덤임관` → `'무작위 국가로 임관'`), NOT `rawClassName`. The per-command draw orders (물자조달 leading `choice(gold/rice)`; 랜덤임관 shuffle-then-loop bug-faithful; RandomizeCityTradeRate per-city nextBool/nextRangeInt) are each pinned + golden-gated. The lottery rng must NOT advance the action draw stream.
- **Float-vs-int persistence (#1 byte-match risk, research Unit 6):** exp/ded/gold/rice accumulate as Double in-memory (no per-add round); int-cast (truncate-toward-zero) ONLY at the JDBC row mapper flush. train/atmos stay Double (raw float blend). nation.tech FLOAT — confirm downstream clamp. The flush byte-comparable golden is the oracle.
- **Module-free vs non-identity goldens:** P2 has BOTH — module-free generals (P1 pattern, for log/mutation fidelity on the resolver goldens) AND non-default generals (GATE-TRAIT, for the non-identity 9-source fold). The capture harness asserts module-free where required and explicitly equips the trait/item where the non-identity golden needs it.
- **Wave-3 shared-file protocol (NOT "only CommandRegistry"):** there are **4 real shared Wave-3 files** beyond `CommandRegistry.kt`, each moved to a foundation OWNER so Wave-3 stays file-disjoint: (1) **`Presets.kt`** — ALL presets are a Wave-2 prerequisite (C-PURE/C-DEST CREATE them; FND1/MIL1 only CONSUME — a hard dependency, no "skip if already added" hedge); (2) **`GeneralActionModuleFactory.kt`** — CREATED + owned by S-MODULES (Tier-1); TD1/TP1/TN1/IT1/IT2 only register INTO it via append; (3) **`FoundingCascade.kt`** — created by PR2 with an explicit cross-edge `FND2 depends-on PR2` (both consume it; never co-create); (4) **`DomesticHelpers.kt`** — pre-existing P1 file; S-LEVEL (SL1) adds the shared level-conversion helper block ONCE; DV2/IT3 add/consume disjoint helper keys (no overlapping bodies). `CommandRegistry.kt` is the only genuinely per-family append point (append-only per family, no two families touch the same `when` arm). With this protocol each Wave-3 family touches ONLY its own `actions/<family>/` + `traits/<family>/` + `items/` subtree + the append-only `CommandRegistry.kt` registration.

**Type/naming consistency:**
- DB column `intel` (not intelligence); `intel_exp`/`explevel`/`leadership_exp`/`strength_exp`/`dedlevel` ALL ride the general `meta` jsonb (VERIFIED: the V1 `general` table has NO such columns) — read/written via `GeneralRowMapper.toColumns` meta packing, NOT dedicated columns; `secu/def/wall/pop/trade` are real city columns; there is **NO `city.tech`** (tech is `nation.tech double precision`) — all VERIFIED against `V1__baseline.sql`. The only NEW migration is `V2__p2_brief.sql` (adds `brief text NOT NULL DEFAULT ''` to general_turn + nation_turn).
- **rank_data write-set = 37 rows/general** (`RANK_ROWS_PER_GENERAL=37` = `RankColumn.entries.size`, VERIFIED against the PHP `RankColumn` enum's 37 `cases()`); the stale engine `40` (`DatabaseHooks.kt:38`) + `DatabaseHooksOrderTest` `2*40` are reconciled to 37; step-8 is an UPDATE of the pre-seeded 37 rows, not an UPSERT.
- LastTurn.toRaw delete-on-default + nation_env KV `Json::encode` (bare int for next_execute, object for turn_last) byte-match via `MetaJson`.
- `phpRound` (half-away) for score/cost/strategic/income; `Util::toInt` (truncate) for the useFloor stat path — distinct, never swapped.

## Open Questions (carried forward from research §15 + design §14, with the P2 ruling)

The research's 15 consolidated OQs + the design §14 9-source merge/cache item, each with the plan's default ruling (resolve early in the relevant area or explicitly defer):

1. **P1 Kotlin golden GREEN first (HARD gate) — RESOLVED.** The P1 harness + golden tests are on disk and the P1 gate is closed (281 tests). F-GOLDEN-0 generalizes the proven harness.
2. **Domain entity expansion (universal prereq) — F-DOMAIN.** One shared foundation (FD0/FD1/FD2), not per-unit; land the `LogicEntities.kt` shape commit first in Wave 1.
3. **GeneralActionModule iface widening (universal stat-stack prereq) — F-PIPELINE (FP1).** Add onCalcStrategic/onCalcNationalIncome/onCalcOpposeStat + aux ONCE; populate the module list later.
4. **Rounding oracle = PHP only, never TS — pinned per task.** phpRound (half-away) is correct; TS divergences enumerated in Self-Review.
5. **getStatValue calcCache asymmetry + immutable-General home — FP2 ruling:** attach the cache as a per-resolve `StatCalc(general)` mutable wrapper (NOT mutating the data class); clear on any draft `.copy()`. Unfloored-only write, useFloor-excluded key.
6. **Unique-item lottery seam (shared by all ~36) — F-RNG (FR1).** Single foundation task ahead of any resolver; NPCType>=2 short-circuit; separate rng.
7. **ScoutMessage mailbox + diplomacy scope boundary — RESOLVED (PR5).** Ship `che_등용` SEND ONLY: getCost `Util::round(env.develcost+(dest.experience+dest.dedication)/1000)*10` (round BEFORE `*10`), self +exp100/+ded200/+leadership_exp1/−reqGold, a minimal `message` row INSERT (letter payload only), no transition; the SEND golden is the gate. **`등용수락`(accept) + decline + the full `Message`/`MessageTarget`/`agreeMessage`/`buildFromArray` mailbox + `SetScoutMsg`/`BlockScout` are DEFERRED to P6** (no longer an executor-time decision).
8. **NPCType taxonomy (cross-cutting guard) — F-RNG (`NpcType.kt`).** Ported once.
9. **Map / city-distance / adjacency module — RESOLVED (F-MAP, Wave-1 foundation).** A minimal `CalcCityDistance` map module (`logic/world/CalcCityDistance.kt`, FM1) lands as a **Wave-1 foundation** — pure BFS over `CityConst.kt`'s existing golden-locked bidirectional `path` adjacency. NearCity (military 이동/constraints), 천도 `calcCityDistance` (NI5), and 이동 adjacency (MIL5) all land on it; the C-PURE/C-DEST `nearCity` constraint key consumes its preloaded-distance set. **Full pathfinding (HasRoute*/NearNation, the C-DB constraint family) is DEFERRED to the map/diplomacy phase** (it spills past the P2 domestic boundary).
10. **RANK_ROWS_PER_GENERAL 40 vs 37 — RESOLVED = 37 NOW.** Verified against the PHP `sammo\Enums\RankColumn` enum (`RankColumn::cases()` = 37). Set `RANK_ROWS_PER_GENERAL = 37` (= `RankColumn.entries.size`), reconcile the stale engine `40` (`DatabaseHooks.kt:38`) + the `DatabaseHooksOrderTest` `2*40`→`2*37` (FF1/FF2). GSat1 CONFIRMS the 37-row write-set byte-comparable.
11. **NationTurn `brief` column (PHP yes, core2026/P1 no) — RESOLVED = include via V2 migration.** Verified V1 baseline `general_turn`/`nation_turn` have no `brief`; PHP writes it (`che_거병.php:151` `'brief'=>'휴식'` on all 24 rows). FD0a adds `brief text NOT NULL DEFAULT ''` to BOTH tables (`V2__p2_brief.sql`); GSat1 CONFIRMS the after-image.
12. **KV write position in the frozen 10-step order — FF2 ruling:** KV folds into step-10 (alongside reserved_turns), buffered into `DirtyState.kvDirty` (not eager), flushed in the one transaction.
13. **`develcost` / full env threading — F-DOMAIN + the SINGLE shared `WorldEnvBuilder` (reused/widened).** Keep ONE shared `WorldEnvBuilder` (so precheck and full never drift). **Note: each command family ADDS its own env keys with PHP-confirmed defaults** — `develcost` (per-server); `정착장려`/`주민선정` cost `develcost*2`; `천도` distance cost (`develcost*5*2^distance`); `발령` `turnterm` (+ dest `turnTime`); `거병` `scenario`; `건국` `init_year`/`init_month`; `isunited`. The shared builder is the single place these keys + defaults are declared.
14. **Specialty counts (design §11 "~30 내정특기") — RESOLVED:** domestic slot is exactly 8 (+None+거상); the "~30" conflates domestic(8)+war(21, P4). TRAITS-DOMESTIC is sized to 8.
15. **Per-source hook matrix not exhaustively enumerated — enumerate during TRAITS-*/ITEMS** (which of the 8 특기 / 12 성격 / 15 국가타입 / scenarioEffect / inheritBuff implement EACH onCalc* hook); the per-trait golden (GATE-TRAIT) is the empirical check. scenarioEffect/inheritBuff ship as identity stubs in P2 domestic (war/inheritance = P4/P6).

**Design §14 (9-source stack merge/cache):** "General action 스택 9소스의 정확 머지·캐시 무효화 규칙 (P1/P2)" — RESOLVED in this plan: F-PIPELINE pins the `MODULE_ORDER` (getActionList exact: nationType→officer→specialDomestic→specialWar→personality→crew→inherit→scenario→item[horse,weapon,book,item]) + the `getStatValue` calcCache (unfloored-only write + clear-on-mutate); S-MODULES builds the factory in that order; TRAITS-*/ITEMS populate the concrete modules; GATE-TRAIT proves the non-identity fold byte-matches PHP. The income fold's NATION-TYPE-ONLY asymmetry (not the general stack) is pinned in FP1.
