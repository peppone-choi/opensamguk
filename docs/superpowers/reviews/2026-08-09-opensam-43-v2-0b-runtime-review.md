# OPENSAM-43 V2-0B runtime contract independent review

- Date: 2026-08-09
Scope: `.ai/`, `app/`, `common/`, `infra/`, and `docs/` — initial immutable review plus current PR #371 Round 1 dirty remediation based on `origin/main` `e9cc3b31fa72aa46716f375b623f6a4937ad6c06`.
- Independent reviewer: `/root/op43_independent_review` (`fable-deep-reasoner`)
- Historical reviewed-tree fingerprint: `3e05d2cfef9a808efb696db93f4f81f0afc53d39dbadd903798f80c922779cbb`
- Historical terminal reviewed-tree fingerprint: `5c93a23653012a0e557b720f701374ea2fe2c86ea5cebf718856d51933e17360`
- Historical cleared review base HEAD: `ac1d199644f61685ca3fee25f19c36e07782f960`
- Historical cleared dirty-tree diff SHA-256: `0657e23e82f37f2c8ee0a7080edb3cdfbf048bb78966590f3c310cd839a4bb8b`
- Historical `8abb47a1` CI: all green
- Historical `8abb47a1` structural dirty-tree combined fingerprint: `0734d9d5625b70fb6a92ea12c6e5717302b1b689aadcc46a4f17fcbf06f28ac3`
- Historical tracked fingerprint: `023225…06f4`; untracked fixture fingerprint: `898063…dd0`
- Committed PR head: `5363abe9be95c304b30288df493c5b1f006ab8bb`; its exact-SHA remote CI, including CodeRabbit, completed SUCCESS.
- CodeRabbit review on committed `5363abe9`: its 4 findings are remediated in the current uncommitted dirty tree; reviews `0/3`.
- Prior/pre-infra-sync terminal code-remediation review fingerprint: `84dc8a5ee43586b00f36101b7a840f9eba3241fdb7c705460242790d2263b1ca`; no findings; focused exact-source `app:game-engine` mutation `4` / V2Both2 green. It is not a current exact-tree fingerprint.
- Current exact dirty-tree full verifier is `infra-blocked`: after `2h 16m 30s`, only `app:game-api` `V2BothConditionsBeanGateIT` `initializationError` hit a Testcontainers PostgreSQL readiness timeout before assertions (log SHA-256 `8d6ff449e7fa672249768d486416f8d2ad17101d4e0ff59421b4ed13f76cec94`).
- Authorized final isolated rerun was not started because Docker preflight produced no usable version/status; Docker health is `UNKNOWN/not confirmed healthy`. Strict/diff are green. The resulting exact-SHA remote CI after commit/push is the required deciding gate; reviews `0/3` remain pending. This is not a local-full-green or merge-ready claim.
Verdict: cleared

## Committed `5363abe9` evidence, prior code-remediation clearance, and current infra-blocked gate

The committed `5363abe9` local verifier and independent-review record are
supplemented by exact-SHA remote CI: all jobs, including CodeRabbit, completed
SUCCESS. The current uncommitted dirty remediation resolved CodeRabbit's four
findings. The prior/pre-infra-sync terminal independent code-remediation review
fingerprint `84dc8a5ee43586b00f36101b7a840f9eba3241fdb7c705460242790d2263b1ca` had no
findings; it is not a current exact-tree fingerprint. Focused exact-source
`app:game-engine` mutation 4 / V2Both2 are green. That clearance is
prior/pre-infra-sync code-content evidence, not a current exact-tree clearance.
The current exact dirty-tree full verifier ran for `2h 16m 30s` and failed solely
when `app:game-api` `V2BothConditionsBeanGateIT` `initializationError` encountered a Testcontainers PostgreSQL
readiness timeout before assertions (log SHA-256
`8d6ff449e7fa672249768d486416f8d2ad17101d4e0ff59421b4ed13f76cec94`). The
local full verifier is therefore `infra-blocked`, not green. The authorized
isolated rerun was not started because Docker preflight returned no usable
version/status and Docker health was `UNKNOWN/not confirmed healthy`. Focused exact-source
`app:game-engine` mutation 4 / V2Both2, strict, and diff are green. After commit/push, new
exact-SHA remote CI is the required deciding gate; reviews 0/3 remain pending.
This code-content clearance does not claim merge readiness.

The authorized healthy-Docker isolated local full-verifier rerun passed: exit 0,
`BUILD SUCCESSFUL in 23m 46s` / 29 tasks; common 232 + logic 3,173 + infra 236 +
game-api 468 + game-engine 822 = 4,931 tests / failures 0 / errors 0 / engine
skipped 1; strict 46 changed / Errors 0 / Warnings 0 / findings 0; log
`/tmp/op43-catalog-diff-final-os-verify-rerun.log` SHA-256
`a95386e902908c199f12d86cb776e06d97ead25eef71e9dc3647b0e3e671e31e`.
The preceding first run is historical infrastructure-only: a transient Docker
HTTP 500 before game-api app assertions; its log
`/tmp/op43-catalog-diff-final-os-verify.log` SHA-256 is
`706131db0b7c24a2e57d4c7875031b195240b2e565ca2b798f54098bada6aadc`.

### Historical `8abb47a1` structural dirty-tree re-review state

The first submitted `@codex` review of exact SHA `8abb47a1` historically found
three P2 findings:

1. Duplicate catalog metadata keys are accepted.
2. An FK same-name identity check can produce a false positive.
3. `SELECT INTO` can bypass the table-creation guard.

The historical reviewed structural tree remediates the duplicate-key and FK
identity findings and replaces source-parser-only discovery with a PostgreSQL
`pg_class` OID baseline/post-v2 catalog-diff. Its terminal independent re-review
found no findings. Focused convention 17, `app:game-engine` mutation 4 / V2Both2, and infra
catalog 11 were all green. This historical clearance and its all-green CI do not
replace the prior/pre-infra-sync code-remediation clearance or current exact-tree
infra-blocked full-verifier gate.

### Structural-remediation closure

The `pg_class` OID baseline catalog-diff closes the procedural/foreign table
creation fail-open and `SELECT` alias false-positive paths. Duplicate-key and FK
identity fixes are included in the reviewed dirty tree.

The 4,915-test full verifier (failures 0 / errors 0), focused 6 + 1/16 + 2
evidence, and the prior immutable clearance are historical/pre-current-
structural-tree evidence.
The 4,916-test `scripts/agent/verify-changes.sh --run` record (exit 0, `BUILD
SUCCESSFUL in 17m 11s` / 29 executed; common 232 + logic 3,173 + infra 235 +
game-api 468 + game-engine 808; failures/errors 0; game-engine skipped 1;
strict 45 changed / Errors 0 / Warnings 0 / findings 0; log SHA-256
`dbefda4b82181c2e0f24cb3c9667dd33e0e5b2d3c106785789672793a2dc5530`) is
pre-current-structural-tree historical evidence only and does not substitute for
the prior/pre-infra-sync code-remediation clearance or current exact-tree
infra-blocked full-verifier gate.

## Historical PR #371 Round 1 state

PR #371 exists at initial remote commit
`983598928f4375b902d1e49c72551056ce5c9a1f`. Its four remote CI jobs
(`agent-system`, `jvm`, `web (gateway)`, and `web (game)`) are green, but they
predate the current dirty remediation and are not exact-final-SHA evidence. The
initial immutable local review was `cleared`; that conclusion is historical and
does not cover the Round 1 changes below.

Round 1 contains six CodeRabbit threads and one Codex P2:

| Review input | Current disposition / evidence |
|---|---|
| CodeRabbit: historical/current backend count wording | Fixed in lifecycle evidence: 5,063 remains pre-remediation historical evidence and 4,911 is an earlier verifier, not the current Round 1 verifier record. |
| CodeRabbit: task approval wording | Rejected as inapplicable. The active task's `Human approval` clause and approved plan §7 explicitly authorize this ticket's PR/merge, satisfying ADR-LITE-026's ticket-specific explicit approval boundary; no edit to the `Human approval` clause is correct. |
| CodeRabbit: MD022 | Fixed after both V2-0A and V2-G0 headings without changing list content. |
| CodeRabbit: duplicate classpath | Implemented fail-closed duplicate-resource handling. |
| CodeRabbit: duplicate city ID | Implemented duplicate-ID fixture and adapter rejection. |
| CodeRabbit: exact diagnostics and deep/decoy scope | Implemented exact rejection-detail assertions and positive deep/decoy fixture-existence assertions. Focused infra rerun: `BUILD SUCCESSFUL in 42s` / 10 tasks, catalog 10/0/0/0 and adapter 6/0/0/0. |
| Codex P2: world-owned key guard | Every v2-created table PK/UNIQUE must include `world_id`; scoped-key-plus-unscoped-UNIQUE mutation produced intended RED. Combined focused `app:game-engine` current-input fan-in passed: `V2FlywayIsolationConstraintMutationIT` 1/0/0/0 and V2Both2 (`V2BothConditionsBeanGateIT`) 2/0/0/0; engine log SHA-256 `d6ea51c9a8ee5fb9991443eb7313cee666f86092c56e1fd7daf2e461b3e36ba4`, diff-check green. |

The terminal independent re-review at the historical fingerprint above found no
BLOCKER/MAJOR/MINOR/QUESTION/NIT and cleared the then-current dirty remediation.
The pre-final-SHA Round 1 dirty-tree `scripts/agent/verify-changes.sh --run` ran exactly
once and exited 0: `BUILD SUCCESSFUL in 12m 54s` / 29 executed; common 232 +
logic 3,173 + infra 235 + game-api 468 + game-engine 806 = 4,914 tests /
failures 0 / errors 0 / game-engine skipped 1. Strict reported 44 changed /
Errors 0 / Warnings 0 / findings 0; log SHA-256:
`5dc8db9b1f94cb509a8e1c4f826aaa6621e2fcc81e6996db110adc77f8dd9454`.
Compose, smoke, and deploy were not run. This historical evidence does not
replace the prior/pre-infra-sync code-remediation clearance or current exact-tree
infra-blocked full-verifier gate.

## Historical initial critique

The first review was `fix-required` with four MAJOR findings and no BLOCKER:

1. Catalog metadata accepted unknown root keys, including a copied `cities` payload.
2. Both wire decoders silently defaulted a missing `schemaVersion` to version 1.
3. Flyway isolation manually constructed `Flyway` and did not prove the real Spring runtime configuration.
4. The migration convention could be spoofed by a commented `world_id integer NOT NULL` string.

It also found documentation drift in the state, ownership, handoff, and 0B-j inventory. No original finding was
waived or downgraded.

## Historical initial-remediation evidence

| Finding | Change | RED | GREEN |
|---|---|---|---|
| Exact metadata shape | `V2ContentCatalog` now requires exactly the seven approved root keys; a valid eight-key fixture includes copied `cities` rows. | `V2ContentCatalogTest`: 9 tests, 1 intended failure. | 9/0/0/0. |
| Required wire version | Both decoders pre-parse and require a present `schemaVersion`; emitted defaults and independent constants remain. | `V2WireContractTest`: 7 tests, 2 intended missing-version failures. | 7/0/0/0. |
| Actual Flyway runtime | Existing booted v1 and v2 Spring/Testcontainers contexts assert resolved locations, V900 history/table absence or presence, and applied-table constraints. | The strengthened runtime test exposed a real PostgreSQL parser failure in the new catalog query. | Corrected engine v2 run: 21/0/0/0. |
| Non-spoofable convention | Exact physical first-line header, SQL comment stripping, unsupported `CREATE TABLE` fail-closed parsing, and PostgreSQL catalog checks for every applied v2-created table. | Comment-only `world_id` mutation is rejected. | Included in engine v2 21/0/0/0. |

The PostgreSQL failure was not hidden: XML showed `syntax error at or near "constraint"` at position 81. The
reserved alias was replaced with `table_constraint`; the exact rerun completed with `BUILD SUCCESSFUL in 1m 46s`
and 17/17 tasks executed. Log SHA-256:
`42df4a0ee81c1dcb6b40bcd2c15d8db06dc94d475cc0a6ab1e79c2f95f402501`.

The combined common/infra remediation log SHA-256 is
`c970de5a6619414d9b73c490d650c03f186e2449c04972b2df554d4881894152`.

## Historical initial immutable-review conclusion

At its then-exact immutable reviewed state, the terminal re-review found no
blocker, major, minor, question, or nit. It confirmed:

- approved source SHA, 94 total cities, 24 owned cities, and 94 unique IDs;
- metadata-only `content/v2/cities_1010.json`, with no copied production city payload;
- unchanged v1 wire paths and explicit v2-only schema contracts;
- canonical existing process-world identities and exact AND-gated bean sets;
- no production `migration_v2` SQL or product leaf; V900 remains test-only;
- documentation synchronized to the actual Spring/Flyway implementation and superseded pre-review counts.

The pre-remediation broad backend gate is historical evidence only: 605 suites / 5,063 tests / failures 0 /
errors 0 / skipped 1, log SHA-256
`0a6ffedd0868bbf60d9d8439230c2c8664b9bfb78352f930b740be427a6d3a14`. Before Round 1, the prior
current-tree `scripts/agent/verify-changes.sh --run` invocation exited 0 with `BUILD SUCCESSFUL in 16m 19s`,
29 tasks and 4,911 tests across common/logic/infra/game-api/game-engine, failures 0 / errors 0. Strict reported
41 changed, Errors 0, Warnings 0, findings 0. `/tmp/op43-final-os-verify.log` SHA-256 is
`00b137ac81dca1757bb920ccc54f1b0eda1dac18343b5028587ffaab5286242a`. Immutable commit review, remote CI,
and the three required PR-conversation reviews remained separate process gates; compose/smoke/deploy were not run.
This historical conclusion does not clear the current Round 1 dirty remediation.

Generic Fablize warnings observed during successful bounded reads and output-session recovery were isolated as
tooling-wrapper telemetry; no repository gate result is inferred from them.
