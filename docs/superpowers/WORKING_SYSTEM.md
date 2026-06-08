# opensamguk working system

This document is the operating contract for future agents working on opensamguk. It turns the project rules into a repeatable workflow: choose the right skill, read the PHP grand truth, compare behavior, verify with gates, then document the evidence.

## Installed project skills

`skills-lock.json` records the skills installed from [skills.sh](https://skills.sh/) for this repo:

| Skill | Source | Use |
| --- | --- | --- |
| `next-best-practices` | `vercel-labs/next-skills` | Next.js App Router, route handlers, RSC boundaries, self-hosting. |
| `webapp-testing` | `anthropics/skills` | Browser-level web app verification and user-flow testing. |
| `redesign-existing-projects` | `leonxlnx/taste-skill` | Modernizing an existing UI without discarding its legacy shape. |
| `java-spring-boot` | `pluginagentmarketplace/custom-plugin-java` | Spring Boot controller/service wiring and backend conventions. |
| `java-testing` | `pluginagentmarketplace/custom-plugin-java` | JUnit/testing reference only. The skills.sh Gen audit marked it High Risk, so project gates below remain authoritative. |
| `kotlin-spring-boot` | `ashchupliak/dream-team` | Kotlin + Spring Boot idioms. |
| `supabase-postgres-best-practices` | `supabase/agent-skills` | PostgreSQL migration/query discipline. Use with this repo's JDBC-only daemon write rule. |

Restore or refresh these with:

```bash
DISABLE_TELEMETRY=1 npx --yes skills experimental_install
DISABLE_TELEMETRY=1 npx --yes skills update --project -y
```

The installed skill bodies live under `.agents/skills/`, which is intentionally git-ignored. This document and `skills-lock.json` are the committed source of truth.

## Mandatory routing

Start every non-trivial task by classifying the work:

| Task shape | Required first surface |
| --- | --- |
| One command/action parity gap | `parity-close` |
| Batch ready for PR/deploy | `parity-ship` |
| Frontend Next.js implementation | `next-best-practices`, then `redesign-existing-projects` for visual parity/modernization |
| Browser flow verification | `webapp-testing` plus Playwright/browser tooling |
| Backend Kotlin/Spring work | `kotlin-spring-boot`, `java-spring-boot`, then repo architecture rules |
| PostgreSQL/Flyway/JDBC work | `supabase-postgres-best-practices`, then one-daemon-write rule |
| PHP legacy or golden comparison | This document's PHP oracle protocol, then `tools/php-golden/` |

External skills are advisory. If an external skill conflicts with `CLAUDE.md`, `AGENTS.md`, or PHP legacy behavior, the repo rules win.

### Provider-agnostic fallback for parity-close

`parity-close` is a local process skill when available. In fresh providers that only restored `skills-lock.json`, execute the same sequence manually:

1. PHP oracle inventory and real golden capture when needed.
2. Kotlin logic port plus golden/replay test.
3. `tools/parity/gate.sh logic` or a narrower targeted Gradle gate.
4. backend intake/wire/flush integration and tests.
5. frontend submit wiring when the user action exists.
6. cross-agent critique artifact under `docs/superpowers/reviews/`.
7. small Lore commit.

### Provider-agnostic fallback for parity-ship

`parity-ship` is a local process skill when available. In fresh providers, execute:

1. `tools/agent-system/check.py --strict --base origin/main`.
2. `tools/parity/gate.sh backend`.
3. frontend typecheck/build for changed apps.
4. PR creation/update.
5. explicit human go before main merge/deploy.
6. production verification: health, nginx route, and either world-clock advancement or intentional empty-world invariant.

## PHP oracle protocol

PHP is the grand truth. Before changing logic, find and quote the source:

1. Locate the PHP entry point in `legacy/devsam-core`:
   - Commands: `hwe/sammo/Command/General/*.php` or `hwe/sammo/Command/Nation/*.php`
   - Join/possession/founding: search `hwe/` and `sammo/` by handler name and route
   - Frontend shape: `hwe/ts/` Vue first; `hwe/*.php` shell pages second
2. Record the exact source path and line range in a research note or the PR body.
3. Determine the parity dimensions:
   - RNG draw order/count/arguments
   - `Util::round`, `intdiv`, `toInt`, `ceil`
   - Korean log bytes, Josa, markup, and log order
   - DB side effects and ordering
   - insertion order, stable PHP sort behavior
4. Capture real PHP evidence when behavior is not already covered:
   - Use `tools/php-golden/`
   - Commit only real fixtures
   - Never edit a golden to make Kotlin pass
5. Compare Kotlin/Next implementation against the PHP evidence, not against intuition.

If evidence is uncapturable, quarantine it with proof and backlog it. Do not fabricate expected values.

## Implementation loop

Every slice follows this loop:

1. **Inventory**: PHP oracle path, Kotlin/Next target files, existing tests, and current gap doc.
2. **Lock behavior**: add or identify a golden/replay/API/UI regression test before risky edits.
3. **Implement narrowly**: prefer deletion and existing utilities. No hardcoded server/data placeholders unless they are PHP parity constants.
4. **Compare**: run targeted golden/API/UI checks and inspect expected vs actual drift.
5. **Cross-agent critique**: ask at least one independent agent/reviewer to attack the result before commit. For multi-agent work, the peer must check PHP evidence, changed files, tests, and docs without reusing the implementer's conclusion as truth.
6. **Gate**: run `tools/parity/gate.sh` or a narrower equivalent with Java 21 and XML verification.
7. **Document**: update the relevant gap, research, plan, or handoff doc with exact evidence and the critique outcome.
8. **Commit small**: one logical commit with Lore trailers and the repo co-author trailers.

## Cross-agent critique

Every non-trivial slice needs an explicit adversarial review. Use whatever provider is available, but keep the review contract identical:

- **Implementer claim**: what changed, PHP source/evidence used, commands run, known gaps.
- **Critic task**: find the strongest reason this is wrong, stale, hardcoded, ungated, or divergent from PHP.
- **Required checks**: PHP oracle path, RNG/rounding/log/write-order dimensions, test adequacy, docs freshness, production invariants, and hardcoded data.
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
- required docs still mention the working system, PHP oracle, and gate commands.
- code changes include docs or evidence in strict mode.
- behavior changes include tests, golden evidence, or docs in strict mode.
- strict mode requires changed non-trivial work to keep the cross-agent critique rule documented.
- strict mode requires a changed `docs/superpowers/reviews/*.md` critique artifact for non-trivial code/tool changes.
- production compose defaults `SCENARIO_SEED_ENABLED` to false.
- the default gateway server list stays empty.

This is the hook point for all providers. Claude hooks, Codex tools, Gemini agents, local shell scripts, and CI should call the same checker instead of each inventing a separate policy.

## Hardcoding policy

Hardcoded UI/server/game data is a blocker unless it is one of:

- a PHP parity constant captured from `legacy/devsam-core`
- a documented default in `.env.example`
- a test fixture

Server lists are admin-created runtime data. If no servers exist, the gateway must not synthesize or render fake server entries. Login and lobby must hide server maps, logs, and server tabs entirely.

## Production policy

Production is live EC2 and must be treated as a real deployment target:

- Back up before destructive DB operations.
- Do not seed or re-seed unless explicitly intended.
- `game-engine` starts last because it owns memory state.
- Verify nginx routes, API health, and either world-clock advancement or the intentional empty-server invariant.
- For the current admin-created-server target, an empty world is valid: `world_state=0`, `general=0`, `nation=0`, no server list rendered.
