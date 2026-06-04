---
name: parity-close
description: Close ONE parity gap (a single reservable command/action) end-to-end — golden capture, logic port + GoldenTest, draw-for-draw gate, BE intake + flush + IT, FE submit, adversarial review, one commit. Invoke as `/parity-close <command-code>` (e.g. `/parity-close 출병`, `/parity-close che_농업`). Use when adding or fixing one command's full mutation path against the PHP grand truth.
---

# parity-close — close one parity gap, golden-gated

A **process skill**. It takes ONE `<command-code>` and drives it from PHP grand truth to a live, gated, FE-wired mutation in opensamguk. One command per invocation — never batch. Each step delegates to a dedicated agent; this skill owns the **ordering** and the **safety gates** between them.

The denominator this skill chips away at: **93 turn-reservable command classes** = 55 General (`legacy/devsam-core/hwe/sammo/Command/General/`) + 38 Nation/Chief (`legacy/devsam-core/hwe/sammo/Command/Nation/`), whitelisted in `GameConstBase.php` `$availableGeneralCommand` (~line 316) + `$availableChiefCommand` (~line 378). Plus ~40 non-Command mutations (Betting, Auction, Vote, Message, Troop, InheritAction, Nation Set* settings, misc General actions). PHP (`legacy/devsam-core`, git-ignored) **wins every divergence**; `devsam-core2026` (TS) is structural-only.

## The ONE rule that makes a command actually fire

A code **absent from `intakeCodes`** (in `app/game-api/.../reserve/CommandWireMapper.kt`) precheck-passes as AVAILABLE but the **engine silently denies it at execution** — a no-op the UI never sees. So "ported + green GoldenTest" is NOT done. The full seam must close:

```
POST /api/command/{code}
  → CommandReserveService.reserve            (app/game-api)
  → CommandWireMapper (intakeCodes Set + toCommand)   ← code MUST be here
  → common/wire/TurnDaemonCommand.kt          (wire variant)
  → engine TurnDaemonCommandDispatcher
  → handler OR ReservedTurnHandler            (threads actorId/cityId/nationId + destGeneralId/destCityId/destNationId into ConstraintContext)
  → ChangeRecorder channels (created/dirty/deleted)
  → JdbcFlushExecutor flush step              (JDBC batch, never inline)
FE: web/game/app/game/<page>/page.tsx form → Next route handler → game-api intake
    (arg-bearing commands use web/game CommandModal, not page nav)
```

## SAFETY GATES (non-negotiable — read before every step)

1. **Never weaken a test. Never edit a golden.** On a mismatch, fix the **Kotlin impl**, not the fixture. Touching `logic/src/test/resources/golden/**` to make red go green is forbidden.
2. **RNG-bearing commands are the highest-care path and MUST be golden-gated before commit.** If the command draws randomness (`RandUtil`/`LiteHashDrbg`), it is **not committable** until a `*GoldenTest`/`*ReplayGateTest` replays the real PHP draw stream **draw-for-draw green**. Draw **order + count + method-args** are the parity targets, not just the result.
3. **Never fabricate a golden.** Numbers/logs/seeds come ONLY from a real PHP capture (`tools/php-golden/`, Docker). If a value genuinely can't be captured faithfully → **quarantine WITH PROOF** (sibling-code-path byte-match) + log to the phase backlog. Inventing a value = breaking the repo's grand-truth contract.
4. **Verify by TEST XML, not exit code.** The host routes gradle through a context-mode wrapper; `task-notification` exit 0 is unreliable. Read `logic/build/test-results/test/*.xml` + grep `BUILD SUCCESSFUL` from the tail. Use `--rerun-tasks` to defeat UP-TO-DATE false-greens. Java 21: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew ...` from repo root.
5. **One logical commit** at the very end, message ending with the trailer:
   ```
   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```
6. **Daemon writes go only through ChangeRecorder → JdbcFlushExecutor** (architecture-test-enforced). No JPA `EntityManager` writes in game-engine. `CommandReserveService.reserve` (general_turn JDBC + Redis poke) is **sanctioned intake**, not a forbidden write.

## Ordered steps

Run sequentially. Each step delegates to its agent; do not skip ahead. Use code-review-graph MCP (`semantic_search_nodes`, `query_graph`) before Grep to locate the PHP class, the Kotlin handler, and existing siblings.

### 1. Capture golden — agent: `golden-capturer`
- Read the PHP class first (`hwe/sammo/Command/General/<Code>.php` or `Command/Nation/<Code>.php`) to determine **does it draw RNG?** and **what does it mutate?**
- **RNG-free + deterministic** (e.g. pure stat/gold deltas, no `rand`): a golden fixture may be unnecessary. **You MUST state explicitly why** (quote the PHP showing no `RandUtil`/`rand`/`pickOne` call) and let the GoldenTest assert log/delta byte-parity against PHP-derived expected strings instead. Do not silently skip.
- **RNG-bearing**: capture is **mandatory**. Run the Docker harness (`tools/php-golden/`, MariaDB 11.4 + `php:8.3-cli`, scenario_1010). Use a `RandUtilDrawRecorder.php` override to record the draw stream + final mutations + log strings. Honor the quirks: `_boot.php` binds `DB::db()`, `j_install.php` called twice, install not idempotent (fresh DB), dumps byte-identical across two runs. Throwaway probes are `tools/php-golden/probe_*.php` (never committed).
- Output: fixture JSON committed under `logic/src/test/resources/golden/<area>/`. **Commit the fixture in this step** (separate from the impl commit is fine; the fixture is the oracle).

### 2. Port logic + write GoldenTest — agent: `parity-porter`
- Port the PHP behavior into `logic/` (`actions/*`, register in `CommandRegistry`; war paths via `war/*`). Korean code comments; identifiers + log-parity strings stay English/Korean-as-in-PHP.
- Honor: `PhpRound` half-away-from-zero (`phpRound(v,-2)`, NEVER `phpRound(v/100)*100`, NEVER `Math.round`/`kotlin.math.round`); `Util::toInt`/`intdiv` = truncate-toward-zero; damage clamp = `ceil()`. `Josa` 조사 + color/tag markup for logs. Insertion order preserved (`LinkedHashMap`), PHP 8.0+ stable sorts (no non-stable secondary comparator).
- Write the `*GoldenTest`/`*ReplayGateTest` that loads the step-1 fixture and asserts draw-for-draw (RNG) or log/delta byte-parity (RNG-free). Build the `RandUtil(LiteHashDrbg(SeedSerializer.serialize(...)))` exactly as PHP seeds it; for battle, ONE `RandUtil(warSeed)` threaded by reference, never re-seeded.

### 3. Gate loop — agent: `parity-gate-runner`
- Run the gate: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --rerun-tasks --tests '*<Code>*' 2>&1 | tail -40`, then read `logic/build/test-results/test/*.xml`.
- On RED: hand the diff (expected vs actual draw / log / delta) **back to `parity-porter`** (step 2). **Loop porter ↔ runner** until draw-for-draw GREEN. Fix the Kotlin, never the golden, never the test.
- If after honest investigation a value is **genuinely uncapturable** (e.g. unreachable in scenario_1010, or a path with zero live PHP callers): **quarantine WITH PROOF** — document the sibling-code-path byte-match that establishes correctness, mark the test, and log to the phase backlog. This is the ONLY sanctioned exit other than green. **RNG-bearing commands cannot pass this step un-gated** (gate-rule 2).

### 4. Wire BE intake + flush + IT — agent: `intake-wirer`
- Add the code to `intakeCodes` + `toCommand` mapping in `app/game-api/.../reserve/CommandWireMapper.kt` (else: silent engine deny).
- Add/extend the wire variant in `common/wire/TurnDaemonCommand.kt`; route it in the engine `TurnDaemonCommandDispatcher` to the handler (or `ReservedTurnHandler`, threading actorId/cityId/nationId + destGeneralId/destCityId/destNationId into `ConstraintContext`).
- Wire the mutation onto `ChangeRecorder` channels and the matching `JdbcFlushExecutor` flush step (created/dirty/deleted tombstone — never an inline write).
- Add an intake IT proving the round-trip reserves and flushes. Testcontainers on macOS: `api.version=1.44` + `DOCKER_CONTEXT=default` + Ryuk disabled (wired in `tasks.test`); Docker-unavailable ⇒ IT **skipped**, not failed.

### 5. Wire FE submit — agent: `fe-submit-wirer`
- In `web/game/app/game/<page>/page.tsx`, turn the read render into a submit: form → Next route handler → game-api intake. **Arg-bearing** commands open `web/game` `CommandModal` (not a page nav). Tokens stay in httpOnly cookies via the route handler (no token in browser JS).
- Match the precheck/gating so the UI only offers the command when reservable.

### 6. Adversarial review — agent: `parity-reviewer`
- Adversarial pass over the whole change: RNG draw order/count/args, rounding mode, log byte-parity (Josa + markup + order = execution order), flush-delta-not-inline, intakeCodes presence, insertion order. Use code-review-graph `detect_changes` + `get_impact_radius` + `get_affected_flows`.
- Any **blocker** → fix it (or bounce to the owning agent) and re-run the relevant gate (step 3) / IT (step 4). Do not proceed with an open blocker.

### 7. Commit one logical unit
- Stage the impl + tests + intake wiring + FE wiring + (if not already) the fixture. **One logical commit.** Re-run `:logic:test` for the command's gate one final time and confirm GREEN via test XML.
- Commit message ends with the `Co-Authored-By: Claude Opus 4.8 (1M context)` trailer. Branch first if on a protected/default branch; commit/push only as the workflow directs.

## Done means
Draw-for-draw GREEN gate (or quarantine-with-proof + backlog entry) · code in `intakeCodes` · flush-delta wired · IT round-trips · FE submits · reviewer blockers cleared · one trailer-stamped commit. Anything short of this is an open gap, not a closed one.
