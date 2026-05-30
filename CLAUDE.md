# CLAUDE.md — opensamguk

Guidance for Claude Code (and humans) working in this repo. These rules are **load-bearing**: they encode hard-won parity discipline. Violating them silently breaks the golden gates.

---

## What this repo is

**opensamguk** is a faithful migration of the PHP game **devsam/core** (삼국지 모의전투 HiDCHe / 삼모) to **Kotlin/Spring + Next.js + PostgreSQL + Redis + nginx**, built on a **memory-centric CQRS** architecture.

- **`legacy/devsam-core` (PHP) is GRAND TRUTH.** Every behavior — RNG draws, rounding, log strings, side-effect order — must match it byte-for-byte. It is the oracle, never "improved."
- **`legacy/devsam-core2026` (TypeScript)** is a *second* structural oracle only. **PHP wins every divergence** (collapse experience, findNextCapital BFS-vs-Euclidean, arsort-vs-V8 sort, append-additive ordering, `Math.round`-vs-half-away).
- `legacy/` is **git-ignored** and never committed.
- Program design + roadmap: `docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md`.

The repo **MUST stay PRIVATE** until a Koei-IP review clears it. Do not commit Koei-owned assets/IP, secrets, or credentials.

---

## Architecture (memory-centric CQRS)

```
api  ──Redis(XADD)──▶  game-engine daemon  ──JDBC batch flush──▶  PostgreSQL
 ▲                      (InMemoryTurnWorld = source of truth)            │
 └──────────────── turnCompleted SSE ◀── ChangeRecorder dirty/created/deleted
```

Modules (`settings.gradle`):
- **`common`** — RNG/log kernel: `rng/LiteHashDrbg` (byte-exact SHA-512 DRBG) + `rng/RandUtil` + `rng/SeedSerializer`, `util/PhpRound`, `log/*` (Josa/ConvertLog/tokens), `constants/GameConst`+`GameUnitDetail`.
- **`logic`** — pure game logic: `stats/ActionPipeline` (multi-source stat fold + `getStatValue` + calc-cache), `actions/*` commands + `CommandRegistry`, `war/*` battle engine, `event/*` DSL, `tick/*`, domain entities. No Spring, no DB.
- **`infra`** — `JdbcFlushExecutor` (**JDBC-only** flush + delete/tombstone delta + row mappers), Flyway migrations `db/migration/V*.sql`, Redis.
- **`app/gateway-api`** (`:8080`) auth/profile · **`app/game-api`** (`:8081`) read+precheck+intake+SSE · **`app/game-engine`** (`:8082`) turn daemon (`InMemoryTurnWorld`+`ChangeRecorder`+`MonthlyPipeline`+`TurnRunService`).
- **`web/gateway`** (`:3000`) · **`web/game`** (`:3001`) — Next.js.

### The ONE daemon-write rule (architecture test-enforced)
**The game-engine daemon NEVER uses a JPA `EntityManager` for writes.** JPA is read/precheck only (game-api). Daemon writes go **only** through `ChangeRecorder` → `JdbcFlushExecutor` JDBC batch. (Two competing dirty-truths — JPA dirty-checking + change-recorder — would silently diverge.)

---

## Parity discipline (NON-NEGOTIABLE)

1. **RNG draw-for-draw.** All randomness is `RandUtil(LiteHashDrbg(seed))`. The draw **order, count, and method args** are parity targets, not just the result. In battle, the WHOLE fight runs on **ONE** `RandUtil(warSeed)` built once in `processWar()` and threaded by reference — never re-seeded mid-stream. A single extra/missing/reordered draw desyncs everything downstream.
2. **Rounding.** `Util::round` / `Util::setRound` = **half-AWAY-from-zero** → use `PhpRound`. NEVER `Math.round` (half-up) or `kotlin.math.round` (half-to-even). `Util::toInt`/`intdiv` = truncate-toward-zero. Damage-loop clamp = `ceil()` (distinct from round).
3. **Korean log byte-parity.** Log strings (`Josa` 조사, color/tag markup, prefixes, `<Y1>【name】</> <C>HP (-dead)</>`, 진격·퇴각·패퇴·전멸·분쟁·정복 …) must match exactly. **Log order = execution order** → execution drift breaks the log gate.
4. **Flush delta, not inline writes.** Mutations are recorded as `created`/`dirty`/`deleted` (tombstone) on `ChangeRecorder` and flushed in bulk. Resolvers (e.g. ConquerCity) write **only** delta — no inline DB write.
5. **Faithful port, never fabricate.** Golden numbers/logs/seeds come **only** from a real PHP capture (`tools/php-golden`, Docker). If a value can't be captured faithfully, **quarantine it with proof** (sibling-code-path byte-match) and document in the phase backlog — do **not** invent it, and do **not** weaken a test or edit a golden to make it pass. On a mismatch: fix the Kotlin impl, not the golden.
6. **Insertion order matters.** jsonb / conflict-map / trigger-caller keys preserve insertion order (`LinkedHashMap`), never re-keyed by id. PHP 8.0+ sorts are stable — do not add a non-stable secondary comparator.

---

## Build & test

- **Java 21 LTS is required.** Gradle 8.12 fails to parse Java 25. Always:
  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test
  ```
  Run `./gradlew` from the **repo root** (multi-module).
- **Verify builds by OUTPUT TAIL, not exit code.** A `task-notification` exit 0 is unreliable here (the host routes gradle through a context-mode wrapper). Pipe `... 2>&1 | tail -40` and grep for `BUILD SUCCESSFUL` + the test counts (or read `**/build/test-results/test/*.xml`).
- **Testcontainers on macOS** needs `api.version=1.44` + `DOCKER_CONTEXT=default` + Ryuk disabled (already wired in `tasks.test`). Docker-unavailable ⇒ IT **skipped**, not failed.
- Full check: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test`.
- Smoke the Docker stack: `./tools/smoke.sh`. Frontend: `cd web/gateway && corepack pnpm dev`.

---

## How phases are built (the workflow)

Each phase runs one cycle: **spec → plan → adversarial review → execute → gate**.
- Plans live in `docs/superpowers/plans/`, research in `docs/superpowers/research/`.
- **Foundation-first decomposition.** Every *shared extension point* (a registry, a base class, a stat-key enum, a pipeline hook, a phase-machine skeleton) is built in a **Tier-0 foundation wave** that later families only **consume**. Parallel worktree families must be **disjoint** — never let two co-widen the same file.
  - **Lesson (P2/P3/P4):** if a *leaf* area needs to widen a base class's interface, that interface belongs in a foundation, not the leaf. Co-widening a shared file across parallel worktrees ⇒ merge conflict. Cross-area shared artifacts ⇒ build sequentially (creator-then-consumer), never in parallel.
- The phase **gate** is a real PHP golden replayed draw-for-draw. A phase is not "done" until its gate is green (or its gaps are quarantined with proof + logged to the phase backlog).
- One logical commit per task. **Every commit message ends with:**
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

### Golden capture harness — `tools/php-golden/`
The PROVEN Docker capture (MariaDB 11.4 + `php:8.3-cli`, scenario `1010` = 174 generals, **not** empty scenario_0). Clone the existing `capture_*.php` + `_boot.php` + `manifest*.json`. Quirks (save ~30 min): `_boot.php` binds via `DB::db()` (not `setSelfConnInfo`); `j_install.php` is called twice; install is **not** idempotent (fresh DB per run); golden dumps must be byte-identical across two runs.

---

## Repo conventions

- **Branch stack**, one per phase: `p0a-foundation-scaffold → p0b-parity-kernel → p1-vertical-slice → p2-commands-constraints → p3-monthly-tick → p4-battle-engine → …`. Each branches off the previous; PRs are stacked (base = parent branch) for clean incremental diffs.
- Git-ignored: `legacy/`, `build/`, `node_modules/`, `.env*`, `.workflow-*.mjs`, `.claude-tmp/`.
- Use the **code-review-graph** MCP tools (`detect_changes`, `query_graph`, `get_impact_radius`, `semantic_search_nodes`) BEFORE Grep/Glob when exploring — faster + structural context.

---

## Skills & commands

Invoke a skill with the `Skill` tool (or type `/<name>`). Use the **process** skill first (it decides *how* to approach), then implementation skills.

### gstack
**All web browsing goes through `/browse`** — never `mcp__claude-in-chrome__*` tools.

| Group | Commands |
| --- | --- |
| Plan / review | `/autoplan` `/plan-ceo-review` `/plan-eng-review` `/plan-design-review` `/plan-devex-review` `/design-consultation` `/design-shotgun` `/design-html` `/design-review` `/devex-review` `/review` `/cso` `/office-hours` |
| Ship / deploy | `/ship` `/land-and-deploy` `/canary` `/setup-deploy` `/document-release` |
| QA / debug | `/qa` `/qa-only` `/investigate` `/benchmark` |
| Browser | `/browse` `/connect-chrome` `/setup-browser-cookies` |
| Repo safety | `/careful` `/freeze` `/unfreeze` `/guard` |
| Meta | `/retro` `/learn` `/setup-gbrain` `/gstack-upgrade` `/codex` |

### Compound engineering
Loop: **brainstorm → plan → work → review → compound** (record learnings so each unit makes the next easier; ~80% plan/review, 20% execute).

| Command | Purpose |
| --- | --- |
| `/ce-strategy` | Define product goal/approach/target → `STRATEGY.md` |
| `/ce-ideate` | (optional) vet a big idea |
| `/ce-brainstorm` | Define feature requirements interactively |
| `/ce-plan` | Brainstorm → implementation plan |
| `/ce-work` | Execute the plan + track work |
| `/ce-code-review` | Multi-agent pre-merge review |
| `/ce-debug` | Reproduce → root cause → fix |
| `/ce-compound` | Record learnings to improve future work |

Plus the `ce-*` review subagents (spawn via the `Agent` tool): always-on `ce-correctness-reviewer` / `ce-maintainability-reviewer` / `ce-testing-reviewer` / `ce-project-standards-reviewer`; conditional `ce-security-reviewer` / `ce-performance-reviewer` / `ce-reliability-reviewer` / `ce-api-contract-reviewer` / `ce-data-migrations-reviewer` / language-specific `ce-kieran-{rails,typescript,python}-reviewer` / `ce-dhh-rails-reviewer`; researchers `ce-best-practices-researcher` / `ce-framework-docs-researcher` / `ce-web-researcher`; plan-doc reviewers `ce-feasibility-reviewer` / `ce-coherence-reviewer` / `ce-scope-guardian-reviewer` / `ce-security-lens-reviewer` / `ce-design-lens-reviewer` / `ce-product-lens-reviewer`.

### Harness
Meta tool: decompose a domain description into a specialized agent team + skills under `.claude/agents/` and `.claude/skills/` (6 patterns: pipeline / fan-out-fan-in / expert-pool / generate-verify / supervisor / hierarchical). Trigger: "하네스 구성해줘".

### superpowers
The skill system itself. Key execution sub-skill: **`superpowers:subagent-driven-development`** — TDD red→green, one commit per task. Invoke via the `Skill` tool; follow it exactly (rigid skill).

### graphify
Any input → knowledge graph. Trigger: `/graphify`.

### code-review-graph (MCP)
Persistent structural graph of this codebase. **Use BEFORE Grep/Glob/Read** when exploring: `detect_changes` (risk-scored review), `query_graph` (callers/callees/imports/tests), `get_impact_radius`, `get_affected_flows`, `semantic_search_nodes`, `get_review_context`, `get_architecture_overview`. Falls back to file scanning only when the graph doesn't cover the need.

---

## Roadmap status

`P0→P1→P2→P3→P4→P5→P6→P7→P8` (branch: `P7 ← {P2,P6}`, parallelizable with P3/P4/P5).

- ✅ **P0-A** scaffold · ✅ **P0-B** parity kernel · ✅ **P1** vertical slice · ✅ **P2** ~35 commands + constraints · ✅ **P3** monthly tick — all gate-closed, ~1235 tests.
- 🔧 **P4** battle engine (`processWar_NG` + triggers + WarUnit + city-conflict + ConquerCity + battle items/specialties) — code complete (1527 tests); **G1 battle/conquest draw-for-draw gate in progress**.
- ⬜ **P5** NPC AI · **P6** diplomacy/auction/inheritance · **P7** read API + Next.js + SSE · **P8** parity harness + gateway orchestration + production deploy (AWS EC2 t3.large, LLM-free, no external API deps).
