# H-SEAM — the real ReservedTurnHandler/lifecycle seam, pinned for F-SEAM (Task FM0)

The 1-page seam pin for AREA F-SEAM (plan §"AREA F-SEAM", FM0). Verified against
the REAL current Kotlin signatures and the PHP grand truth. This freezes the seam
contract BEFORE FM1/FM2 touch any code. Closes B2 (the handle()-sig + widen
contract), confirms B3 (no nation-resolve path), and the m7 typing note.

> **PARITY LAW:** PHP `TurnExecutionHelper.php` is GRAND TRUTH; core2026 is
> structural-only; PHP wins all divergences. On a mismatch fix the Kotlin impl,
> never weaken a test or edit a golden. This is a pin note — NO code, NO gradle.

This note is a thin operational digest of `R-SEAM.md` (the full research pin), with
every fact re-verified line-by-line against the live source. Where the two agree it
is a re-confirmation; nothing here supersedes `R-SEAM.md`.

---

## 1. The REAL current Kotlin seam signatures (verified line-by-line)

### `ReservedTurnHandler.handle()` — `app/game-engine/.../turn/ReservedTurnHandler.kt:93`

```kotlin
fun handle(generalId: Int, actionCode: String, year: Int, month: Int, date: String): HandledTurn
```

- The action enters as a **FLAT `String actionCode`** (`:93`). No arg payload.
- Resolved via `registry.resolve(actionCode)` → `GeneralActionDefinition` (`:103`).
- The per-action RNG seed (`:136-138`) is the 6-component
  `serializeSeed(hiddenSeed, "generalCommand", year, month, generalId, definition.key)`
  — **component 2 = literal `"generalCommand"`, component 6 = `definition.key`**
  (the short command class name, e.g. `che_상업투자`/`che_농지개간`), **NEVER an arg**.
- Returns `HandledTurn(generalId, definition, fellBack, denyReason, logs, env)`.
- FULL-mode constraints run over `WorldStateViewAdapter` (`:114-115`) — the SAME
  `evaluateConstraints` precheck makes; on `!Allow` it 휴식-falls-back + pushes the
  deny-reason log (the backstop for an honored-but-illegal AI reservation, plan
  decision #4 — DO NOT remove or "normalize").
- Single dirty source: `ChangeRecorder` only (`recorder`, `:397`); writes go
  through `world.applyGeneralDirtyFree`/`applyCityDirtyFree` (`:156-157`). **No
  JPA EntityManager** (the ONE daemon-write rule).

### `TurnDaemonLifecycle.reservedActionOf` — `TurnDaemonLifecycle.kt:24` (used `:56`)

```kotlin
private val reservedActionOf: (generalId: Int) -> String   // returns ACTION CODE ONLY
...
actionCode = reservedActionOf(g.id),   // :56, inside runTick's due-general loop
```

Returns **ONLY the action code** — `argJson` is DROPPED at the seam.

### `run/TurnRunService.kt`

The Spring service that wires the daemon over `WorldStateViewAdapter`; it drives
`TurnDaemonLifecycle.runTick` (general pass only today). It does NOT itself resolve
nation commands — same gap as §3.

### The args gap (R-SEAM §1 contract decision — LOAD-BEARING, re-confirmed)

`ReservedTurnRepository.ReservedTurn` **does carry `argJson: String`** (read from
the `general_turn.arg`/`nation_turn.arg` jsonb, `ReservedTurnRepository.kt:39-43,
80-100,180-201`), but the seam discards it: `reservedActionOf: (Int)->String` and
`handle(actionCode: String)`. **`argJson` never reaches the resolver today.**

PHP DOES use args: the reserved nation command is built from BOTH columns
(`TurnExecutionHelper.php:264-272`: `$nationCommand = $rawNationTurn['action']`,
`$nationArg = Json::decode($rawNationTurn['arg'])`, then
`buildNationCommandClass(...$nationArg)`); `chooseGeneralTurn`/`chooseNationTurn`
return a fully-built `Command` object with its arg bound, seed keyed on
`$commandObj->getRawClassName()`.

**PINNED CONTRACT (FM1):** widen `reservedActionOf` from `(Int)->String` to carry
`(actionCode, argJson)` (e.g. `(Int)->ReservedTurn` or an `(actionCode,argJson)`
pair); thread `ReservedTurn(actionCode, argJson)` through `handle()` into the
resolver draft ctx. **RNG-seed parity is UNAFFECTED** — the seed uses
`definition.key` (the class name), never the arg → the widening is
behavior-additive on the resolver side ONLY. Foundation-level interface widening:
F-SEAM owns these two files; per the CLAUDE.md co-widening rule it is built
creator-then-consumer, never in parallel with a leaf that also touches them.

---

## 2. The pass-order: NATION command BEFORE GENERAL command (PHP grand truth)

`TurnExecutionHelper.php:299-348`, per-general, INSIDE one `if (!processBlocked())`
gate (`:299`):

```
processBlocked()  guard — block>=2 skips the WHOLE command block        [:299]
└─ if ($hasNationTurn):                                                  [:301]   ← NATION PASS FIRST
     hasNationTurn ⇐ nation != 0 && officer_level >= 5                   [:260]
     • non-휴식 reserved → hasReservedTurn=true                          [:302-304]
     • if ($ai && (use_auto_nation_turn ?? 1)):                          [:305]
         $nationCommandObj = $ai->chooseNationTurn($nationCommandObj)    [:306]
         LogText("NationTurn", ...)                                      [:307-308]
     • rng = RandUtil(LiteHashDRBG(simpleSerialize(
           hiddenSeed,'nationCommand',year,month,genId,
           nationCommandObj->getRawClassName())))                        [:310-317]
     • processNationCommand(rng, nationCommandObj)                       [:318-321]
     • nationStor->setValue("turn_last_{officer_level}", result->toRaw())[:322]
     • general->setRawCity(null)                                         [:323]
   $generalCommandObj = general->getReservedTurn(0, env)                 [:326]   ← GENERAL PASS SECOND
     • non-휴식 reserved → hasReservedTurn=true                          [:327-329]
     • if ($ai):                                                          [:331]
         $newCmd = ai->chooseGeneralTurn($generalCommandObj)             [:332]
         if ($generalCommandObj !== $newCmd) {autorunMode=true;cmd=$newCmd}[:333-336]
         LogText("turn", ...)                                            [:337-338]
     • rng = RandUtil(LiteHashDRBG(simpleSerialize(
           hiddenSeed,'generalCommand',year,month,genId,
           generalCommandObj->getRawClassName())))                       [:340-347]
     • processCommand(rng, generalCommandObj, autorunMode)               [:348]
```

**ORDER (load-bearing): NATION runs BEFORE GENERAL** within one general's turn,
both gated by ONE `processBlocked()` at the top.

**Each pass RE-SEEDS its OWN per-command RNG** — `'nationCommand'` then
`'generalCommand'`, distinct 2nd-component streams; NOT one shared draw stream,
and BOTH distinct from the AI-decision `'GeneralAI'` stream (the AI decision picks
the command on its `'GeneralAI'` stream; execution then re-seeds to resolve it).
`preprocessCommand` ran earlier on its own `'preprocess'` RNG (`:280-287`).

**The `'nationCommand'` RNG is constructed ONLY when `hasNationTurn`** — otherwise
the whole nation block AND its RNG construction are skipped (a parity-sensitive
conditional: do NOT build the `'nationCommand'` RandUtil unless `hasNationTurn`).

Ring rotation order AFTER the block: `pullNationCommand` then `pullGeneralCommand`
(`:350-351`), then `updateTurnTime()` + `applyDB()` (`:363-364`).

**The reserved-honor asymmetry (plan decision #4, A13 §5 #1):**
- GENERAL path honors a non-휴식 reserved command WITHOUT re-validating and WITHOUT
  a fail-log (the `chooseGeneralTurn` step-5 no-gate path); the execution
  `hasFullConditionMet` gate in `processCommand` (`:121`) is the backstop.
- NATION path DOES gate via `hasFullConditionMet()`; on deny it pushes a
  `getFailString()` fail-log (`:77-82`) THEN FALLS THROUGH to the dispatch loop.
- Port BOTH verbatim. Do NOT "normalize" the general path to add a check.

**Kotlin status:** `TurnDaemonLifecycle.runTick` (`:47-64`) runs ONLY the general
pass (`handler.handle`). `processBlocked` exists on the handler (`:212-228`) but is
NOT yet invoked in the lifecycle drain; the nation pass is entirely absent (§3).

---

## 3. The nation-command resolve path: DOES NOT EXIST in the daemon today (B3 GAP)

Confirmed against the live source:
- `ReservedTurnHandler` ports ONLY `processCommand` (general) as `handle()`. There
  is NO `processNationCommand` analogue — no `NationCommand` resolution, no
  `'nationCommand'` seed construction, no `turn_last_{officer_level}` KV write, no
  `setRawCity(null)`.
- `TurnDaemonLifecycle` reads only the general ring (`reservedActionOf`); it never
  reads `nation_turn` for execution. The repo HAS `readReservedNationTurn`
  (`ReservedTurnRepository.kt:180`) + `pullNationTurn` (`:213`) plumbing, but
  nothing in the daemon's run path calls `readReservedNationTurn` to RESOLVE a
  command — the engine's `nation_turn` references are flush/truncate/cascade only.
- game-api is read+precheck+intake only (CLAUDE.md) → not there either.

**PINNED IMPLICATION (FM2):** port `processNationCommand` FRESH
(`TurnExecutionHelper.php:72-109`), reusing the GREEN general `processCommand`
while-loop (`:111-167`) as the structural template — the two loops are
near-identical:

| step | processNationCommand :76-106 | processCommand :120-148 |
| --- | --- | --- |
| 1 hasFullConditionMet → fail-log + break | `:77-83` | `:121-127` |
| 2 addTermStack → term-log + break | `:85-91` | `:129-135` |
| 3 run(rng) → setNextAvailable + break | `:93-97` | `:137-141` |
| 4 getAlternativeCommand → null break / re-loop | `:100-105` | `:142-147` |
| return getResultTurn() | `:108` | (tail differs) |

Same `getFailString`/`getTermString` log strings. The DIFFERENCE: `processCommand`
has the post-loop `clearActivatedSkill` + the killturn-decrement branch
(`:151-165`); `processNationCommand` does NOT — it just `return $commandObj->
getResultTurn()` (`:108`). Both run through the SAME `ChangeRecorder`
single-dirty-source (the ONE daemon-write rule — JDBC delta only, NO
EntityManager). The nation result feeds `turn_last_{officer_level}` (a ChangeRecorder
KV delta) + `setRawCity(null)`.

---

## 4. `chooseInstantNationTurn` — NOT wired (R-SEAM §3, decision #3/B3)

ZERO live PHP call-sites (`GeneralAI.php:3685` def only; its own TODO at `:3620`
confirms it is unwired). The live loop calls ONLY `chooseNationTurn` (`:306`).
**F-SEAM wires ONLY `chooseGeneralTurn` (FM1) + `chooseNationTurn` (FM2).** A
structural stub MAY exist but is `@ParityQuarantine("R-SEAM-no-call-site")`,
EXCLUDED from G-GATE, and NOT referenced by the lifecycle (FM2 asserts no live
wiring). m7: the stub's `do$X(NationCommand $reservedCommand)` by-ref param needs a
Kotlin-typeable nullable/unused contract — moot for the gate, needed only to compile.

---

## 5-LINE SUMMARY (the FM0 deliverable)

1. **handle() sig** = `handle(generalId: Int, actionCode: String, year: Int,
   month: Int, date: String): HandledTurn` (`ReservedTurnHandler.kt:93`) — FLAT
   action-code String; seed keyed on `definition.key` (`:137`), never the arg;
   `argJson` exists on `ReservedTurn` but is DROPPED at `reservedActionOf:
   (Int)->String` (`TurnDaemonLifecycle.kt:24`).
2. **Widen contract (FM1):** `reservedActionOf → (Int)->ReservedTurn` carrying
   `(actionCode, argJson)`, threaded through `handle()`; RNG-seed parity unaffected
   (behavior-additive on the resolver side only).
3. **pass-order:** NATION command BEFORE GENERAL command per general, both under
   one `processBlocked()` gate, each RE-SEEDING its own `'nationCommand'`/
   `'generalCommand'` RNG (`TurnExecutionHelper.php:299-348`); `'nationCommand'`
   RNG built ONLY when `hasNationTurn` (nation!=0 && officer_level>=5).
4. **nation-resolve GAP:** does NOT exist in the Kotlin daemon — only general
   `processCommand` is ported; `processNationCommand` (`:72-109`) must be ported
   fresh (FM2), reusing the identical general resolve while-loop as template, same
   ChangeRecorder single-dirty-source, no killturn-decrement tail.
5. **instant quarantine:** `chooseInstantNationTurn` has ZERO call-sites → NOT
   wired into F-SEAM, OFF the G-GATE (decision #3/B3).
