# P0-B Parity-Kernel Plan — Adversarial Review Report

> Multi-agent review (35 agents, 25 findings → 21 confirmed after adversarial verification) run 2026-05-29 before execution.
> Dimensions: feasibility · coherence · scope · correctness · parity-risk. Plus a 17-item Open-Questions triage.

## Verdict: **go-with-edits**

Architecture sound (RNG-first ordering, dual-oracle TS-primary/PHP-second grading, golden-freeze gating, flush-order recorder, full wire union) — verified against the source oracles. No structural rework. Apply the surgical edits below, then execute. Intentional legacy divergences (officer ranks 0-9, city lv4/lv5, choiceUsingWeight numeric-first) correctly preserved — NOT flagged.

## P0 — must fix before ANY execution (2)

1. **RNG-dump not runnable as written** (Task 2; FEAS-1 / OQ4). `legacy/devsam-core2026` has NO `node_modules` and NO `dist` → `node --experimental-strip-types` fails (ERR_UNSUPPORTED_TYPESCRIPT_SYNTAX on parameter-properties, then ERR_MODULE_NOT_FOUND on .js→.ts; `@noble/hashes` unresolved). Fix: add explicit `pnpm install` prerequisite; switch primary runner to `npx tsx` (4.22.3 confirmed); keep `pnpm --filter @sammo-ts/common build` + `dist/index.js` as documented fallback only. Blocks all 31 Area-1 RNG tests → blocks every later phase.
2. **UnitConstraint.kt draft strings wrong + 5/10 types missing** (Task 16; Q11). Verified verbatim against `legacy/devsam-core/hwe/sammo/GameUnitConstraint/*.php`. Rename `info()`→`getInfo()`; fix all 5 present strings; add `ReqChief`, `ReqNotChief`, `ReqCitiesWithCityLevel`, `ReqHighLevelCities`, `ReqNationAux` (per-key switch ReqNationAux.php:62-119). `_generate()` appends `getInfo()` into every unit's `info[]` → cascades to golden failures across all 40 rows. PHP is sole oracle (TS emits boolean logic, no human strings).

## P1 — fix before the relevant area lands (6)

3. Build-prereq note (line 91): carve out wire area — Task 19 PROMOTES kotlinx-serialization-json test→implementation scope (never downgrade); literal verify-only rule would skip the promotion → wire main source won't compile. (COH-1)
4. Task 28 build edit: add the same "preserve Docker quirk block (api.version=1.44 + DOCKER_HOST + DOCKER_CONTEXT=default + RYUK_DISABLED), ADDITIVE not rewrite" instruction Task 22 carries — else RealtimeRelayIT redis Testcontainer won't start on this macOS host. (FEAS-2)
5. Tasks 22/28: replace literal `kotlinx-serialization-json:1.7.3` with `libs.kotlinx.serialization.json` alias (single source of version); add one-time "1.7.3 resolves from mavenCentral" check to Task 3 first-lander. (FEAS-3/COH-1)
6. Task 26: delete engine-local `StreamKeys.kt` + duplicate test — consume `:common` `TurnDaemonStreamKeys.of`/`gameEventChannel` directly (game-engine already deps `:common`). Two sources of truth for wire keys = drift hazard the sealed contract exists to prevent. (SCOPE-5/COH-3)
7. Task 20 Files header: add `WireEnvelope.kt` to the deliverable enumeration (currently only inline prose). (COH)
8. OQ9/Task 18: cite `ResetHelper.php:264-267` as confirmed killturn/develcost source (does NOT "fail to surface"): `killturn=4800/turnterm; if(npcmode==1) killturn=intdiv(killturn,3); develcost=(year-startyear+10)*2`. PHP-only port; develCost recomputes per-turn (func_gamerule.php:219) → P1 must recompute, not cache.

## P2 — nice-to-have (5)

9. Task 6: note Kotlin `buildTournamentSeedKey` ↔ TS exported `createTournamentSeedKey` (TournamentRNG.ts:36 wrapper → private buildTournamentSeedKey:15); byte-identical, dump imports exported name, Kotlin keeps the one name.
10. Task 9/OQ5: record LATIN-path josa divergence (PHP regex special-char tables vs TS last-char vowel check {a,e,i,o,u,y}); add romanized golden cases (Kim, Park, …).
11. Task 3: reword nextBits guard note — Kotlin reproduces the observable throw via own `bits<=0` guard; KEEP it.
12. Task 6: note `serializeSeed` is a reconstructed helper (no single TS export); `str(len,..)` uses `String.length`=UTF-16 code units (correct).
13. Task 2/4: add inclusive-max fixture rows proving a draw of exactly `max` is ACCEPTED (TS pins nextInt(0x99)→0x99); current test checks range only.

## Open-Questions resolutions (resolve-now)

- **Q2** (>54-bit caller): keep unsigned-64-as-signed-Long; add `require(bits <= 54)` at top of `_nextInt` so any future caller fails loudly.
- **Q4**: → folded into P0 #1 (npx tsx).
- **Q9**: → folded into P1 #8.
- **Q14**: approve kotlinx.serialization on `:common` (no competing serializer; Jackson is app-web-only); keep JsonContentPolymorphicSerializer (type,ok) — verified necessary.
- **Q15**: confirm Lettuce/Spring Data Redis + engine-publishes/api-relays-SSE as-is (matches turnDaemon.ts:297-311 + game-api realtime). Do NOT co-locate SSE on engine.

## Deferred to P1/P8

- Q3 PHP RNGTest byte-stream cross-check → P8 parity-harness (TS golden already encodes PHP/Python-cross-checked SHA-512 vector head 24d9ccd6).
- Q7 createdAt/meta byte format → carried opaque in P0-B, never byte-compared.
- Q10 live GetConst.php dump → P8 (needs devsam capture env).
- Q16 real JDBC flush + byte-comparable DB-dump + full 70-col General → P1 (P0-B records op-ORDER + exclusion intent only).
- PR-8: RandUtil.nextInt signature — all P1 ports MUST use devsam-core2026 2-arg (min,maxExcl, span-1), not PHP single-arg.

## Residual risks (after edits)

- Double hand-transcription of GameUnitConst (40×18) + CityConst (94×13) goldens (Tasks 16-17) — guarded by structural+spot tests + round-trip diff, but full-table typos possible.
- RESULT-union JsonContentPolymorphicSerializer (Task 21) committed though no P0-B path produces/consumes a CommandResult — most intricate piece with least P0-B exercise.
- WireJson `ignoreUnknownKeys=true` → malformed-rejects corpus must use missing-required/wrong-scalar/unknown-discriminator, NOT extra-key rejection.
- All goldens graded vs TS oracle (correct for P0-B gate); DB byte-parity gates later compare vs PHP runtime — latin-path josa divergence is the known watch-point.
- Build success verified by tail/grep (not exit code); Testcontainers macOS quirk must survive in BOTH engine + api build files.

_Full workflow output: `tasks/wi7wghvaz.output` (run wf_e89243ba-b90)._
