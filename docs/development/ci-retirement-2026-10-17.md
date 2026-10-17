# Existing CI retirement

The existing CI workflows were stopped at the owner’s request on 2026-10-17.
Their definition files are removed from this branch; historical workflow runs, logs,
and test artifacts remain evidence and must not be deleted or relabeled as passing.

## Removed definitions

- `.github/workflows/build-admin-surfaces.yml`
- `.github/workflows/build-game-jvm-images.yml`
- `.github/workflows/build-local-qa-images.yml`
- `.github/workflows/build-pr-candidate-qa-images.yml`
- `.github/workflows/build-web-game-image.yml`
- `.github/workflows/ci-nightly-evidence.yml`
- `.github/workflows/ci.yml`
- `.github/workflows/container-privileges.yml`
- `.github/workflows/g6-turn-order-probe.yml`
- `.github/workflows/map-artifact.yml`
- `.github/workflows/pep-loop-test.yml`
- `.github/workflows/run-pr-candidate-qa.yml`

## Verification while CI is retired

Use an independent cross review of the exact change and execute the affected
local or isolated tests, including relevant regression coverage. Two review opinions
alone are not functional verification. Preserve known failures and NOT_RUN outcomes.
No replacement CI infrastructure is introduced by this change.

## Boundaries

Deployment/CD, operational monitors, alert/report automation, review automation,
GCP power, runtime configuration and database contents are outside this change.
Existing disabled deployment workflows remain disabled. Branch protection and
required checks are unchanged; changing them requires separate authorization.
For the app repository, its six existing required checks can block new commits
while CI is disabled. Do not synthesize success statuses or use an admin bypass.

## Restore

The exact prior definitions are preserved in Git at `1d0533a89d41780d3e8882a12cacc154b79dc1f3`.
Restore only the listed files from that commit in a new reviewed change, then
re-enable the corresponding workflow IDs after authorization. For example:

```sh
git restore --source=1d0533a89d41780d3e8882a12cacc154b79dc1f3 -- .github/workflows/build-admin-surfaces.yml .github/workflows/build-game-jvm-images.yml .github/workflows/build-local-qa-images.yml .github/workflows/build-pr-candidate-qa-images.yml .github/workflows/build-web-game-image.yml .github/workflows/ci-nightly-evidence.yml .github/workflows/ci.yml .github/workflows/container-privileges.yml .github/workflows/g6-turn-order-probe.yml .github/workflows/map-artifact.yml .github/workflows/pep-loop-test.yml .github/workflows/run-pr-candidate-qa.yml
```

Workflow state snapshots, original Git blob identities and cancellation results
are retained in the execution handoff. Re-enabling workflows does not recreate
previously cancelled runs. No run, log, artifact or repository history is deleted.
