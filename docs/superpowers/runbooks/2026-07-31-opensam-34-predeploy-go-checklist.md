# OPENSAM-34 predeploy Go checklist (manual-only)

## Status

The local predeploy grader is ready. This is **not** a production Go decision:
Jira remains `할 일`, and the actual D4-31~35 observation is blocked/incomplete
because the `ec2-prod` self-hosted runner was observed offline in both this
repository and the sibling `opensamguk-docker` control repository.

Do not infer a production pass from the hermetic local contract. No production
workflow dispatch, EC2 access, `.env*` or secret access, commit, push, PR,
merge, deploy, or Jira mutation occurred for this closeout.

## Approval boundary

Run `.github/workflows/predeploy-go-check.yml` only after both conditions hold:

1. A human has resumed the EC2 runner and confirmed that it is available.
2. The user has explicitly approved this exact production observation and its
   operator-supplied input values.

The workflow is manual-only (`workflow_dispatch`) and targets
`[self-hosted, Linux, X64, ec2-prod]`. It must not be substituted with a local
shell run or a guessed server value. A failing or unavailable runner is a
No-Go/blocked result; it is never a reason to relax an input or a check.

## Required manual workflow inputs

Every input is required and intentionally has no default. The operator, not
this runbook or an agent, chooses the approved values.

| Workflow input | Required value | Fail-closed rule |
|---|---|---|
| `server` | Target multi-server identifier, for example `s1`. | Must use the `s<number>` server identifier form. |
| `expected_tag` | Expected immutable tag/ref: a full 40-hex commit SHA or canonical `vX.Y.Z` tag (with an optional prerelease/build suffix). | `latest`, a branch, a partial SHA, a newline, and any other raw ref are rejected. The workflow validates it before checkout; semver becomes `refs/tags/<tag>`, checkout uses only that validated output, and credentials are not persisted. |
| `expected_scenario_code` | Expected canonical scenario code, `scenario_<number>`. | Non-canonical forms such as a leading-zero nonzero number are rejected. |
| `world_id` | Positive ID of the target `world_state` row. | Must be a positive signed-64-bit decimal integer. |
| `min_free_gib` | Operator-approved minimum free capacity in GiB. | Must be a positive signed-64-bit decimal integer. |
| `min_free_percent` | Operator-approved minimum free capacity percentage. | Must be a decimal integer from 1 through 100. |

## What the grader observes

The grader is read-only and returns `NO-GO:` on any missing, ambiguous, or
mismatching observation. It prints only result labels, not unrelated server
environment values or secrets.

| Jira check | Read-only observation required for an actual pass |
|---|---|
| D4-31 image tag | Safe `IMAGE_TAG` and `WEB_GAME_TAG` pins match `expected_tag`; running game-api, game-engine, and web-game image references also match the component tag. |
| D4-32 seed code | Safe `SCENARIO_CODE`, `world_state.scenario_code`, and `ng_games.env.scenario_code` all equal `expected_scenario_code` for exactly the selected positive `world_id`; the game/server relation is checked through the canonical `world_state.meta ->> 'serverId'` relation. |
| D4-33 runner health | GitHub Actions Linux/X64 metadata, the five expected containers, game-api/game-engine actuator JSON, web-game, PostgreSQL, and Redis health checks all pass. |
| D4-34 disk headroom | `df -Pk` observes both `/` and Docker's reported root directory; both free GiB and free percent meet the operator-approved thresholds. |
| D4-35 database/Flyway | Read-only SQL finds no failed Flyway row, the latest successful numeric migration equals the checked-out migration set, V29 succeeds exactly once, and `log_entry_year_month_idx` is valid, ready, and has the current world-scoped definition. |

Docker access is limited to `inspect`, `exec`, and `info`; PostgreSQL uses a
`default_transaction_read_only=on` session. The hermetic contract rejects a
mutating Docker verb, mutating SQL, unsafe health-check form, noncanonical
server relation, malformed `df` output, redaction leakage, invalid input, and
every covered D4 failure branch.

## Local readiness evidence

- `bash -n` passed for both predeploy shell scripts, and the hermetic contract
  passed (`PASS: predeploy-go-check hermetic contract`).
- The workflow YAML parse passed, and scoped untracked-file whitespace checking
  passed.
- Fresh migration evidence: V29 `2/0/0/0` and V32 `9/0/0/0`, with
  `BUILD SUCCESSFUL in 2m 2s`.
- The final independent re-review cleared the local grader. Its first
  `fix-required` findings and their remediations are retained in the paired
  review artifact.
- `scripts/agent/verify-changes.sh` classification ran. Its `--run` form was
  not rerun for OPENSAM-34, so no whole-worktree execution claim is made here.

## Result handling after authorization

After an approved dispatch, retain only redacted output and the exact input
identifiers needed to audit the result. `GO: predeploy read-only checks passed`
means the five observations passed for that approved run; it is not by itself a
deploy, pin-change, migration, or merge authorization. Any `NO-GO:` result,
runner outage, or absent approved run leaves D4-31~35 blocked/incomplete.

For a future PR, ADR-LITE-026 still applies: mention the review agent in the
PR conversation for three separate rounds, address every finding, reverify
each round, and merge only after explicit human approval.
