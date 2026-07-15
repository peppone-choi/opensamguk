# Review: scenario_0 player-command unification gate

## Verdict: cleared

SHIP_READY

The first independent review returned `fix-required` despite green gates. Its scenario, lifecycle,
archive, command, lottery, and `checkEmperior` findings have since been remediated. The former
`checkEmperior` line-725 quarantine is superseded by the complete `func_gamerule.php:696-939`
daemon/flush path. `RaiseNPCNation` and the empty tournament pattern use separately seeded,
replayable streams while preserving the enclosing action/monthly `RandUtil` cursor. Their ambient
permutations are explicitly sanctioned divergences and are not claimed as PHP byte parity; the
user directed this shipment to proceed without an additional ambient-permutation test.

The B1 harness directly drives `ReservedTurnHandler` and the nation-command handler with reserved-command-shaped payloads. It uses the production `EventDispatcher`, `WorldEventContextFactory`, and `MonthlyPostUpdateHook`, but substitutes its clock/pre-update/statistics boundaries and does not traverse Redis intake, the due-turn ring, `TurnRunService`, game-api reads, or SSE. Those operational boundaries remain B2.

## Scope

- `CommandContractMatrixTest`: every public general/chief menu code resolves to rest or a contracted command. Ten valid role-oriented five-stat coverage profiles each parse valid args and pass FULL constraints for every public command. The profiles are test inputs for the current five-stat join contract, not PHP-captured golden outputs or a claim that all ten roles execute every command side effect.
- `ScenarioBlankPlayerCommandIT`: six nations and sixty user-owned players execute role commands; user/general/access-log ownership, creation-time five-stat role shape, agriculture, commerce, security, wall, defence, nation tech, crew, and training are asserted at the exact command boundary and after restart.
- `ScenarioBlankUnificationIT`: a real scenario seed reaches the production `checkEmperior` hook,
  flushes final statistic/inheritance/archive/emperor/yearbook/message state, and reloads the world.
  It does not claim PHP permutation parity for the separately documented ambient shuffle.

## PHP anchors

- `legacy/devsam-core/hwe/sammo/Command/Nation/che_선전포고.php:82-197`: declaration constraints and the bilateral `state=1, term=24` transition.
- `legacy/devsam-core/hwe/sammo/Command/General/che_출병.php:79-101` and `:134-261`: sortie constraints, `round(crew / 100)` rice requirement, routing, and battle execution.
- `legacy/devsam-core/hwe/func_gamerule.php:696-939`: one surviving active nation plus all-city ownership, then the blocked final statistic, log, auction, inheritance, United event, archive, yearbook, and message tail.
- `legacy/devsam-core/hwe/sammo/Command/BaseCommand.php:64-86` and `:377-411`: failed `argTest` prevents initialization and returns `인자가 올바르지 않습니다.` before constraints or resolution.
- `legacy/devsam-core/hwe/sammo/Command/General/che_군량매매.php:22-49`: `buyRice` is boolean, `amount` follows PHP `is_numeric`, then `round(amount, -2)` and clamp.
- `legacy/devsam-core/hwe/sammo/Event/Action/RaiseNPCNation.php:199-232`: candidate cities use ambient `shuffle_assoc`, outside the action's seeded `RandUtil` draw stream.
- `legacy/devsam-core/hwe/func.php:1280-1304`: the tournament trigger consumes one monthly `nextBool(0.4)`, while an empty pattern is initialized with ambient `shuffle()` outside that `RandUtil` stream.
- `legacy/devsam-core/hwe/sammo/Event/Action/CreateManyNPC.php:65-94`: fill-mode creation count is `npcCount + rulerCount * fillCnt - registered npc<4 generals`, not the literal `npcCount`.
- `legacy/devsam-core/hwe/sammo/Scenario.php:421-469,557-568`: underage scenario generals are grouped by birth and registered through a one-shot `Month` event at `birth + adultAge`, January.
- `legacy/devsam-core/hwe/sammo/Scenario/GeneralBuilder.php:575-582,656-667`: absolute birth/death years decide visibility, while killturn is a remaining-month counter.
- `legacy/devsam-core/hwe/sammo/Event/Action/RegNPC.php:14-59`: deferred registration uses a name/nation/stat-derived `RegNPC` seed and the shared `GeneralBuilder`.

## Runtime evidence: debugging audit

| Hypothesis | Observation | Decision |
|---|---|---|
| A remote sortie target would move through friendly fallback cities | The first bot policy plateaued at 32 owned cities because a friendly fallback did not provide the intended persistent move | Select adjacent sortie targets and issue explicit `che_이동` for owned routes |
| Any owned high-population city was a sufficient resupply point | The bot exhausted movement money or selected unreachable isolated front cities | Prefer attack-front supply cities, reject unreachable candidates, and rotate to another player |
| Monthly post-update alone was enough during a 24-month declaration wait | Movement cost rose with elapsed years while player gold remained at 22-24; the bot stalled around 89-90 cities | Run `MonthlyPipeline -> EventDispatcher -> WorldEventContextFactory -> MonthlyPostUpdateHook` so income and diplomacy advance together |
| Crew alone identified a valid attacker | A 576-crew player fell back with `군량이 모자랍니다.` and a 54,076-crew player had only 375 rice | Match the PHP sortie rice predicate and require `rice >= phpRound(crew / 100.0)` |
| An allowed recruit command guaranteed an executable war order | Some already-large armies accepted the command without producing a usable sortie candidate, leaving `chooseWarOrder` null | Re-evaluate `chooseWarOrder` immediately after recruitment and rotate to another player until a real order exists |
| A green JUnit suite meant the daemon test was clean | XML `system-out` contained `turn-daemon-loop tick failed` after Testcontainers stopped PostgreSQL while the cached Spring context still owned the worker | Mark `EmptyWorldBootIT` dirty and explicitly stop/join `TurnDaemonRunner` in `@AfterEach`; verify stop precedes Hikari shutdown and scan XML errors |
| Character creation preserves requested stats exactly | The legacy join draw distributed a total 3-5 bonus across leadership/strength/intelligence, so an exact input equality assertion failed | Assert the legacy bonus range and role shape at creation, then compare the actual post-command stats across flush/reload |
| Recruiting a small batch was sufficient and every supply pass should train | 100-person batches exhausted the 800-step budget; 500-person batches could exhaust gold; maxed training correctly denied redundant training | Sell rice through real `che_군량매매`, recruit 500, and issue training/morale only below their command caps while asserting every executed delta |
| Malformed reserved args would safely fall back | `che_군량매매` reached its resolver with an unbound/invalid map and threw `ClassCastException` | Parse and validate every reserved payload before constraints/resolution; log the PHP invalid-arg reason and return `휴식` without mutation |
| Kotlin ambient shuffle was harmless | `cities.shuffled()` used `Random.Default`, making the same world seed produce different candidate order | Use the separately seeded deterministic divergence adopted in LEDGER 26; preserve the action RNG cursor and make no PHP permutation claim |
| Tournament pattern shuffle could remain ambient | `listOf(0, 0, 1, 2, 3).shuffled()` changed the selected tournament under an identical world seed | Reuse a fresh canonical monthly-seed stream for the pattern only, preserving the passed monthly RNG cursor and removing `Random.Default` from production logic |
| `CreateManyNPC(10, 10)` always creates exactly ten free NPCs | A fresh run created eleven because one registered player was no longer counted by PHP's `npc < 4` fill formula | Derive only the free-NPC fill expectation from the PHP formula; keep RaiseNPCNation's selected permutation outside the PHP byte-parity claim |
| Scenario roster size equals the starting general count | The 181 start-year roster contained 678 entries, but 449 had not reached age 14 | Insert only 229 living adults, persist 77 birth-group events for the remaining 449, and execute `RegNPC` once at the absolute adult year |
| Multiplying birth/death years by three would align the 36-turn calendar | The world year already advances once per 36 phase turns; multiplying an absolute year changes the historical date | Keep absolute years unchanged and multiply only legacy month-duration counters such as killturn at the engine boundary |
| Declared arg keys were enough to keep malformed payloads out of resolvers | Fractional destination ids truncated through `Number.toInt`, and negative recruitment was clamped to 100 | Validate every parsed schema value by declared type and apply PHP-specific recruitment normalization before constraints |

## Current test evidence

- Fresh `ReservedTurnHandlerTest --rerun-tasks`: 19 tests, skipped 0, failures 0, errors 0 after reproducing the invalid-payload crash in RED.
- Fresh `WorldActionContextRngTest --rerun-tasks`: 1 test, skipped 0, failures 0, errors 0 after reproducing nondeterministic ordering in RED.
- Fresh tournament-pattern regression: RED 1 failure, then GREEN 1 test, skipped 0, failures 0, errors 0; production `shuffled()`/`Random.Default` scan is empty under engine and logic.
- Fresh `ScenarioBlankUnificationIT`: production hook through JDBC flush/reload, including
  `isunited=2`, archive state, and final history.
- Fresh lifecycle conversion bundle: `ScenarioLifecycleMetaTest`, unchanged `GeneralBuilderGoldenTest`, `KillTombstoneTest`, and `BuiltGeneralMapperTest` passed with the three-phase killturn conversion.
- Fresh scenario appearance gates: `ScenarioImporterIT` proved 229 immediate generals plus 77 deferred birth events; `ScenarioBootIT` executed the 182 January event, created twenty age-14 generals with 30 turns and 37 rank rows, deleted the event, flushed, and reloaded the appeared general.
- Fresh malformed-arg gates: `ReservedTurnHandlerTest` rejects fractional schema integers and negative recruitment before constraints; the 886-case `CommandContractMatrixTest` remains green.
- Final lifecycle/command target run: `BUILD SUCCESSFUL in 42m 31s`; 16 suites and 112 tests, skipped/failures/errors 0. A separately corrected package selector ran `MilitaryConstraintsTest` 15/15 green.
- The full gate first exposed 22 contradictory recruit success fixtures, then one underfunded production-pipeline fixture. Both were corrected without weakening production constraints; `CommandContractMatrixTest` returned to 886/886 green and `ProductionPipelineIntegrationTest` to 3/3 green.
- Forced full backend rerun: `BUILD SUCCESSFUL in 6m 13s`; all five backend test tasks executed with `--rerun-tasks`.
- Earlier canonical backend parity gate: `BUILD SUCCESSFUL in 2m 40s`; XML gate green with 480 suites and 4,358 tests. The previously observed 22 failures and the three subsequently exposed stale log assertions were absent; whole-suite failures/errors were 0 and the single skip was the existing Docker-availability assumption path.
- Final isolated rerun after unification archive, dissolution-event, and persistence-boundary remediation: forced five-module gate `BUILD SUCCESSFUL in 30m 44s`, then canonical `tools/parity/gate.sh backend` `BUILD SUCCESSFUL in 15m 52s`; both report 481 suites and 4,406 tests with failures/errors 0 and one existing assumption skip. The original 22 failures remain absent.
- Final canonical rerun after the four direct PHP parity repairs: `tools/parity/gate.sh backend` `BUILD SUCCESSFUL in 20m 51s`; an independent XML aggregation confirms 481 suites, 4,406 tests, failures/errors 0, skip 1. The original 22 failures remain absent.
- Final-review remediation target: `JoinTest`, `RandomImgwanTest`, and `ReservedTurnHandlerTest` `BUILD SUCCESSFUL in 11m 55s`. This proves `che_장수대상임관` persists `destGeneralID`, forced-unique refunds delete the aux marker, and the random-join test no longer claims PHP native-shuffle order.
- The final forced run exposed three additional assertions: `last_turn` duplicated into `general.meta`, NPC random-join inheritance, and conflict JSON `8.0`. The first was fixed at `GeneralRowMapper`; the latter two expectations were aligned to PHP `InheritancePointManager.php:261-270` and `process_war.php:508-522`. Their focused rerun and both full gates are green.
- Frontend verification on the current Next 15-line patch `15.5.20`: `web/game` typecheck, 37 files/148 tests, and production build green; `web/gateway` typecheck, 1 file/3 tests, and production build green. The ActionLogger-prefix RED reproduced two duplicate-date assertions before the three live log surfaces were aligned to the legacy inline rendering contract.
- Production dependency audit: the prior `15.5.19` dependency graphs reported zero vulnerabilities. The final `15.5.20` re-audit could not produce a result because both npm audit endpoints returned HTTP 410; this external failure is recorded and is not treated as a successful audit.
- Credential-default hardening: the tracked weak admin password example and local Compose fallback were removed. Production Compose still requires an explicit server-side secret, and blank local values skip the idempotent admin seed.
- Final strict agent-system rerun after the cleared critique artifact returned `findings: []` and `ok: true`.
- The first independent read-only parity review returned `fix-required`. Remediation now includes
  PHP seed RNG replay, neutral/deferred NPCs, a source-verified V26 deferred-event migration,
  active server identity, restart-safe archives/inheritance, dissolution/founding alternatives,
  exhaustive unique-lottery call-site coverage, ambient-shuffle proof, and the complete unification
  tail. Fresh reviewer `019f6495-1e81-74a3-ab98-f1f4f12e1c0a` rechecked the four final findings
  and returned exact `Verdict: cleared` plus `SHIP_READY` after the targeted gate passed.

## Residual scope

- B1b peace/non-aggression/break diplomacy policy remains planned and is not claimed by this review.
- B2 must cover Redis intake, `TurnRunService`, API/SSE observation, and atomic rollback/retry of a failed fresh-world seed.
