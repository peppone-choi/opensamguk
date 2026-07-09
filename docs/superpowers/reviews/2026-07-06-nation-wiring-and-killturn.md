# 2026-07-06 nation resolver wiring + killturn review

Status: cleared

## Scope

- `che_불가침수락`, `che_종전수락`, `che_불가침파기수락` now use the engine nation-command resolver path instead of falling through to the pass-through seam.
- Scenario seed/load lifecycle metadata now derives `killturn` per general from `deadYear`, preventing a global same-turn death collapse.

## PHP evidence

- `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침수락.php`: constraints/run comments in the Kotlin port cite PHP lines 121-140 and 171-233. The resolver path preserves draw count 0 and buffers `resp_assist` before diplomacy deltas.
- `legacy/devsam-core/hwe/sammo/Command/Nation/che_종전수락.php`: Kotlin port cites PHP lines 95-105 and 157-194. The engine resolver applies bidirectional `state=2, term=0`.
- `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침파기수락.php`: Kotlin port cites PHP lines 87-97 and 149-179. The engine resolver applies bidirectional `state=2, term=0`.
- `legacy/devsam-core/hwe/sammo/Scenario/GeneralBuilder.php:662`: `killturn = (death - year) * 12 + rng.nextRangeInt(0, 11) + month - 1`. The deterministic scenario importer intentionally omits the jitter in this B-track seed path, but now keeps the per-general `deadYear` term instead of a global baseline.

## Verification

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests opensamguk.engine.turn.NationCommandDispatchTest`
  - XML: `tests="4" skipped="0" failures="0" errors="0"` in `app/game-engine/build/test-results/test/TEST-opensamguk.engine.turn.NationCommandDispatchTest.xml`.
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :common:test --tests opensamguk.common.constants.ScenarioLifecycleMetaTest`
  - XML: `tests="6" skipped="0" failures="0" errors="0"` in `common/build/test-results/test/TEST-opensamguk.common.constants.ScenarioLifecycleMetaTest.xml`.
- `git diff --check`
- `tools/agent-system/check.py --format json`

## Follow-up (2026-07-09 parity loop)

Additional (a) fixes on the same branch:

- **급습/이호경식 term**: PHP `term - 3` / `IF(state=0, 3, term+3)` was cascade-encoded but applied as absolute in `ReservedTurnHandler`. Added `DiplomacyCascadeTerm` + engine apply; registered both commands in `DaemonLoopConfig.installNationActionResolvers`.
- Tests: `DiplomacyCascadeTermTest` (7), extended `NationCommandDispatchTest` (급습 term 15→12, 이호경식 war→3 / decl 12→15).
- Backend gate: `tools/parity/gate.sh backend` BUILD SUCCESSFUL, XML failures/errors=0.

Residual quarantine: 급습/이호경식 exp/ded + broadcast still need a general mutation channel on the nation-pass registry path; other nation strategic cmds still registry-incomplete.

## Stub audit

Runner subagent audited `logic`, `common`, `app/game-engine`, and `app/game-api` for `TODO`/`stub`/`NotImplementedError`/placeholder returns. Highest-signal remaining items:

- `logic/src/main/kotlin/opensamguk/logic/event/EventCodec.kt` + `EventCondition.kt`: `Interval` is still a loud fail-fast quarantine. Repo data search did not find live `Interval` conditions, so it is not included in this commit.
- `app/game-engine/src/main/kotlin/opensamguk/engine/run/MonthlyPostUpdateHook.kt`: several monthly adapter seams remain no-op until their source systems are ported.
- `logic/src/main/kotlin/opensamguk/logic/event/OpenNationBetting.kt`: betting close trigger wiring remains a documented follow-up.

## Critique result

- Executor subagent implemented the resolver wiring and targeted tests.
- Runner subagent performed read-only stub audit and found no higher-priority live input than the two fixes above for this commit.
- No golden expectations were weakened.
