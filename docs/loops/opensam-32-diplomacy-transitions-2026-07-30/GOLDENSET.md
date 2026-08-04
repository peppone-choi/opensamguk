# OPENSAM-32 외교 상태 전이 골든셋

Status: frozen for the user-approved OPENSAM-32 execution on 2026-07-30.

## PHP grand truth

All six commands accept a `RandUtil` parameter but make zero RNG calls. Existing
`NoRng` tests are therefore the draw-count oracle; no new numeric fixture is
required and no existing golden may be edited.

| D4 | Command | PHP evidence | Required observable result |
|---|---|---|---|
| 08 | `che_종전제의` | `legacy/devsam-core/hwe/sammo/Command/Nation/che_종전제의.php:105-172` | one `stop_war` diplomatic message with `deletable=false`; no diplomacy mutation before accept |
| 09 | `che_종전수락` | `legacy/devsam-core/hwe/sammo/Command/Nation/che_종전수락.php:134-195` | both directions become state `2`/term `0` |
| 10 | `che_불가침제의` | `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침제의.php:154-228` | one `no_aggression` message preserving requested year/month |
| 11 | `che_불가침수락` | `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침수락.php:171-232` | both directions become state `7`; `term=(year*12+month)-(currentYear*12+currentMonth-1)` |
| 12 | `che_불가침파기제의` | `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침파기제의.php:107-174` | one `cancel_na` diplomatic message with `deletable=false` |
| 13 | `che_불가침파기수락` | `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침파기수락.php:126-181` | both directions become state `2`/term `0`; `che_선전포고.php:90-94` no longer rejects the relation as state `7` |

Parity dimensions:

- RNG: zero draws for all six commands.
- Rounding: none.
- Ordering: proposal log precedes message; accept updates both diplomacy rows
  before logs/events.
- Message payload insertion order follows the PHP arrays.
- Korean action logs and titles remain byte-exact where existing sinks expose
  them.
- Daemon writes remain `ChangeRecorder → JdbcFlushExecutor`; no inline/JPA
  daemon write.

## Frozen graders

1. Logic:

   ```bash
   JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test \
     --tests 'opensamguk.logic.actions.nation.CheJongjeonjeuiGoldenTest' \
     --tests 'opensamguk.logic.actions.nation.CheJongjeonSuakGoldenTest' \
     --tests 'opensamguk.logic.actions.nation.CheBulgachimJeuiGoldenTest' \
     --tests 'opensamguk.logic.actions.nation.CheBulgachimSuakGoldenTest' \
     --tests 'opensamguk.logic.actions.nation.CheBulgachimPagijeuiGoldenTest' \
     --tests 'opensamguk.logic.actions.nation.CheBulgachimPagiSuakGoldenTest' \
     --tests 'opensamguk.logic.actions.nation.DiplomacyProposalCommandsTest' \
     --tests 'opensamguk.logic.actions.nation.DiplomacyAcceptCommandsTest' \
     --rerun-tasks
   ```

2. Engine:

   ```bash
   JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test \
     --tests 'opensamguk.engine.intake.DiplomaticMessageHandlerTest' \
     --tests 'opensamguk.engine.turn.ReservedDiplomacyDestTargetTest' \
     --tests 'opensamguk.engine.turn.NationCommandDispatchTest' \
     --rerun-tasks
   ```

3. Flush:

   ```bash
   JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test \
     --tests 'opensamguk.infra.persistence.DiplomacyUpdateFlushIT' \
     --rerun-tasks
   ```

4. Frontend deterministic surface:

   ```bash
   cd web/game && pnpm vitest run __tests__/DiplomacyPage.command.test.tsx
   ```

5. Browser/live surface:
   authenticated local Docker observation of proposal submit and message accept.
   If the required local stack cannot start without unavailable runtime
   configuration, record `채점대기`; do not substitute a mocked unit test as
   browser evidence.

PASS requires `BUILD SUCCESSFUL`, fresh XML with tests greater than zero and
failure/error/skip zero for focused suites, the frontend test green, and no
unresolved independent-review blocker. Any code change additionally requires a
verified RED→GREEN cycle against this unchanged grader.
