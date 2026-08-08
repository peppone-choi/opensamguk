# Review: OPENSAM-35 — V2-0A production isolation gate

Scope: PR #370 (`codex/op-35-v2-0a-final`) Round 1 reconciliation and current Round 3 PR state across `.ai/`, `.github/workflows/`, `app/`, `infra/`, `tools/`, `web/`, and the owned OPENSAM-35 docs. This artifact distinguishes the completed local Round 3 dirty-tree re-review from still-unobserved remote exact-SHA CI.

Stage: **PR #370 Round 3 (Codex, two P2 findings resolved; Round 1 resolved)**
Verdict: cleared

## Review-stage labels and release boundary

- **GATE-f / GATE-f2** are pre-PR adversarial snapshots, both `fix-required` at their respective times.
- **GATE-f3** is a pre-PR false-green remediation loop. Its focused red/green evidence is useful implementation
  history, not a PR conversation disposition or release acceptance.
- **A4 backend is current Round 1 dirty-tree evidence:** Java 21 `--rerun-tasks` six-root gate, **one run / no
  retry**, `BUILD SUCCESSFUL in 12m 35s`, 35 actionable tasks, 601 suites / 5,050 tests / 0 failures / 0 errors /
  1 skipped. Full-log SHA256 is `a35ea5cf8352e2fe518daa32dbe95343f92bf62c95dc41a3673e924aa9fcaad1`.
  It is execution evidence, not by itself a reviewer disposition or release acceptance.
- **A4 599 suites / 5,023 tests and web 54 files / 288 tests** are historical post-remediation records.
  The older backend run was non-forced; the old count does not establish current backend provenance.
- **Independent terminal dirty-tree re-review (completed historical evidence):** **cleared, no findings**,
  reviewer fingerprint `3c1b357c…`. Its scope was the exact reviewer-inspected dirty working tree only.
- **Local immutable-SHA review (completed historical evidence):** completed at
  `54ead4e70cf5fa7c822bc7fef11a8c42f09eded6`. It is a local exact-SHA review, not a third external PR review.
- **Round 3 independent dirty-tree re-review (current review disposition):** terminal **cleared** with no
  blockers, fixes, questions, or nits. It independently clears both P2 dispositions in the reviewed dirty tree;
  it is local review evidence, not a third external PR review or remote exact-SHA CI result.
- **Last observed GitHub CI:** `agent-system`, `jvm`, `web (gateway)`, and `web (game)` jobs were SUCCESS before
  the new contract step was wired. The current remote PR CI run has not been observed. CodeRabbit Round 2 was
  rate-limited and produced no completed review result; its current status context is separate from the historical
  green CI jobs.
- **PR review accounting:** mentions=3 must not be read as three completed external reviews. The actual completed
  PR review results are CodeRabbit Round 1 and Codex Round 3; the local exact-SHA and dirty-tree reviews above are separate.
- **PR #370 Round 3 controls now.** Both Codex P2 source remediations are implemented across three approved source
  items and the independent dirty-tree re-review is terminal `cleared`. Remote exact-SHA CI remains unobserved and
  separately pending; this review artifact is nevertheless `cleared`. The PR remains open. No merge, release, deploy, production observation, or
  OPENSAM-177 consumer execution occurred. OPENSAM-177 is the separate linked shared account/JWT/profile
  live-integration consumer, not proof that OPENSAM-35 was deployed.

## PR Round 1 ledger (23 threads; historical resolved/dispositioned)

| ID | Finding / disposition | Current state |
|---|---|---|
| R1-01 | Current v2 status was stale | **Documented fixed:** baseline zero-runtime statement is now explicitly pre-implementation; current tree has isolation gates only, no product leaf/schema/persistence. |
| R1-02 | PR/release/deploy/OPENSAM-177 boundary was blurred | **Documented fixed:** PR is open; no merge/release/deploy; OPENSAM-177 remains separate/unexecuted. |
| R1-03 | Fragile ownership line reference | **Documented fixed:** stable `Shared-file ownership fence` heading/link replaces row-number references. |
| R1-04 | Kotlin source-comment wording | **Observed resolved:** source lane reports English-only non-game KDoc/comments; the current Java 21 full backend gate is green (601/5,050, failures/errors 0). The independent dirty-tree review cleared this disposition. |
| R1-05 | Flyway inventory count was wrong | **Documented fixed:** 36 SQL files + 1 V29 `.conf` metadata file = 37 inventory files. |
| R1-06 | Git diff evidence used a vacuous pathspec / moving base | **Documented fixed:** historical bad transcript is non-evidence; canonical merge-base `:(glob)…/**` commands are the only current form. |
| R1-07 | S2 PHP replay request | **Not applicable to 0A behavior:** T1/parity unchanged. A3 now says scope/inventory only, never a replay pass; future parity changes need replay. |
| R1-08 | Frontend and backend truth-value semantics were conflated | **Documented fixed:** frontend strictly accepts raw `"true"`; backend `havingValue` is case-insensitive with the profile condition. |
| R1-09 | Reserved path entry was inaccurately described | **Documented fixed:** `RESERVED_PATH_SERVER_IDS` really includes `'v2-lab'`; hyphen regex makes it defensive today. |
| R1-10 | v2 route test count record was stale | **Documented fixed:** stage 13/284 is historical; A4 XML/log records route 17, middleware 8, total 54/288. The requested 14 is not corroborated by source/XML. |
| R1-11 | Missing Compose variable was called a syntax pass | **Documented fixed:** missing required variable is fail-closed failure; only complete-placeholder JSON render is config-shape evidence. |
| R1-12 | Destructive cleanup was presented as runnable | **Documented fixed:** cleanup commands are BLOCKED pending separate, exact-target deletion approval. |
| R1-13 | S1/S5/U12 PHP replay request | **Not applicable to 0A behavior:** retained local Flyway/Compose conclusions only; no replay claim or waiver. |
| R1-14 | Untagged Markdown fences | **Documented fixed:** all owned artifact fences receive an explicit language tag before closure. |
| R1-15 | A3 inaccurately implied golden replay | **Documented fixed:** A3 is scope/inventory proof, not PHP capture, replay, or a substitute for A4. |
| R1-16 | Backend gate provenance was overstated | **Documented fixed / observed current:** 599/5,023 and 286/1,652 are historical; the current one-run/no-retry Java 21 full gate is 601/5,050, failures/errors 0. |
| R1-17 | Plan fence referenced a volatile ownership line | **Documented fixed:** plan links the stable ownership heading. |
| R1-18 | Review stages were merged into one clear claim | **Documented fixed:** this artifact labels pre-PR loops, A4, and PR Round 1 separately. |
| R1-19 | Infra KDoc / source explanation | **Observed resolved:** source lane reports remediation plus focused 7-test XML; the current full backend gate is green (601/5,050, failures/errors 0). |
| R1-20 | Configuration KDoc / source explanation | **Observed resolved:** source lane reports remediation plus focused engine/API/gateway XML; the current full backend gate is green (601/5,050, failures/errors 0). |
| R1-21 | Java 21/backend gate rerun provenance | **Observed resolved:** Java 21 preflight and `--rerun-tasks` hardening are exercised by the one-run/no-retry six-root gate (601/5,050, failures/errors 0). |
| R1-22 | Frontend formatting/indentation | **Observed resolved:** current frontend typecheck passed; Vitest JSON reports 132 suites / 288 tests / 0 failures; independent dirty-tree review cleared. |
| R1-23 | Frontend comments / static-asset guard evidence | **Observed resolved:** current frontend typecheck and Vitest JSON (132 suites / 288 tests / 0 failures) passed; independent dirty-tree review cleared. |

The three PHP replay requests are rejected only as claims for this isolation/build-only ticket: no PHP replay was
performed or represented as passed. That does not relax the project-wide parity rule; it records that no
PHP-derived behavior changed. A future T1/parity change must run the appropriate PHP oracle capture/replay.

All 23 Round 1 threads now have a resolved/dispositioned entry above. The later independent Round 3 dirty-tree
re-review separately resolves both Round 3 P2 findings.

## Round 3 P2 resolution

Codex Round 3 reported **two P2 findings**. Both source remediations now exist across three approved source items;
the terminal independent dirty-tree re-review found no blockers, fixes, questions, or nits, so both are resolved
for review disposition. The newly wired CI step still has no observed remote exact-SHA run:

1. **P2 asset-prefix remediation:** `docker-compose.v2-sandbox.yml` now passes `ASSET_PREFIX: /game` to the
   `web-game` build. Local production-mode `ASSET_PREFIX=/game pnpm build` is green, with 62 generated files
   containing `/game/_next/`; no deployment is implied.
2. **P2 regression-contract remediation:** new `tools/ops/v2_sandbox_compose_contract_test.sh` renders the v2
   Compose file and fail-closes unless `web-game.build.args.ASSET_PREFIX == "/game"`. The source lane observed
   red-before/green-after with the same contract. Existing `.github/workflows/ci.yml` now wires that script into
   the `agent-system` job as `Verify v2 sandbox compose contract`; this local wiring validation is not a remote
   CI green result.

The approved canonical scope is exactly the existing `docker-compose.v2-sandbox.yml`, new contract test, and
existing `.github/workflows/ci.yml` invocation; all three are outside T2 and are recorded in the plan §4.0a.
The independent dirty-tree re-review clears both P2s. This artifact does not claim a remote CI result: remote
exact-SHA CI remains pending, while the sole tracked review verdict is `cleared`.

## Evidence actually available

- Historical independent dirty-tree re-review: **cleared with no findings**, fingerprint `3c1b357c…`.
- Historical local immutable-SHA review: completed at `54ead4e70cf5fa7c822bc7fef11a8c42f09eded6`; it is separate
  from external PR review accounting.
- Round 3 independent dirty-tree re-review: terminal **cleared**, no blockers/fixes/questions/nits; it resolves
  both Codex P2 review dispositions but does not observe remote exact-SHA CI.
- Last observed GitHub CI: `agent-system`, `jvm`, `web (gateway)`, and `web (game)` jobs were SUCCESS before the
  current contract-step wiring. Its remote PR CI run is not yet observed. CodeRabbit Round 2 was rate-limited and
  did not produce a completed review result.
- Current Codex Round 3: both P2 findings are resolved by the terminal independent dirty-tree re-review.
- Round 3 source evidence: Compose now supplies `ASSET_PREFIX=/game`; the new Compose contract test observed
  red-before/green-after; `ASSET_PREFIX=/game pnpm build` is green and 62 generated files contain `/game/_next/`.
  `.github/workflows/ci.yml` locally validates as wiring that test into `agent-system`; the remote exact-SHA run is
  unobserved. Together with the terminal dirty-tree re-review, this resolves the review disposition only.
- Current backend log: Java 21 `tools/parity/gate.sh backend` with `--rerun-tasks` over all six roots, one run
  with no retry: `BUILD SUCCESSFUL in 12m 35s`, 35 actionable tasks, 601 suites / 5,050 tests / 0 failures /
  0 errors / 1 skipped. Module detail is in `baseline/a4-backend-gate-xml-summary.txt`; full-log SHA256 is
  `a35ea5cf8352e2fe518daa32dbe95343f92bf62c95dc41a3673e924aa9fcaad1`.
- Historical backend record: 599 suites / 5,023 tests / 0 failures / 0 errors / 1 skipped, non-forced and
  retained only as the historical narrative in `s6-gates-and-baseline.md` §14.
- Historical A4 web log/XML: 54 files / 288 tests; `v2-lab-route.test.tsx` 17 and `middleware.test.ts` 8.
- 2026-08-08 `scripts/agent/verify-changes.sh --run` observed a forced four-root backend subset: 286 suites /
  1,652 tests / 0 failures / 0 errors / 1 skipped. It does not cover the six-root full backend gate.
- The same verifier found frontend dependencies absent (`tsc: command not found`), so frontend typecheck failed
  and tests were not executed. Its Compose failure on missing `JWT_SECRET` was expected fail-closed behavior,
  not a syntax/config pass.
- A later current frontend observation passed typecheck; Vitest JSON reports 132 suites / 288 tests /
  0 failures. Together with the backend run, this resolves the execution evidence; it does not replace remote
  exact-SHA CI or separately authorized release actions.

## Remaining post-review closure

1. Observe/rerun remote exact-SHA PR CI including the new `agent-system` contract step. The cleared local dirty-tree
   review does not supply that remote CI result.
2. Canonical merge-base `:(glob)…/**` T1/T2/config/C1 snapshot and backend/frontend evidence need rerun if their
   tested inputs change during P2 remediation.
3. Commit/push, merge, release, deploy, production observation, and OPENSAM-177 execution remain separately authorized actions.
