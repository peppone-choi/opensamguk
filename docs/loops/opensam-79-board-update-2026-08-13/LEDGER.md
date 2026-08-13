# OPENSAM-79 board-post update API ledger

## Scope

- Worktree: `/private/tmp/opensam-op79-board-update`
- Branch/base: `codex/opensam79-board-update` from `origin/main`
  `fe7127fdd240632b448409b7f7c04e3ef7c1e966`
- Ownership: `app/gateway-api` board controller/service/contracts and focused tests,
  plus this task-local loop and review evidence. Shared `.ai/*`, frontend, migrations,
  shared security, and game-board paths are read-only/out of scope.

## Oracle and contract decision

The PHP board components are not the behavior oracle for this task. The existing merged
OPENSAM-79 contract explicitly classifies this as a new account-global community board
(`docs/superpowers/research/2026-08-12-opensam-79-gateway-community-board-contract.md:5-8`).
Legacy `hwe/ts/components/BoardArticle.vue:1-98` instead describes an in-game comment
flow. The user/product acceptance set is frozen in `GOLDENSET.md`.

## Round 0 — baseline and hypothesis

| Round | Hypothesis | Score before -> after | Grader | Verdict | Cause |
| --- | --- | --- | --- | --- | --- |
| 0 | The stable post-update route is absent because `GatewayBoardController` exposes only create/delete/pin mutations and `GatewayBoardService` has no post-content update operation. | 0/6 -> 0/6 | Focused HTTP RED test, then gateway board suite | RED observed | The controller/service/DTO path was never added when OPENSAM-79 landed. |

### Evidence before editing production code

- `GatewayBoardController.kt:48-87` exposes `POST /posts`, deletes, and only
  `PATCH /posts/{postId}/pin`; no content update route exists.
- `GatewayBoardService.kt:55-142` has create/delete/pin operations only.
- `GatewayBoardContracts.kt:14-34` has create and pin request DTOs only.
- Existing `GatewayBoardPostMutationSecurityTest` provides the project’s real HTTP/
  principal test pattern and is retained unchanged.
- Fresh XML at `app/gateway-api/build/test-results/test/TEST-opensamguk.gateway.board.GatewayBoardPostUpdateSecurityTest.xml`
  reports 7 tests, 6 failures, 0 errors. The anonymous request already satisfies the
  gateway JSON 401 contract; each authenticated post-update acceptance path is an exact
  `405 Method Not Allowed` instead of its expected `200`, `403`, or `409`.

### One hypothesis and acceptance threshold

Add exactly one owner/admin update operation at `PATCH /board/posts/{postId}`, using the
existing sanitizer, post response mapper, authorization helper, and `NOTICE` policy.
Adopt only if the focused RED turns GREEN and the full gateway-board regression suite,
strict checker, and independent review all clear. Otherwise revert this round.

### Focused GREEN

- Command: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --no-daemon
  --max-workers=1 --console=plain :app:gateway-api:test --tests
  'opensamguk.gateway.board.GatewayBoardPostUpdateSecurityTest' --rerun-tasks
  -Pkotlin.compiler.execution.strategy=in-process`
- Terminal output: `BUILD SUCCESSFUL in 12m 45s`.
- Fresh XML: `app/gateway-api/build/test-results/test/TEST-opensamguk.gateway.board.GatewayBoardPostUpdateSecurityTest.xml`
  has `tests="7"`, `failures="0"`, and `errors="0"`.
- Result: the same suite that observed six `405` failures before production code now
  proves owner/admin updates, anonymous/non-owner denial, deleted rejection, NOTICE
  policy, safe plain-text rendering, and public `canDelete`/`Vary` behavior.

### Gateway-board regression

- Command: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --no-daemon
  --max-workers=1 --console=plain :app:gateway-api:test --tests
  'opensamguk.gateway.board.*' --rerun-tasks
  -Pkotlin.compiler.execution.strategy=in-process`
- Terminal output: `BUILD SUCCESSFUL in 4m 58s`.
- Fresh XML under `app/gateway-api/build/test-results/test/` reports zero failures and
  errors for `GatewayBoardReadSecurityTest` (6), `GatewayBoardPostMutationSecurityTest`
  (8), `GatewayBoardPostUpdateSecurityTest` (7), `GatewayBoardCommentSecurityTest` (5),
  and `GatewayBoardMigrationIT` (1): 27 tests total.

### Planned live HTTP QA artifacts

The matching runtime surface is the gateway HTTP API, not the frontend. Before starting it,
the following disposable artifacts are registered for cleanup after the `curl` check:

- Docker containers `opensam79-qa-postgres` and `opensam79-qa-redis`, each started with
  `--rm` on otherwise unused host ports and stopped by exact name.
- One temporary `mktemp -d` profile-icon root under `/private/tmp/opensam79-qa.*`, removed
  by its exact path after the server stops.
- One local `:18079` gateway-api `bootRun` process configured only with the temporary DB,
  Redis, profile root, and test-only admin credentials; stopped by its exact process/session.
- No repository source, migration, shared Docker service, user database, credentials file,
  or frontend state is used for this QA. Credentials/tokens are never written to the ledger
  or command output.

### Live QA boot finding and recovery

- The macOS-host `bootRun` did connect to the disposable PostgreSQL database and completed
  Flyway V1–V40, but exited before HTTP readiness with `ProfileIconStorageException`.
- Root cause is outside this board change: `LocalProfileIconStorage` fail-closes unless the
  platform provides a `SecureDirectoryStream`; the project fixture documents that
  `Files.newDirectoryStream` is non-secure on macOS
  (`ProfileIconSecureDirectoryStreamFixture.kt:21-24`). The temporary root existed and the
  service created its child directory, so a missing-path hypothesis is refuted.
- Recovery: run the same production jar in a disposable Linux container, where the secure
  stream requirement is supported. Before that run, create and later remove exact network
  `opensam79-qa-net` and app container `opensam79-qa-gateway`; attach only the two already
  registered QA backing containers. This remains an isolated runtime baseline rather than a
  production-code defect or an OPENSAM-79 scope expansion.

### Live HTTP QA — PASS

The packaged gateway API ran in the registered disposable Linux container against only its
disposable PostgreSQL/Redis services. It started successfully, applied/validated Flyway V1–V40,
and seeded one test-only ADMIN account. Test credentials and JWTs were held only in shell
variables and are intentionally not retained here.

- Anonymous `PATCH /board/posts/{id}`: `401`.
- Authenticated ADMIN create then `PATCH`: response category `SUGGESTION`, trimmed title
  `live updated`, escaped body `&lt;script&gt;alert(1)&lt;/script&gt;<br>next`,
  `canDelete:true`, and `deleted:false`.
- Anonymous `GET /board/posts/{id}` after the update: `200` with `Vary: Authorization`.
- Authenticated ADMIN category change to `NOTICE`: `200`.
- ADMIN soft delete: `204`; subsequent PATCH: `409`; public detail remained masked with
  title/body `삭제된 게시글입니다.`, `deleted:true`, and `pinned:false`.

Cleanup is now required for the registered exact QA containers, network, temporary profile
directory, and health probe file; no service or fixture is retained for PR evidence.

### QA cleanup — complete

`opensam79-qa-gateway`, `opensam79-qa-postgres`, `opensam79-qa-redis`, and
`opensam79-qa-net` were removed by exact name. The temporary profile directory and probe
file were empty/removed with exact `rmdir`/`unlink` operations. A command guard rejected an
initial bundled recursive-delete form before execution; the explicit cleanup above then
verified no matching container, network, directory, or probe file remains.

## Independent review

- PR-visible artifact:
  `docs/superpowers/reviews/2026-08-13-opensam-79-board-update-review.md`.
- A separate read-only reviewer inspected the controller/service/DTO diff, focused HTTP
  test, XML evidence, live-QA ledger, and scope boundary. Verdict: cleared; Critical 0,
  Important 0, Minor 0.
- It independently confirmed the stable PATCH route, owner/ADMIN authorization, deleted
  `409` without mutation, ADMIN-only `NOTICE`, sanitizer/title behavior, and unchanged
  public `canDelete`/`Vary: Authorization` semantics. It also confirmed no migration,
  frontend, shared-security, game-board, or shared `.ai` change.

## Tooling baseline

Generic Fablize wrapper `tool failure` notices recurred around successful, read-only
instruction/source commands in this session. Initial instruction reads also hit one bad
cache path and one output truncation; the path was corrected and later reads were split.
No repository file or product test was affected. Product claims will use direct command
output, fresh XML, and independent review rather than wrapper telemetry.

The LSP daemon is unreachable at its configured local socket, so no editor diagnostics are
available in this worktree. Compiler output and fresh Gradle XML remain the diagnostic
authority for this Kotlin-only change.

### Gradle RED-run runtime audit

- Artifact journal: no source instrumentation, temporary files, ports, or debugger
  sessions were created; the only deliberate runtime artifact is the single focused Gradle
  process, which will be allowed to finish before another gateway Gradle run starts.
- H1 (task-graph deadlock) is refuted by `jcmd` observation of a live Kotlin FIR resolver
  thread and subsequent compiler warnings from the worktree.
- H2 (shared Gradle contention) remains environmental context: another unrelated Gradle
  daemon is active, but no cross-worktree process has been stopped or modified.
- H3 (the `--rerun-tasks` focused command requires a broad Kotlin recompilation) is
  supported by the live compiler output. The target XML subsequently confirmed the RED
  405 result above.
