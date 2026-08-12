# Independent review — OPENSAM-34 D4 production Go closeout

Scope: OPENSAM-34 D4-31 through D4-35 production observation for spep, recorded by this single documentation artifact; no application, workflow, test, deployment, or shared .ai file is changed.
Verdict: cleared

## Outcome and boundary

The manual [Predeploy Go Check run 31563987678](https://github.com/peppone-choi/opensamguk/actions/runs/31563987678) completed successfully on 2026-08-12. Its terminal result was:

> GO: predeploy read-only checks passed for spep

This closes the OPENSAM-34 D4 observation for that approved run. It is not an
authorization to deploy, change an image pin, migrate data, merge this pull
request, or mutate Jira. This pull request contains documentation only.

## Immutable run record

| Field | Recorded value |
| --- | --- |
| Workflow / event | Predeploy Go Check / workflow_dispatch |
| Run | [31563987678](https://github.com/peppone-choi/opensamguk/actions/runs/31563987678) |
| Checked-out immutable ref | [1305514d904fc6e86f9fd50e99f384f733778a80](https://github.com/peppone-choi/opensamguk/commit/1305514d904fc6e86f9fd50e99f384f733778a80) |
| Expected scenario | scenario_1020 |
| World | 1 |
| Approved disk thresholds | 2 GiB free and 10% free |
| Workflow conclusion | success |

The run metadata independently reports a completed successful
workflow_dispatch run at the stated ref. The retained terminal output contains
all of the following:

| Gate | Recorded pass evidence |
| --- | --- |
| D4-31 | Safe pins and running image references matched the immutable tag. |
| D4-32 | Safe and authoritative scenario codes matched. |
| D4-33 | GitHub Actions Linux/X64 runner metadata, expected containers, and health checks passed. |
| D4-34 | Both root and Docker-root disk headroom met the approved thresholds. |
| D4-35 | Flyway and the current log-entry index checks passed. |

## Scenario provenance without an environment-file claim

The public [server-basic-info response for pep](https://sam.peppone.dev/api/server-basic-info/pep)
returned the title “【역사모드2】 반동탁연합 결성” during this closeout. At the
checked ref, the same title is the title field of the uniquely tracked
[scenario_1020 resource](https://github.com/peppone-choi/opensamguk/blob/1305514d904fc6e86f9fd50e99f384f733778a80/infra/src/main/resources/scenario/scenario_1020.json).
The committed resolver maps a scenario code to the packaged
scenario/code.json resource before reading its title; the relevant source is
[ScenarioTitleResolver](https://github.com/peppone-choi/opensamguk/blob/1305514d904fc6e86f9fd50e99f384f733778a80/app/game-api/src/main/kotlin/opensamguk/gameapi/read/ScenarioTitleResolver.kt).

That title match is an inference from an authoritative public API value to a
unique tracked resource. It is deliberately not described as a direct read of
any production environment file. Separately, D4-32 passed the grader's
safe-environment and database equality checks, which is stronger confirmation
of the expected scenario after the run succeeded.

## Corrected operator input

The earlier [run 31563630946](https://github.com/peppone-choi/opensamguk/actions/runs/31563630946)
used expected scenario scenario_1010 and completed with a D4-32 No-Go. It
checked the same immutable ref as the successful run. The correction to
scenario_1020 is an operator-input correction inferred from the live title
and then confirmed by the succeeding D4-32 check; it is not a product defect.
The prior input mismatch is retained for audit history and is not used as a
failure against the product.

## Non-blocking platform notice

Actions checkout emitted a Node 20 deprecation notice while using the current
runner default. The workflow nonetheless completed successfully, and the
notice did not change a D4 result. It is recorded as a non-blocking maintenance
notice, not as a D4 failure.

## Ownership and review record

The active ownership ledger reserves shared .ai files for the OPENSAM-35
foundation lane. Consequently this closeout adds no current-state, decision,
ownership, or handoff mutation. The only changed path is this task-specific
review artifact.

An independent read-only critique of this artifact and its evidence returned
cleared with no blocking finding. It independently checked the successful
and prior run, the public-title-to-resource inference, authorization
boundaries, the Node notice, the single review anchors, and the Fablize
baseline wording. The reviewer noted only that the public endpoint is mutable;
this artifact limits that response to a closeout-time observation and relies on
the immutable D4-32 run evidence for confirmation.

## Tooling baseline

During read-only documentation and evidence inspection, the agent harness
emitted repeated generic Fablize tool-failure notices. The notice did not name
a failing product command or Actions gate. Recovery was to avoid relying on
that harness signal and to re-establish the evidence with direct public API,
GitHub Actions, immutable-Git, strict-checker, and diff-check commands. This
is isolated as an agent-harness reporting baseline; no retry of that generic
Fablize path is needed for the product conclusion.

## Documentation validation

The changed-path inspection, untracked-file whitespace check,
scripts/agent/verify-changes.sh --run, and
tools/agent-system/check.py --strict --base origin/main all passed for this
documentation-only diff. The rendered Markdown smoke also retained the Go
result and its links. No code or test gate is claimed as newly run because this
change does not modify application, workflow, or test behavior.
