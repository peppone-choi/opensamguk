# opensamguk working system

Use this reference for the requested implementation, historical comparison, verification or deployment task; it is not an always-read checklist. Product authority is the latest approved ADR/spec and current implementation (ADR-LITE-042). Entry-point rules and safety boundaries live in `../../AGENTS.md` and `../../CLAUDE.md`.

The former skill lock, `$os-*` adapters, Codex project hooks and agent-system checker were removed (ADR-LITE-047). Do not run their restore/check commands or infer that old installation tables describe the current environment. Use only available, relevant skills. The historical-source skill remains at `../../.claude/skills/historical-sources/SKILL.md`.

## Implementation and completion

1. Identify the current scope, affected contracts, files and useful existing tests. Consult the relevant approved ADR/spec; PHP evidence is needed only for an explicitly historical comparison.
2. Implement in the authorized worktree. Preserve deterministic replay, flush/order invariants and frozen baselines. No fake server/data placeholders except documented product defaults or test fixtures.
3. Run checks appropriate to the changed behavior. Fix failures caused by the change and rerun affected checks; do not stop at first implementation when verification is part of the request. Document-only edits normally need link/format/diff checks rather than a full application build.
4. Before merging/deploying changes to replay, flush, data integrity or production contracts, obtain independent review of the evidence, tests and documentation. Record `cleared`, `fix-required` or `quarantined-with-proof`; `fix-required` blocks merge/deploy. Review-only requests remain read-only.
5. Update affected documentation and the existing task report with actual results, skipped/unavailable checks and risks. A test exit code alone is not evidence that skipped integration tests passed. No blanket claim that all local tests are isolated from production.

Commit, push, PR creation, merge, deployment, server promotion and data deletion/re-seeding require authorization covering that action and target. Existing authorization remains valid within its scope. Finish safe local work while separating actions that need further approval. Credit-consuming generation must state its cost and must not run speculatively.

## Historical PHP comparison protocol (opt-in)

Use this protocol only when the task explicitly asks to investigate a frozen-baseline regression, maintain a historical parity surface, or explain legacy behavior. PHP and hwe evidence can establish what those legacy systems did; they do not decide new opensamguk product behavior. Before making a historical behavior claim:

1. Locate the PHP entry point in `legacy/devsam-core`:
   - Commands: `hwe/sammo/Command/General/*.php` or `hwe/sammo/Command/Nation/*.php`
   - Join/possession/founding: search `hwe/` and `sammo/` by handler name and route
   - Frontend shape: `hwe/ts/` Vue first; `hwe/*.php` shell pages second
2. Record the exact source path and line range in a research note or the PR body.
3. Determine the comparison dimensions relevant to the frozen baseline:
   - RNG draw order/count/arguments
   - `Util::round`, `intdiv`, `toInt`, `ceil`
   - Korean log bytes, Josa, markup, and log order
   - DB side effects and ordering
   - insertion order, stable PHP sort behavior
4. Capture real PHP evidence when behavior is not already covered:
   - Use `tools/php-golden/`
   - Commit only real fixtures
   - Never edit a golden to make Kotlin pass
5. Compare Kotlin/Next behavior against the captured legacy evidence only for the explicitly selected historical scope. For new design, compare against the approved ADR/spec and current implementation instead.

If evidence is uncapturable, quarantine it with proof and backlog it. Do not fabricate expected values.

## Gate command

Use the committed gate helper for standard backend proof:

```bash
tools/parity/gate.sh backend
```

Useful narrower gates:

```bash
tools/parity/gate.sh logic
tools/parity/gate.sh engine
tools/parity/gate.sh api
```

Frontend proof remains per app:

```bash
cd web/gateway && pnpm typecheck
cd web/game && pnpm typecheck && pnpm test
```

Do not claim success from Gradle exit code alone. Confirm `BUILD SUCCESSFUL` in output and `failures="0" errors="0"` in XML.

## Hardcoding policy

Hardcoded UI/server/game data is a blocker unless it is one of:

- a documented product constant, or a frozen fixture retained for historical regression maintenance
- a documented default in `.env.example`
- a test fixture

Server lists are admin-created runtime data. If no servers exist, the gateway must not synthesize or render fake server entries. Login and lobby must hide server maps, logs, and server tabs entirely.

## Production policy

Production is the shared stack on GCP Compute Engine `e2-standard-2` and must be treated as a real deployment target. `.github/workflows/deploy.yml` builds and pushes GHCR images on GitHub-hosted runners; its VM-local `gcp-prod` self-hosted runner synchronizes the `opensamguk-docker` control repository before updating the shared stack.

- Back up before destructive DB operations.
- Do not seed or re-seed unless explicitly intended.
- Refresh the deployer, then shared dependencies and upstreams from `opensamguk-docker`; recreate nginx last so it resolves current upstream container addresses.
- A shared-stack refresh must preserve every `servers/<id>.env` `IMAGE_TAG` and `WEB_GAME_TAG`. Game-server promotion is a separately approved control-repository operation, not an implicit `main` deployment effect.
- This repository's `docker-compose.production.yml` and `scripts/deploy.sh` are compatibility-only; use the current `opensamguk-docker` shared-stack flow.
- Verify nginx routes, API health, and either world-clock advancement or the intentional empty-server invariant.
- For the current admin-created-server target, an empty world is valid: `world_state=0`, `general=0`, `nation=0`, no server list rendered.
