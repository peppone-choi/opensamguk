# H-BRIDGE-MAP — the 35 AI-emitted `che_*` codes → Kotlin pack status, the sub-package layout decision, and the CheBallyeong typo verdict (Task FR0, closes M1/m11/M9)

**Date:** 2026-05-30
**Owner:** F-BRIDGE (Tier-0 foundation). Consumed by FR1 (presets + che_출병 repair), FR2 (the 9 missing defs + registry), FR3 (the argTest gate + candidateAllowed).
**Sources (read in full / verified live):**
- PHP GRAND TRUTH: `legacy/devsam-core/hwe/sammo/GeneralAI.php` (the `buildNationCommandClass`/`buildGeneralCommandClass` emit sites), `legacy/.../Command/Nation/che_*.php`, `legacy/.../Command/General/che_*.php`.
- Live Kotlin: `logic/src/main/kotlin/opensamguk/logic/actions/CommandRegistry.kt` (the `resolve(actionCode)` `when`), `logic/.../actions/**`.
- Pins: `R-BRIDGE.md` §1/§2/§4 (the authoritative emitted-code → pack map), `R-NATIONPOL.md` §1 (NPC몰수 in the 20-entry priority).

**PARITY LAW reminder:** PHP wins. The emitted `che_*` code, its raw-vs-canonical args, its FULL `fullConditionConstraints` pack, and the `isArgValid`/argTest gate are ALL parity targets. A MISSING/DIVERGENT pack flips the AI gate boolean → wrong priority-loop branch → total downstream draw desync (the gate boolean is a P5 control-flow input even though the diplomacy/천도 RESULT is P6).

---

## (1) The 35 distinct AI-emitted `che_*` codes (R-BRIDGE §1, verified)

### NATION-emitted (`buildNationCommandClass`) — 5 distinct codes
| che_* code | emitted by do<한글> | GeneralAI.php emit lines |
|---|---|---|
| `che_발령` | 12 발령 methods (부대전방/부대후방/부대구출/부대유저장후방/유저장후방/유저장구출/유저장전방/유저장내정/NPC후방/NPC구출/NPC전방/NPC내정) | 391,476,529,641,746,808,865,937,1051,1085,1143,1211 |
| `che_포상` | 유저장긴급포상/유저장포상/NPC긴급포상/NPC포상 (4) | 1298,1406,1499,1621 |
| `che_몰수` | doNPC몰수 (1) | 1751 |
| `che_불가침제의` | do불가침제의 | 1832 |
| `che_선전포고` | do선전포고 | 1965 |
| `che_천도` | do천도 (×2 paths) | 1987,2103 |

### GENERAL-emitted (`buildGeneralCommandClass`) — 30 distinct codes
che_주민선정, che_정착장려, che_수비강화, che_성벽보수, che_치안강화, che_기술연구, che_농지개간, che_상업투자, che_모병, che_징병, che_군량매매, che_훈련, che_사기진작, che_소집해제, che_출병, che_헌납, che_NPC능동, che_귀환, che_집합, che_인재탐색, che_이동, che_거병, che_해산, che_건국, che_선양, che_임관, che_랜덤임관, che_물자조달, che_견문, che_요양 (the chooseGeneralTurn step-6 injury>cure direct build, no do<한글>, gate-exempt G14).

**Total distinct emitted = 35** (5 nation + 30 general). [research §5 said "~33"; the precise count is 35.]

---

## (2) Per-code Kotlin pack status (M1)

> **HEADLINE for the executor:** the live `CommandRegistry.resolve` `when` (verified at HEAD `b6528df`, `CommandRegistry.kt:58-105`) maps **28** of the 35 emitted codes. **7 of the 9 task-named MISSING codes are confirmed absent from the `when`.** Two more — **che_요양 and che_몰수** — are ALSO absent from the live `when` (see UNCERTAIN U1). The task FR0 brief + FR2 scope name exactly **9 MISSING** (불가침제의/선전포고/NPC능동/귀환/인재탐색/견문/해산/요양/선양) and label che_몰수 "EXISTS-GREEN"; the LIVE registry contradicts the che_몰수 label. **This note records the live ground-truth AND keeps the FR2 9-name scope; FR2 must decide che_몰수 (see U1) before the gate, or the doNPC몰수 emit silently falls to RestAction.**

### EXISTS-GREEN — registered in `CommandRegistry.resolve`, def present (28 confirmed by-key from `CommandRegistry.kt:58-105`)
che_상업투자, che_농지개간, che_성벽보수, che_수비강화, che_치안강화, che_기술연구, che_정착장려, che_주민선정, che_물자조달, che_군량매매, che_징병, che_모병, che_훈련, che_사기진작, che_소집해제, che_이동, che_집합, che_임관, che_랜덤임관, che_발령, che_포상, che_천도, che_거병, che_건국, che_출병 (DIVERGENT-PACK — see below), che_헌납.
(Plus non-AI-emitted extras in the `when` not on the 35-list: che_감축, che_증축, che_국호변경, che_국기변경, che_무작위건국, che_무작위수도이전, che_장수대상임관, che_하야, che_방랑, che_은퇴, che_등용, che_증여, che_장비매매, cr_맹훈련, cr_건국.)

That is **26 of the 35** emitted codes EXISTS-GREEN and NON-divergent (che_출병 is the 27th-listed but DIVERGENT). Counting che_출병 as "present-but-divergent", **27 of 35 have a registered def**; subtract che_출병 → **26 clean-green**.

### MISSING-DEF (emitted by the AI but NOT in `CommandRegistry.resolve` — no Kotlin def): the 9 FR2-scoped codes
1. `che_불가침제의` (do불가침제의 :1832) — P6 diplomacy class; P5 needs the GATE pack (G5). **MISSING.**
2. `che_선전포고` (do선전포고 :1965) — P6 diplomacy. **MISSING.**
3. `che_NPC능동` (do후방/전방/내정워프 :2958/3009/3083) — the warp/순간이동 command; EMPTY fullCond + MustBeNPC → gate ≈ argValid. **MISSING.**
4. `che_귀환` (do귀환 :3103). **MISSING.**
5. `che_인재탐색` (방랑군이동/국가선택/사망대비/중립, 4 sites :3185/3412/3440/3448). **MISSING.**
6. `che_견문` (사망대비/중립, 3 sites :3414/3442/3462). **MISSING.**
7. `che_해산` (do해산 :3292). **MISSING.**
8. `che_요양` (chooseGeneralTurn step-6 :3773, gate-exempt G14 — def still needed for EXECUTION). **MISSING (confirmed absent from `when`; the task brief co-lists it).**
9. `che_선양` (do선양 :3323; uses `ORDER BY RAND()` → G4 quarantine; BeLord gate). **MISSING.**

All 9 PHP source classes confirmed present on disk: `Command/Nation/{che_불가침제의,che_선전포고}.php`, `Command/General/{che_NPC능동,che_귀환,che_인재탐색,che_견문,che_해산,che_요양,che_선양}.php`.

### DIVERGENT-PACK (def exists but FULL `fullConditionConstraints` diverges from PHP): 1 — `che_출병`
- LIVE `CheChulbyeong.buildConstraints` (`logic/.../actions/war/CheChulbyeong.kt:74-76`) ships ONLY `[notBeNeutral(), occupiedCity(), reqGeneralCrew()]` (3 constraints).
- PHP `che_출병.php:78-87` FULL pack (8, in order): `NotOpeningPart(relYear)`, `NotSameDestCity`, `NotBeNeutral`, `OccupiedCity`, `ReqGeneralCrew`, `ReqGeneralRice(reqRice)`, `AllowWar`, `HasRouteWithEnemy`.
- **Kotlin is MISSING 5:** `NotOpeningPart`, `NotSameDestCity`, `ReqGeneralRice`, `AllowWar`, `HasRouteWithEnemy`. (The inline comment at `CheChulbyeong.kt:94` even names HasRouteWithEnemy as "should be precluded" but it is NOT wired.)
- **G5/M8 PARITY-FATAL:** a missing FULL-pack member flips the AI gate boolean → the AI emits/skips che_출병 wrongly → wrong priority-loop branch → desync. P4 intentionally TRIMMED the pack for the BO3 battle gate; the P5 AI bridge needs the FULL PHP pack. **FR1 repairs this** — add the 5 absent in PHP order, and EITHER confirm the P4 battle resolve still green with the additions OR scope the FULL pack to the AI-bridge ctx only if P4 resolve depends on the trimmed set.

### NET COUNT (FR0 brief's contract, reconciled to live)
- FR0 brief / FR2 commit message: **29 EXISTS-GREEN (incl che_몰수), 9 MISSING-DEF, 1 DIVERGENT (che_출병).**
- LIVE registry at HEAD: che_몰수 is NOT in the `when` (and no `CheMolsu`/`Molsu`/`che_몰수` def exists anywhere in `logic/` — grep clean). So the live truth is **27 EXISTS (che_출병 counted, divergent), 8 MISSING + che_몰수 = effectively 8-or-9 MISSING depending on che_몰수's resolution, 1 DIVERGENT.** See U1.

---

## (3) The sub-package layout decision (m11) — KEEP the existing sub-package layout (do NOT scaffold; do NOT flatten)

The 4 dirs the plan said to "scaffold OR flatten" **ALREADY EXIST** at HEAD, alongside `nation/`, `trade/`, `war/`:
```
logic/src/main/kotlin/opensamguk/logic/actions/
  (root)      CommandRegistry.kt, GeneralActionDefinition.kt, GeneralActionResolveContext.kt, TermStack.kt,
              CheNongjigaegan.kt, CommerceInvestment.kt    ← a few develop cmds still live flat at root
  develop/    CheGisulYeongu, CheGunryangMaemae, CheJeongchakJangnyeo, CheJuminSeonjeong, CheMuljaJodal
  founding/   CheGeobyeong, CheGeonguk, CheMujakwiGeonguk, CrGeonguk, FoundingCascade
  military/   CheHullyeon, CheIdong, CheJiphap, CheSagiJinjak, CheSojipHaeje, CrMaenghullyeon, MilitaryHelpers, RecruitAlgorithm, UnitSetTable
  nation/     CheBallyeong, CheCheondo, CheGamchuk, CheGukgiByeongyeong, CheGukhoByeongyeong, CheJeungchuk, CheMujakwiSudoIjeon, ChePosang, NationCommand
  personnel/  CheBangrang, CheDeungyong, CheEuntwe, CheHaya, CheImgwan, CheRandomImgwan
  trade/      CheHeonnap, CheJangbiMaemae, CheJeungyeo
  war/        CheChulbyeong
```

**DECISION (pinned for FR2 consistency): use the EXISTING sub-package layout.** The codebase already adopted the sub-package convention in P2–P4 (the "flat P2–P4 layout" the plan offers as an alternative is only partially present — two develop cmds at root); converging on sub-packages is the lower-churn, already-established choice. The FR2 def-file placements in the plan's File-Structure block already target these exact existing dirs:

| FR2 def | dir (EXISTS) | PHP source |
|---|---|---|
| `CheBulgachimJeui` (che_불가침제의) | `actions/nation/` | `Command/Nation/che_불가침제의.php` |
| `CheSeonjeonpogo` (che_선전포고) | `actions/nation/` | `Command/Nation/che_선전포고.php` |
| `CheNpcNeungdong` (che_NPC능동) | `actions/military/` | `Command/General/che_NPC능동.php` |
| `CheGwihwan` (che_귀환) | `actions/military/` | `Command/General/che_귀환.php` |
| `CheInjaeTamsaek` (che_인재탐색) | `actions/personnel/` | `Command/General/che_인재탐색.php` |
| `CheHaesan` (che_해산) | `actions/founding/` | `Command/General/che_해산.php` |
| `CheSeonyang` (che_선양) | `actions/founding/` | `Command/General/che_선양.php` |
| `CheGyeonmun` (che_견문) | `actions/develop/` | `Command/General/che_견문.php` |
| `CheYoyang` (che_요양) | `actions/personnel/` | `Command/General/che_요양.php` |

No new dirs need creation (all 4 + nation/trade/war exist). FR2 places the 9 files into the table's dirs; FR2 registers all 9 into `CommandRegistry.resolve` (F-BRIDGE owns the registry widening; no leaf family re-touches it).

---

## (4) CheBallyeong `destGenaralID` typo verdict (M9, R-BRIDGE §4) — port the latent always-null bug VERBATIM; the fix is the argTest gate (FR3), not the typo

- **AI emits the typo:** `do부대전방발령` (`GeneralAI.php:384`) emits `['destGenaralID' => $leaderID, 'destCityID' => $targetCityID]` — misspelled `destGenaralID` (extra `a`). It is the **ONLY** call site with the typo; the other 11 발령 methods emit the correct `destGeneralID` (e.g. `:476-478`, `:529-531`).
- **PHP rejects it:** `che_발령.argTest()` (`che_발령.php:38`) does `if (!key_exists('destGeneralID', $this->arg)) return false;`. Under the typo, `destGeneralID` is absent → `argTest()` → false → `testFullConditionMet()` denies on `isArgValid` → `hasFullConditionMet()` false → **`do부대전방발령` ALWAYS returns null** and falls through to the next priority. **This is a PHP latent bug: 부대전방발령 can NEVER successfully fire.** Port verbatim — fixing the typo would make 부대전방발령 emit, shifting every downstream draw (and 부대전방발령's pre-null `choice($nextCityCandidate)` BFS draws STILL happen before the null — R-BRIDGE §1/§4, catalog §5.A).
- **Live Kotlin status — NO argTest gate (verified):** `CheBallyeong.parseArgs` (`CheBallyeong.kt:58-61`) reads `raw["destGeneralID"]` (→ null under the typo) and `argsSchema = {destGeneralID:int, destCityID:int}`; but `GeneralActionDefinition`/`NationCommand` have **NO `isArgValid`/argTest gate** — the PHP FALSE-on-missing-key reject is unimplemented. Kotlin currently silently null-coerces (`destGeneralID==null`), so the typo's always-null behavior is NOT reproduced AND the downstream-desync protection is lost.
- **VERDICT (pinned):** Kotlin NEITHER requires NOR cleanly rejects the typo. **FR3's `candidateAllowed` MUST front the bridge with an `isArgValid`/argTest gate that runs FIRST (BaseCommand.php:377 order: isArgValid → testAll → testPostReqTurn) and returns Deny `'인자가 올바르지 않습니다.'` on a missing required key.** Under `do부대전방발령`'s typo, that gate returns false → `candidateAllowed` false → `do부대전방발령` null-returns — reproducing the PHP latent bug. **L-DEPLOY ports the typo emit verbatim; F-BRIDGE/FR3 provides the gate that makes it always-null.** Do NOT "fix" the typo; do NOT add a check that special-cases 부대전방발령.

---

## UNCERTAINs (must be resolved before the gate, NOT silently)

- **U1 (LOAD-BEARING — che_몰수 is ACTUALLY MISSING, contradicting the FR0 brief + R-BRIDGE §2 label):** R-BRIDGE §2 line 62/64 and the FR0 task brief both label `che_몰수` "EXISTS-GREEN", but the LIVE `CommandRegistry.resolve` `when` (`CommandRegistry.kt:58-105`) has **NO `"che_몰수"` entry**, and a full-tree grep (`che_몰수|CheMolsu|Molsu|Confiscat`) finds **no def file / class / function** anywhere in `logic/`. `ChePosang.kt` is che_포상 ONLY (no 몰수 sibling). doNPC몰수 (`GeneralAI.php:1751`) emits `che_몰수`, which today resolves to `RestAction` (the `else` fallback) — a SILENT HOLE. R-NATIONPOL §1 confirms `NPC몰수` IS the 20th live entry of the nation `$defaultPriority`, so doNPC몰수 IS reachable in the dispatch. **Per parity law (never fabricate, never weaken): che_몰수 must get a real def from `legacy/.../Command/Nation/che_몰수.php` (confirmed present on disk) OR be explicitly quarantined with proof if unreachable in the 1010 gate (R-GATE census needed).** I have kept FR2's task-dictated 9-name MISSING scope intact in §2 (because FR2's steps + commit message enumerate exactly those 9), but FR2/F-BRIDGE owner must EITHER (a) add che_몰수 as a 10th def, OR (b) confirm via the R-GATE npc-census that doNPC몰수 is unreachable in scenario_1010 and quarantine it. Do NOT let doNPC몰수 silently fall to RestAction on the gate. The FR0 brief's "29 EXISTS incl che_몰수 / 9 MISSING" arithmetic only holds if a che_몰수 def is added (then 28-in-`when` + che_몰수 = 29 of the 35; minus 1 divergent che_출병 → the "29 green" framing). Flagging, not hacking.

- **U2 (che_요양 double-listed):** che_요양 is in the FR2 9-name MISSING list (correct — it IS absent from the `when`) AND is gate-exempt (G14: built+returned directly at chooseGeneralTurn step-6, BYPASSES candidateAllowed). FR2 still builds the def (needed for EXECUTION resolution), but FR3's gate-exempt list must include do요양 so the bridge does NOT gate it. No conflict — just noting both facts are true simultaneously.

- **U3 (P4 che_출병 pack interaction):** FR1's che_출병 FULL-pack repair adds 5 constraints to `CheChulbyeong.buildConstraints`. If the P4 BO3 battle resolve depends on the TRIMMED 3-constraint pack (e.g. a battle test that exercises a path the new NotOpeningPart/AllowWar/HasRouteWithEnemy would now deny), the additions could regress the P4 gate. FR1 must run the P4 battle tests after the repair and, if red, scope the FULL pack to the AI-bridge ctx (mode/flag) rather than the shared resolve path. (Not FR0's job to resolve — flagged for FR1.)

- **U4 (deny-log string, deferred to FR3/G-GATE):** R-BRIDGE notes the NATION reserved-fail path logs `getFailString()` (reason-first, trailing period). FR3 must re-derive the exact deny-log byte-string from the golden, NOT trust the current Kotlin `denyLog`. Out of FR0 scope; noted so FR0's mapping is not mistaken for the log spec.
