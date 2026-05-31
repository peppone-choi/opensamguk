# P5 Quarantine Register — the AI-decision parity quarantines (with proof obligations)

**GRAND TRUTH:** `legacy/devsam-core/hwe/sammo/GeneralAI.php` (PHP). TS core2026 is structural-only; PHP wins.
**Discipline:** CLAUDE.md parity rule #5 — a value that cannot be captured faithfully is QUARANTINED with proof
(sibling-code-path byte-match), NEVER fabricated, and NEVER worked around by weakening a test or editing a golden.

Every entry below is a deliberate, documented divergence that is provably **out of the P5 gate** (or
draw-neutral). Each carries: the PHP source site, why it cannot be replayed draw-for-draw, the faithful
substitute, the proof obligation, and the re-open condition.

---

## Q1 — `ORDER BY RAND()` row-pick → deterministic `min(no)` / `min(nation)` substitute (FQ1, G4, R-GATE §1)

**Sites (the ONLY two non-DRBG random picks in the entire AI):**

| PHP site | method | SQL | substitute |
|---|---|---|---|
| `GeneralAI.php:3324` | `do선양` (→ `che_선양`) | `SELECT \`no\` FROM general WHERE nation = %i AND npc != 5 ORDER BY RAND() LIMIT 1` | `min(no)` over own-nation, `npc != 5` |
| `GeneralAI.php:3345` | `do국가선택`-오랑캐 (→ `che_임관`) | `SELECT nation FROM general WHERE \`officer_level\`=12 AND npc=9 and nation ORDER BY RAND() limit 1` | the `nation` of the `min(no)` row over `officer_level==12 && npc==9 && nation!=0` |

**Why it cannot be replayed draw-for-draw.** Both are **MySQL/MariaDB-side `RAND()`**, NOT `RandUtil(LiteHashDrbg)`.
They draw **ZERO bytes** off the DRBG stream → the AI's per-general rng cursor (`stateIdx`/`bufferIdx`) is
**UNAFFECTED** (no downstream desync). The only non-determinism is *which row id is chosen among ties*; PHP itself
is non-deterministic per run, so a captured id is not reproducible and would fail the "byte-identical across two
runs" install rule.

**Faithful substitute (Kotlin).** `logic/ai/families/GenFoundFamily.kt`:
- `seonyangDestGeneralId(nationId, candidates, rng)` = `candidates.filter { nationId==X && npcType!=5 }.minByOrNull { it.id }?.id`.
- `orankaeRulerNation(candidates, rng)` = `candidates.filter { officerLevel==12 && npcType==9 && nationId!=0 }.minByOrNull { it.id }?.nationId`.
- Both take the `RandUtil` only to document the contract at the call site; **neither consumes a single draw** —
  the DRBG cursor is a parity target a reviewer must not "fix" by inserting a draw. Marked `@ParityQuarantine("G4-order-by-rand")`.
- Candidate-set iteration = general.no insertion order (G13); the pick is `min(no)` → order-independent.
- Empty WHERE set → `null` (mirrors PHP `queryFirstField` → null; no fabrication).

**Gate reachability — ZERO at gate start (R-GATE §1, G4 §3, CERTAIN):** scenario 1010 has **0 npc==5 and 0 npc==9**
generals of **678** (install assigns only npc 2/6 via `Scenario.php`). `can선양` requires npc==5
(`AutorunGeneralPolicy.php:98-100`); 오랑캐임관 requires npc==9 (`GeneralAI.php:3343`); the 2 `officer_level==12`
rulers (하진/장각) are both npc==2. ⇒ NEITHER `ORDER BY RAND()` site fires in the P5 gate — tail paths, not gate paths.

**Proof obligations:**
1. **0-draw / cursor-unchanged** — `logic/.../ai/QuarantineSubstituteTest.kt`: a recording RandUtil over a real
   `LiteHashDrbg` asserts the substitute consumes ZERO draws AND the DRBG `stateIdx`/`bufferIdx` is byte-identical
   before/after the pick (GREEN). It also asserts the deterministic `min(no)`/`min(nation)` selection.
2. **Census proof (G-GATE / GT1, deferred to the gate task):** emit `SELECT npc, COUNT(*) FROM general GROUP BY npc`
   against the installed scenario-1010 fixture — EXPECT only `(npc=2, cnt=678)`, abort if any npc==5 or npc==9 row
   exists. As long as the census holds, the quarantine is valid and both paths stay off the gate.
3. **Crafted-fixture sibling-byte-match (G4 §4, deferred to the gate task):** on a crafted npc==5 officer_level==12
   ruler fixture, the substitute picks a VALID member of the WHERE set; only the **non-id** bytes are byte-matched
   (the emitted `che_선양`/`che_임관` code, the reason `do선양`/`do국가선택`, `hasFullConditionMet`, and the
   rng-stream-position unchanged); the chosen id is asserted **"valid member only"**, never byte-equal to PHP's
   random pick. The unit test (proof 1) already exercises the substitute on a crafted npc==5 fixture.

**DO NOT** fabricate an id, weaken a test, or seed npc 5/9 into scenario 1010 to force coverage.

**Re-open condition:** a future scenario seeds 오랑캐(npc==9), OR a death cycle promotes an `officer_level==12`
ruler to npc==5 inside the gate window. (See G4 §5 backlog entry, logged below.)

---

## Q2 — `chooseInstantNationTurn` no-call-site (decision #3, B3, R-SEAM §3)

**Site:** `GeneralAI.php` `chooseInstantNationTurn` (its own TODO at `:3620` confirms it is an unwired stub).

**Why it is quarantined.** A repo-wide grep finds **ZERO live callers** of `chooseInstantNationTurn` in
`legacy/devsam-core`. The live turn loop (`TurnExecutionHelper.php:306`) calls **only** `chooseNationTurn` for
officer_level≥5 generals. No PHP golden can exercise the instant path → it is **NOT replayable on the gate**.

**Port disposition.** P5 wires ONLY the two LIVE dispatcher spines: `chooseGeneralTurn` + `chooseNationTurn`. A
structural-only port of `chooseInstantNationTurn` MAY be written (kept deliberately divergent from
`chooseNationTurn` — gate-first, no reason strings, 2-guard loop, `reservedCommand` by-ref), but it is marked
`@ParityQuarantine("R-SEAM-no-call-site")`, EXCLUDED from G-GATE, and **NOT wired into the live turn loop**
(`TurnDaemonLifecycle` never invokes it — asserted by `NationPassOrderTest`, FM2). The two LIVE loops are
DELIBERATELY DIVERGENT and must NOT be collapsed.

**Proof obligation.** Sibling byte-match against the LIVE `chooseNationTurn`: the shared sub-structure
(`updateInstance` → categorize → priority loop over `nationPolicy->priority`) is byte-identical to the gated
`chooseNationTurn`; the instant-specific divergences (no reason strings, gate-first, 2-guard) are documented and
not asserted on any golden. `NationPassOrderTest` (FM2) asserts the lifecycle never invokes it.

**Re-open condition:** a future PHP revision wires `chooseInstantNationTurn` into the live turn loop.

---

## Phase-backlog entries (verbatim — log to the P5 backlog / GAPS.md close-out)

```
[QUARANTINE] G4 ORDER BY RAND() — do선양 (GeneralAI.php:3324) + 오랑캐임관 (GeneralAI.php:3345)
- Two MySQL RAND() row-picks; NOT on the LiteHashDrbg stream → un-replayable draw-for-draw.
- DRBG stream position UNAFFECTED (0 draws consumed); only the chosen id is non-deterministic.
- Kotlin port: deterministic substitute = min(no) over the same WHERE-filtered candidate set
  (do선양: own-nation, npc!=5 → min(no); 오랑캐임관: officer_level==12 & npc==9 & nation!=0 → nation of min(no)).
  No RandUtil draw — GenFoundFamily.seonyangDestGeneralId / orankaeRulerNation.
- Gate reachability: scenario 1010 has 0 npc==5 and 0 npc==9 generals of 678 (install assigns only npc 2/6);
  can선양=true requires npc==5 (AutorunGeneralPolicy.php:98-100); 오랑캐 branch requires npc==9 (GeneralAI.php:3343).
  ⇒ NEITHER path fires in the P5 gate — tail paths, not gate paths.
- Proof: (1) 0-draw + cursor-unchanged unit test (QuarantineSubstituteTest, GREEN); (2) GT1 census
  SELECT npc,COUNT(*) (abort if non-zero, deferred to G-GATE); (3) crafted-fixture sibling-byte-match
  pins non-id bytes (che_선양/che_임관 code, reason, hasFullConditionMet, rng-stream-position unchanged);
  chosen id asserted "valid WHERE member only".
- DO NOT fabricate an id, weaken a test, or seed npc 5/9 into 1010. Re-open if a future scenario seeds
  오랑캐(npc9) or a death-cycle promotes an officer_level==12 ruler to npc==5 within the gate window.

[QUARANTINE] chooseInstantNationTurn no-call-site (GeneralAI.php, TODO :3620)
- ZERO live callers in legacy/devsam-core; live loop (TurnExecutionHelper.php:306) calls only chooseNationTurn.
- Not replayable on any PHP golden → EXCLUDED from G-GATE, NOT wired into TurnDaemonLifecycle (FM2 asserts).
- @ParityQuarantine("R-SEAM-no-call-site"); structural-only port allowed but kept divergent from chooseNationTurn
  (gate-first, no reason strings, 2-guard, reservedCommand by-ref). Proof = sibling byte-match vs chooseNationTurn.
- Re-open if a future PHP revision wires chooseInstantNationTurn into the live turn loop.
```

---

## Cross-refs
- G4-rand-quarantine.md (the full strategy + the §5 backlog entry this register links).
- R-GATE.md §1 (the npc 5/9 census proof: 0/0 of 678) + §3 (L-GENFOUND under-coverage).
- R-SEAM.md §3 (the chooseInstantNationTurn no-call-site finding).
- Plan `docs/superpowers/plans/2026-05-30-p5-npc-ai.md` decisions #3 (instant quarantine) + #6 (ORDER BY RAND).
- Impl: `logic/ai/families/GenFoundFamily.kt` (the substitute helpers); test `logic/.../ai/QuarantineSubstituteTest.kt`.
