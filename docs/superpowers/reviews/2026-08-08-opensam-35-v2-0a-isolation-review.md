# Review: OPENSAM-35 — V2-0A production isolation gate

Scope: PR #370 (`codex/op-35-v2-0a-final`) Round 1 reconciliation across `.ai/`, `app/`, `infra/`, `tools/`, `web/`, and the owned OPENSAM-35 docs. The terminal independent review inspected
the reviewer-fingerprinted dirty working tree only, not an immutable commit SHA; post-commit exact-SHA review and
PR CI remain required before any release action.

Stage: **PR #370 Round 1 (CodeRabbit, 23 actionable threads) — independent dirty-working-tree re-review**
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
- **Independent terminal re-review:** **cleared, no findings**, reviewer fingerprint `3c1b357c…`. Its scope is the
  exact reviewer-inspected dirty working tree only; it does not substitute a post-commit exact-SHA review or PR CI.
- **PR #370 Round 1 controls now.** It remains open. No merge, release, deploy, production observation, or
  OPENSAM-177 consumer execution occurred. OPENSAM-177 is the separate linked shared account/JWT/profile
  live-integration consumer, not proof that OPENSAM-35 was deployed.

## PR Round 1 ledger (23 threads; all resolved/dispositioned)

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

All 23 Round 1 threads now have a resolved/dispositioned entry above. This closure is the independent reviewer’s
dirty-working-tree result, not a committed-SHA release verdict.

## Evidence actually available

- Independent terminal dirty-working-tree re-review: **cleared with no findings**, fingerprint `3c1b357c…`.
  It covers only that reviewer-inspected uncommitted tree; a post-commit exact-SHA review and PR CI remain residual.
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
  0 failures. Together with the backend run, this resolves the execution evidence; it does not replace
  post-commit exact-SHA review or PR CI.

## Residual post-clearance evidence

1. After a commit is created, obtain a fresh independent review for that exact committed SHA and run PR CI.
2. Canonical merge-base `:(glob)…/**` T1/T2/config/C1 snapshot is recorded as empty output. Rerun it if tested
   source inputs change before the post-commit review.
3. The current backend one-run/no-retry log and frontend direct-pnpm evidence need rerun only if their respective
   tested inputs change.
4. Merge, release, deploy, production observation, and OPENSAM-177 execution remain separately authorized actions.
