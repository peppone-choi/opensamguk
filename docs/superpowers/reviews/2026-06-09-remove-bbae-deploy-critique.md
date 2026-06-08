# Remove baked bbae deploy critique

Verdict: cleared

Scope: follow-up after PR #44 merge to enforce the current production rule: no baked main/bbae server split, empty server registry by default, admin-created servers first.

Checks:
- Deploy workflow no longer composes `docker-compose.bbae.yml`.
- Deploy workflow no longer starts `bbae-db`, `bbae-redis`, `bbae-engine`, or `bbae-api`.
- `docker-compose.bbae.yml` is removed so it cannot be accidentally merged into production compose.
- `scripts/reseed-prod.sh` is removed because reseeding baked main/bbae worlds conflicts with admin-created empty-server startup.
- `tools/agent-system/check.py` now fails if production deploy references bbae or if `docker-compose.bbae.yml` returns.
- The old bbae plan is retained only as a superseded tombstone, with an explicit do-not-restore warning.

Residual risk:
- A canceled deploy run may already have built images, but bbae startup was blocked by canceling the deploy workflow before the self-hosted deploy job completed.
- Existing remote bbae containers/volumes still need live verification/removal on the EC2 box.
