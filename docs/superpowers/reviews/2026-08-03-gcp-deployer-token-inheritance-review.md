# GCP deployer token inheritance review

Scope: `.github/workflows/deploy.yml` at `77908853d244f28b6d0afb57b0fad3e7bc0100ce`, compared with `2a3dcb984503be2f8910d2b4b52bdbdc69539736`.
- Reviewer: independent `fable-implementer` read-only review.

## Evidence

- The diff is limited to one workflow file with 3 additions and 4 removals; `git diff --check` passed.
- Both host environment-file credential parsing and host `docker exec -e` credential injection were removed.
- The shared and server compatibility probes use only the deployer container's configured environment.
- The public server ID is passed as a quoted positional argument; the inner credential expansion remains literal until the container shell executes it.
- Workflow YAML parsed successfully and all 9 `run` blocks passed `bash -n`.
- The fail-closed pipe retains `set -euo pipefail`; non-OK and transport failures block deployment.
- A live read-only probe using the container-inherited credential returned HTTP 200 without exposing the credential.

## Findings

No blocker, major, minor, or unresolved question was found. The generic Fablize wrapper warning occurred despite successful child-command exit codes and was isolated as an external tooling anomaly.

## Verdict

Verdict: cleared
