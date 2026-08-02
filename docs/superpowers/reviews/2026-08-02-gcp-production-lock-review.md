# GCP production lock review

Date: 2026-08-02
Scope: Cross-repository production mutation serialization in `.github/workflows/deploy.yml`, `.github/workflows/promote-game-server.yml`, `.github/workflows/reset-game-server.yml`, and `scripts/deploy.sh`; base `c4a83c1492103abbe996202fc80d30b78808244c`.
Reviewer: Independent Fable deep architecture and operations review.
Verdict: cleared

## Findings and remediation

The first adversarial pass returned `fix-required` with three findings. The
manual compatibility deploy changed remote files and Docker state outside the
shared host lock. The source deploy accepted a clean local branch ahead of
`origin/main`, and its dirty-tree check hid unexpected non-ignored untracked
files.

The implementation was revised so that the manual deploy streams only the
production compose file and nested nginx configuration through one SSH
session, acquires FD 9 before remote extraction, and retains the lock through
all Compose mutations and image pruning. The source deploy now rejects tracked
or non-ignored untracked drift, performs a guarded fast-forward, and requires
the resulting local HEAD to equal `origin/main` before registry or runtime
mutation. Promote and reset operations acquire the same host-local lock at the
start of their mutation blocks.

The independent re-review confirmed that all three findings were resolved and
returned `cleared`. It found no regression to credential bindings, action pins,
shared image selection, or per-server image-pin preservation.

## Evidence

- All three changed workflow files parse as YAML.
- Every workflow `run:` block passes `bash -n`.
- `scripts/deploy.sh` and its embedded remote shell pass `bash -n`.
- The tar stream contains exactly `docker-compose.production.yml` and
  `infra/nginx/nginx.conf`; the nginx file is not flattened.
- Mutation-order checks confirm that the lock precedes remote directory and
  archive writes, Compose pull/restart operations, and image pruning.
- `git diff --check origin/main` passes.

## Residual risks

- The local macOS validation host does not provide Linux `flock`; ordering was
  verified hermetically with a stub, while the GCP VM independently reports
  util-linux `flock` available.
- GitHub Actions concurrency remains repository-scoped. Safety therefore
  depends on both repositories retaining the same host-local lock contract.
- PR-conversation review rounds and live deployment observation remain
  post-commit gates and are not asserted by this artifact.
