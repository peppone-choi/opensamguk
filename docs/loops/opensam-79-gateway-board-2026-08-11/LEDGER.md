# OPENSAM-79 gateway community board ledger

## Bounded contract

- This isolated worktree is rooted at `origin/main` commit `d7e051af32c625a13f3d87b9d750817f4d8fd1bd` on branch `codex/opensam79-board-backend`.
- Root delegation under the user's maximum-parallel authorization is the task contract. The shared `.ai/task.md` remains owned by the active OPENSAM-35 workstream and is intentionally not edited here.
- Scope: a gateway-only community board foundation for notices, free posts, suggestions, and comments. Public read; authenticated creation/commenting; owner-or-admin deletion; admin-only pinning; deterministic pinned-first pagination; XSS-safe body contract; and masked soft deletion.
- Excluded: `web/gateway` (OPENSAM-80), admin UI (OPENSAM-81), game-world `board_post`/`board_comment`, daemon/JDBC persistence-spine code (OPENSAM-149), shared `.ai/*`, deploys, and tracker mutations. The user later explicitly authorized the bounded commit, push, and ready-PR handoff after focused green, strict, and independent review.

## Migration reservation

- Inventory was taken across every tracked `V*__*` SQL and Kotlin Flyway migration before reserving this file. The latest production version is `V39__general_owner_lifecycle_normalization.sql`; Kotlin migrations are `V26__npc_lifecycle_phase_units.kt` and `V38__rtk14_npc_lifecycle_repair.kt`.
- Reserved version: `V40__gateway_board.sql` only. It creates gateway-owned tables named `gateway_board_post` and `gateway_board_comment`, never changes game-world board tables.

## API and rendering contract

| Route | Access | Contract |
| --- | --- | --- |
| `GET /board/posts` | public | `category`, zero-based `page`, and bounded `size`; rows sort by `pinned DESC`, `pinnedAt DESC`, `createdAt DESC`, then `id DESC`; sends `Vary: Authorization`. |
| `GET /board/posts/{postId}` | public | Returns a post and chronological comments; sends `Vary: Authorization`. Soft-deleted rows are masks, never historical body/title content. |
| `POST /board/posts` | authenticated | Creates `NOTICE`, `FREE`, or `SUGGESTION`; only ADMIN may create `NOTICE`. |
| `POST /board/posts/{postId}/comments` | authenticated | Creates a plaintext comment on a non-deleted post. |
| `DELETE /board/posts/{postId}` and `DELETE /board/posts/{postId}/comments/{commentId}` | owner or ADMIN | `204 No Content`; soft deletes. The original content remains in storage but is never returned after deletion. |
| `PATCH /board/posts/{postId}/pin` | ADMIN | Sets/unsets pin state and pin timestamp. |

- Gateway account identity is `users.id` (`author_account_id`), with a display-name snapshot. No `world_id`, game general, or nation identity participates in this model.
- Requests are plain text. Post content is escaped before storage as `contentHtml` and converts line breaks only to `<br>`; raw tags/attributes are never accepted as markup. Titles, author names, and comments are returned as text fields and consumers must render them as text, not HTML.
- A deleted post returns a fixed Korean mask for title/body and hides comments; a deleted comment returns a fixed Korean text mask. This preserves thread/pagination shape without leaking removed content.
- List JSON is `{content, page, size, totalElements, totalPages}`. Each post item is `{id, category, authorName, title, contentHtml, pinned, canDelete, deleted, createdAt, updatedAt}`. Detail JSON is `{post, comments}`; each comment is `{id, authorName, content, canDelete, deleted, createdAt}`. `canDelete` is true only for the optional authenticated author or an ADMIN; it is false for anonymous readers, including a malformed or expired Bearer value on a public read. Pin input is `{"pinned": boolean}` and returns the same post item. All board error bodies are `{message, status}`.

## Test-first plan

1. Add HTTP/migration tests that fail against the current gateway: public read, anonymous mutation denial, migration V40 absence, and no world-scoped board reuse.
2. Add the V40 migration and gateway-board persistence configuration/entities/repositories; rerun the migration test until it passes.
3. Add focused red tests for creation, pin ordering/page boundaries, ownership/admin authorization, XSS encoding, and deletion masks; implement only the required service/controller behavior between green runs.
4. Run gateway-focused unit/security and PostgreSQL Testcontainers tests, inspect XML, run `verify-changes.sh`, and record the exact results.
5. Request an independent review, then perform the explicitly authorized bounded commit/push/ready-PR handoff only after focused green, strict, and a cleared artifact.

## Environment baseline

- The Fablize wrapper emits generic tool-failure notices even around successful read-only commands. This is a documented harness baseline (also recorded by existing loop ledgers), not product-test evidence. Completion evidence will use command output, changed-file inspection, and test XML.
- The first targeted Gradle red run spent over eight hours in dependency-management graph resolution and emitted no task output/XML. A second offline/no-configuration-cache run reproduced that resolver-only state. Both owned wrapper/daemon pairs were stopped gracefully on 2026-08-12 after stack inspection showed Maven POM exclusion resolution, and root has reserved a single JVM slot for later verification. No test result is claimed from those runs.
- LSP daemon access is unavailable in this environment; compiler/test output and a manual static diff review remain the authoritative diagnostics until the daemon is restored.
- A one-off static-diff shell loop shadowed zsh's special `path` variable and then treated `git diff --no-index`'s normal exit code `1` as a failure. It changed no files. The loop was rerun with `file_path` and an explicit `0|1` diff-exit allowance; it completed with no whitespace errors and no gateway board `world_id`, `general_id`, or `nation_id` references.

## Current verification gate

- `scripts/agent/verify-changes.sh` on 2026-08-12 selected `:app:gateway-api:test :infra:test` plus whitespace and strict agent-system review. It found the intended 13-file scoped change set.
- `python3 tools/agent-system/check.py --strict --base origin/main` is intentionally pending rather than green: it requires an independent `docs/superpowers/reviews/*.md` critique artifact, a `docs/superpowers` source-of-truth rationale for this new gateway surface, and mapped evidence for the V40 infra migration. This ledger is task-local and does not substitute for that independent review. Root orchestration has been notified.
- The authorized source-of-truth note now lives at `docs/superpowers/research/2026-08-12-opensam-79-gateway-community-board-contract.md`; the strict rerun cleared `docs-drift`. The only remaining strict findings are the deliberately deferred independent critique and its `infra/src` evidence coverage.
- OPENSAM-80 and OPENSAM-81 independently consumed the locked gateway API contract. OPENSAM-81 also confirmed that its proxy targets `GATEWAY_API_URL`; all OPENSAM-79 routes are gateway-api `/board/**`, never game-api.
- OPENSAM-80's independent frontend review found and corrected its stale flat comment-delete assumption. The end-to-end route is now locked as `DELETE /api/board/posts/{postId}/comments/{commentId}` at the gateway proxy and `DELETE /board/posts/{postId}/comments/{commentId}` in gateway-api; OPENSAM-80 reports focused 14/14 passing after the correction.
- Focused validation on 2026-08-12 ran exactly once under the coordinated JVM slot and reached `:app:gateway-api:compileTestKotlin` after 14m31s. It failed before tests because `GatewayBoardMigrationIT` referenced the non-exported `org.flywaydb.core.Flyway` type. The test does not need the type: gateway startup already runs V40, then the test reruns V40 raw SQL and asserts exactly one successful V40 history row through `JdbcTemplate`. The direct import/field/migrate call were removed; a new slot is required for the post-fix focused rerun.

### Post-compile focused RED — 2026-08-12

- The next coordinated command was exactly `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --no-daemon --console=plain :app:gateway-api:test --tests 'opensamguk.gateway.board.*' --rerun-tasks -Pkotlin.compiler.execution.strategy=in-process`. It compiled and emitted fresh XML, then failed before any board assertion: `GatewayBoardHttpSecurityTest` reported `tests="9" failures="9"`, and `GatewayBoardMigrationIT` reported `tests="1" failures="1"`.
- First causal exception: `LocalProfileIconStorage` rejected macOS's native `Files.newDirectoryStream` at `LocalProfileIconStorage.kt:104` because it is not a `SecureDirectoryStream`. Existing gateway integration tests explicitly import `ProfileIconSecureStorageTestConfiguration`, whose primary `ProfileIconRootStreamFactory` supplies the portable secure-stream test fixture. Both new board contexts omitted that import. The test-only import is the minimal isolation fix; it does not weaken production storage security.
- The same XML logged Hibernate `HHH000305` for both board entities: `Getter methods of lazy classes cannot be final`, beginning with `GatewayBoardPostEntity#getAuthorAccountId` and its comment counterpart. `gateway-api` applies Kotlin Spring but not Kotlin JPA, so entity property accessors remain final unless declared open. The minimal production fix is to declare the board entity properties `open var`.
- The test output also contains pre-existing H2 DDL warnings for PostgreSQL-specific non-board entities (`game_kv.key`, `nation_env.key`, and `ng_auction_resource`). They are a distinct H2 compatibility hypothesis, not yet attributed as the board-test terminal cause; no broad persistence change is being made before the isolated profile storage/proxyability rerun.
- Fablize again emitted generic `tool failure` notices around successful read-only inspection commands. This remains the known harness baseline, isolated from the observed Gradle XML above; no product behavior is inferred from those notices.

### Final focused GREEN — 2026-08-12

- Under the released, single JVM slot, the focused command ran once after the invalid-Bearer regression was added: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --no-daemon --console=plain :app:gateway-api:test --tests 'opensamguk.gateway.board.*' --rerun-tasks -Pkotlin.compiler.execution.strategy=in-process`. It ended `BUILD SUCCESSFUL in 3m 39s` with 13 executed tasks.
- Fresh XML is authoritative: `GatewayBoardHttpSecurityTest` reports `tests="11" skipped="0" failures="0" errors="0"`; `GatewayBoardMigrationIT` reports `tests="1" skipped="0" failures="0" errors="0"`. The HTTP suite includes malformed-Bearer `GET /board/posts/{id}` returning `200` with `canDelete:false`, so stale gateway cookies preserve public reading instead of yielding a 401.
- The earlier H2-only sort failure was isolated to Spring Data's unsupported Criteria `nullsLast()` expression. The final feed sort uses `pinned DESC, pinnedAt DESC, createdAt DESC, id DESC`; V40's pin-state check guarantees `pinnedAt` is non-null exactly when `pinned` is true, and the focused pagination test is green. No in-place immutable-list mutation was involved.
- The independent critique, strict checker, scoped diff check, commit/push/PR handoff remain pending; no completion claim is made by this entry.

### Independent review remediation — 2026-08-12

- The first independent review returned `fix-required` for two MEDIUM findings: the 311-line HTTP test joined unrelated scenarios, and its stale-Bearer regression was malformed-only despite the contract also requiring an actual expired access token. The findings were verified against the source and repaired in test scope only.
- The oversized suite was replaced by `GatewayBoardReadSecurityTest` (203 lines), `GatewayBoardPostMutationSecurityTest` (173 lines), and `GatewayBoardCommentSecurityTest` (104 lines), each below the 250-line ceiling. The read test signs a genuinely expired access token with the configured test key, first proves provider validation false, then proves a `200` anonymous public detail response with `canDelete:false`.
- The first remediation run stopped before tests because a new private helper named `post` shadowed MockMvc's imported `post` builder. It produced four compile errors (`contentType` unresolved), all in the split test file; renaming only that helper to `storedPost` removed the collision. No production file changed for this repair.
- The next focused command ran once: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --no-daemon --console=plain :app:gateway-api:test --tests 'opensamguk.gateway.board.*' --rerun-tasks -Pkotlin.compiler.execution.strategy=in-process`, ending `BUILD SUCCESSFUL in 6m 40s` with 13 executed tasks. Fresh XML: read security `6/0/0`, post mutation security `8/0/0`, comment security `5/0/0`, and PostgreSQL migration IT `1/0/0`.
- A separate read-only reviewer then re-inspected the complete uncommitted diff, source paths, V40, documentation, and XML; it returned `Verdict: cleared`. Its PR-visible artifact is `docs/superpowers/reviews/2026-08-12-opensam-79-gateway-board-review.md`. Strict/diff/rebase/commit/push/PR remain pending at this ledger point.
