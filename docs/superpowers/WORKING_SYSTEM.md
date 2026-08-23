# opensamguk working system

This document is the operating contract for future agents working on opensamguk. It turns the project rules into a repeatable workflow: choose the right skill, follow the latest approved ADR/spec and current implementation, verify with gates, then document the evidence. Under ADR-LITE-042, PHP and hwe are optional historical/reference inputs, not product authority.

## Installed project skills

`skills-lock.json` records the skills installed from [skills.sh](https://skills.sh/) for this repo:

| Skill | Source | Use |
| --- | --- | --- |
| `vercel-react-best-practices` | `vercel-labs/agent-skills` | React/Next.js rendering, data fetching, bundle, hydration, and runtime performance guidance. |
| `webapp-testing` | `anthropics/skills` | Browser-level web app verification and user-flow testing. |
| `redesign-existing-projects` | `leonxlnx/taste-skill` | Modernizing an existing UI without discarding its legacy shape. |
| `java-spring-boot` | `pluginagentmarketplace/custom-plugin-java` | Spring Boot controller/service wiring and backend conventions. |
| `java-testing` | `pluginagentmarketplace/custom-plugin-java` | JUnit/testing reference only. The skills.sh Gen audit marked it High Risk, so project gates below remain authoritative. |
| `kotlin-spring-boot` | `ashchupliak/dream-team` | Kotlin + Spring Boot idioms. |
| `supabase-postgres-best-practices` | `supabase/agent-skills` | PostgreSQL migration/query discipline. Use with this repo's JDBC-only daemon write rule. |
| `higgsfield-generate` | `higgsfield-ai/skills` | Higgsfield image/video/3D/audio generation via the `higgsfield` CLI. Entry point for the OPENSAM-114 asset lane. |
| `higgsfield-brandkit` | `higgsfield-ai/skills` | Palette/logo/typography brand systems. Concept A tokens (OPENSAM-113 §7) are the input, not a freehand brand. |
| `higgsfield-soul-id` | `higgsfield-ai/skills` | Identity-consistent character models. Candidate for the OPENSAM-98/100 general-portrait set. |
| `higgsfield-product-photoshoot` | `higgsfield-ai/skills` | Product/brand image modes. Not used by this repo's game-asset lane; listed for lock completeness. |
| `higgsfield-marketplace-cards` | `higgsfield-ai/skills` | Marketplace listing imagery. Not used by this repo; listed for lock completeness. |
| `higgsfield-youtube-thumbnail` | `higgsfield-ai/skills` | Thumbnail/cover composition. Not used by this repo; listed for lock completeness. |
| `higgsfield-video-explainer` | `higgsfield-ai/skills` | Narrated explainer video assembly. Not used by this repo; listed for lock completeness. |
| `higgsfield-websites` | `higgsfield-ai/skills` | Higgsfield-hosted site/app/game scaffolding, and its game-art references (spritesheet, tileable texture, rigged 3D). Only the game-art references are relevant here; this repo's frontend stays Next.js and is never scaffolded by this skill. |

Restore or refresh these with:

```bash
scripts/agent/project-skills.sh restore
scripts/agent/project-skills.sh update
```

The `higgsfield-*` skills all require the `higgsfield` CLI (`npm i -g @higgsfield/cli`) plus an authenticated
account (`higgsfield auth login`) and a selected workspace. Generation spends account credits, so any lane that
calls them must state the credit cost before running and must not generate speculatively.

Downloaded external skill bodies are restored locally under `.agents/skills/` from the committed `skills-lock.json`; they are not copied into git. A git-ignored `.agents/.skills-integrity.json` binds the current local bodies to the lock hash and detects local drift on SessionStart. Repo-native process skills, including `opensamguk-working-system`, `loop-engineering`, `opensamguk-php-oracle`, `parity-close`, and `parity-ship`, are tracked alongside the Codex `$os-*` adapters and `$find-project-skill`.

For Codex, trust this project and reload or reopen it after changing the agent surface. A trusted project loads `.codex/config.toml`, the seven roles in `.codex/agents/`, and `.codex/hooks.json`. The `SessionStart` hook restores locked external skills and injects the common `docs/agent/README.md` router context. Hook/config changes are session-scoped, so review and trust the project hooks in `/hooks`, then reload before judging them active.

When the current task needs expertise that is not already available, invoke `$find-project-skill` and follow its fixed sequence: search skills.sh, inspect the exact source with `scripts/agent/project-skills.sh inspect <owner/repository>`, vet its audit signals, install into this project only, read the installed `SKILL.md` completely, update the lock/documentation, and run the agent-system check. Never install a project dependency with the global (`-g`) option.

## Mandatory routing

Start every non-trivial task by classifying the work:

| Task shape | Required first surface |
| --- | --- |
| Explicitly requested frozen-regression/parity maintenance for one command | `parity-close` (historical opt-in only) |
| Explicitly requested frozen-regression/parity batch ready for PR/deploy | `parity-ship` (historical opt-in only) |
| Frontend Next.js implementation | `vercel-react-best-practices`, then `redesign-existing-projects` for visual parity/modernization |
| Browser flow verification | `webapp-testing` plus Playwright/browser tooling |
| Backend Kotlin/Spring work | `kotlin-spring-boot`, `java-spring-boot`, then repo architecture rules |
| PostgreSQL/Flyway/JDBC work | `supabase-postgres-best-practices`, then one-daemon-write rule |
| PHP legacy or frozen-golden comparison | This document's historical comparison protocol, then `tools/php-golden/` |

External skills are advisory. If an external skill conflicts with the latest approved ADR/spec, `CLAUDE.md`, `AGENTS.md`, or the current implementation, the repository product and architecture rules win.

### Provider-agnostic fallback for parity-close (historical opt-in)

`parity-close` is a local process skill for an explicitly requested frozen-regression comparison. It is never a prerequisite for new product work. In fresh providers that only restored `skills-lock.json`, execute the same sequence manually:

1. Opt-in historical PHP evidence inventory and real golden capture when explicitly selected.
2. Kotlin logic port plus golden/replay test.
3. `tools/parity/gate.sh logic` or a narrower targeted Gradle gate.
4. backend intake/wire/flush integration and tests.
5. frontend submit wiring when the user action exists.
6. cross-agent critique artifact under `docs/superpowers/reviews/`.
7. small Lore commit.

### Provider-agnostic fallback for parity-ship (historical opt-in)

`parity-ship` is a local process skill for an explicitly requested frozen-regression batch. It is never a prerequisite for new product work. In fresh providers, execute:

1. `tools/agent-system/check.py --strict --base origin/main`.
2. `tools/parity/gate.sh backend`.
3. frontend typecheck/build for changed apps.
4. PR creation/update.
5. explicit human go before main merge/deploy.
6. production verification: health, nginx route, and either world-clock advancement or intentional empty-world invariant.

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

## Implementation loop

Every slice follows this loop:

1. **Inventory**: approved ADR/spec, current Kotlin/Next implementation, target files, existing tests, and current task/gap doc. Add a PHP path only for an explicitly historical comparison.
2. **Lock behavior**: add or identify a deterministic replay/API/UI regression test before risky edits; preserve affected frozen baselines.
3. **Implement narrowly**: prefer deletion and existing utilities. No hardcoded server/data placeholders unless they are documented product defaults or test fixtures.
4. **Compare**: run targeted replay/API/UI checks and inspect expected vs actual drift. Run a PHP golden comparison only when the task opted into historical maintenance.
5. **Cross-agent critique**: ask at least one independent agent/reviewer to attack the result before commit. For multi-agent work, the peer checks approved product evidence, changed files, tests, and docs without reusing the implementer's conclusion as truth; PHP evidence is required only for the selected historical scope.
6. **Gate**: run the appropriate current gate. The historical filename `tools/parity/gate.sh` is preserved for compatibility and remains the standard backend helper; the name does not make PHP parity mandatory.
7. **Document**: update the relevant gap, research, plan, or handoff doc with exact evidence and the critique outcome.
8. **Commit small**: one logical commit with Lore trailers and the repo co-author trailers.

## Cross-agent critique

Every non-trivial slice needs an explicit adversarial review. Use whatever provider is available, but keep the review contract identical:

- **Implementer claim**: what changed, approved product evidence used, commands run, known gaps, and any explicitly requested historical evidence.
- **Critic task**: find the strongest reason this is wrong, stale, hardcoded, ungated, or divergent from the current approved contract; include the selected PHP baseline only for opted-in historical work.
- **Required checks**: current ADR/spec/implementation agreement, deterministic replay, numerical/log change intent, write/order invariants, test adequacy, docs freshness, production invariants, and hardcoded data. Add a PHP path-and-line check only for opted-in historical comparison.
- **Result**: `cleared`, `fix-required`, or `quarantined-with-proof`.

Do not merge or ship while the latest critique is `fix-required`. If Kimi-backed Claude Code, Codex, Gemini, or another agent is running in parallel, use them as peer critics on disjoint file scopes where possible. The leader owns the final decision and must reconcile conflicting reviews with evidence.

Store the latest review evidence in `docs/superpowers/reviews/<date>-<scope>.md`. Strict CI requires one such artifact for non-trivial code/tool changes.

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

## Provider-agnostic agent check

`tools/agent-system/check.py` is the CI-friendly guard for this working system. It does not call any model provider and does not depend on Claude/Codex-specific hooks.

```bash
# Local working tree check
tools/agent-system/check.py

# PR/CI strict check
tools/agent-system/check.py --strict --base origin/main

# Machine-readable output for any agent/runtime
tools/agent-system/check.py --format json
```

The checker enforces:

- `skills-lock.json` exists and every skill is documented here.
- required docs still mention the working system, ADR-LITE-042 authority boundary, optional historical comparison, and gate commands.
- code changes include docs or evidence in strict mode.
- behavior changes include tests, golden evidence, or docs in strict mode.
- strict mode requires changed non-trivial work to keep the cross-agent critique rule documented.
- strict mode requires a changed `docs/superpowers/reviews/*.md` critique artifact for non-trivial code/tool changes.
- the tracked Codex surface is complete and free of local-only drift: project config, hooks, all seven roles, project process skills, `$os-*`/`$find-project-skill`, and startup scripts must agree.
- production compose defaults `SCENARIO_SEED_ENABLED` to false.
- the default gateway server list stays empty.

This is the hook point for all providers. Claude hooks, Codex tools, Gemini agents, local shell scripts, and CI should call the same checker instead of each inventing a separate policy.

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
