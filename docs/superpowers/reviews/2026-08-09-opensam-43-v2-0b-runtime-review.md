# OPENSAM-43 V2-0B runtime contract independent review

- Date: 2026-08-09
Scope: exact dirty working tree based on `origin/main` `e9cc3b31fa72aa46716f375b623f6a4937ad6c06`, covering `.ai/`, `app/`, `common/`, `infra/`, and `docs/`.
- Independent reviewer: `/root/op43_independent_review` (`fable-deep-reasoner`)
- Reviewed-tree fingerprint: `3e05d2cfef9a808efb696db93f4f81f0afc53d39dbadd903798f80c922779cbb`
Verdict: cleared

## Initial critique

The first review was `fix-required` with four MAJOR findings and no BLOCKER:

1. Catalog metadata accepted unknown root keys, including a copied `cities` payload.
2. Both wire decoders silently defaulted a missing `schemaVersion` to version 1.
3. Flyway isolation manually constructed `Flyway` and did not prove the real Spring runtime configuration.
4. The migration convention could be spoofed by a commented `world_id integer NOT NULL` string.

It also found documentation drift in the state, ownership, handoff, and 0B-j inventory. No original finding was
waived or downgraded.

## Remediation evidence

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

## Re-review conclusion

The terminal re-review found no blocker, major, minor, question, or nit. It confirmed:

- approved source SHA, 94 total cities, 24 owned cities, and 94 unique IDs;
- metadata-only `content/v2/cities_1010.json`, with no copied production city payload;
- unchanged v1 wire paths and explicit v2-only schema contracts;
- canonical existing process-world identities and exact AND-gated bean sets;
- no production `migration_v2` SQL or product leaf; V900 remains test-only;
- documentation synchronized to the actual Spring/Flyway implementation and superseded pre-review counts.

The pre-remediation broad backend gate is historical evidence only: 605 suites / 5,063 tests / failures 0 /
errors 0 / skipped 1, log SHA-256
`0a6ffedd0868bbf60d9d8439230c2c8664b9bfb78352f930b740be427a6d3a14`. After clearance, the final
current-tree `scripts/agent/verify-changes.sh --run` invocation exited 0 with `BUILD SUCCESSFUL in 16m 19s`,
29 tasks and 4,911 tests across common/logic/infra/game-api/game-engine, failures 0 / errors 0. Strict reported
41 changed, Errors 0, Warnings 0, findings 0. `/tmp/op43-final-os-verify.log` SHA-256 is
`00b137ac81dca1757bb920ccc54f1b0eda1dac18343b5028587ffaab5286242a`. Immutable commit review, remote CI,
and the three required PR-conversation reviews remain separate process gates; compose/smoke/deploy were not run.

Generic Fablize warnings observed during successful bounded reads and output-session recovery were isolated as
tooling-wrapper telemetry; no repository gate result is inferred from them.
