# AGENTS.md — opensamguk

This file is the canonical onboarding guide for AI coding agents working on **opensamguk**.
Read this before touching any code. The project has strict parity and architectural rules that are easy to violate silently.

---

## Project overview

**opensamguk** is a faithful migration of the PHP game **devsam/core** (삼국지 모의전투 HiDCHe / 삼모) to a **memory-centric CQRS** stack: **Kotlin/Spring Boot + Next.js + PostgreSQL + Redis + nginx**.

- **`legacy/devsam-core` (PHP) = GRAND TRUTH.** Every behavior (RNG draw order, rounding mode, Korean log strings, side-effect order) must match byte-for-byte. The PHP source is the oracle; never "improve" it.
- **`legacy/devsam-core2026` (TypeScript)** = a secondary structural oracle only. PHP wins every divergence.
- `legacy/` is **git-ignored**, never committed.
- The repo is **PRIVATE** until a Koei-IP review clears it. No Koei-owned assets, secrets, or credentials in commits.

---

## Technology stack

| Layer | Tech | Version |
|-------|------|---------|
| Language | Kotlin | 2.1.0 |
| Framework | Spring Boot | 3.4.1 |
| JVM | Java | 21 LTS |
| Build | Gradle (Kotlin DSL) | 8.12 |
| Frontend | Next.js / React / TypeScript | 15.1.3 / 19 / 5.7.2 |
| Package manager | pnpm (via corepack) | — |
| Database | PostgreSQL | 16 |
| Migrations | Flyway | — |
| Cache/Stream | Redis | 7 |
| Reverse proxy | nginx | 1.27 |
| Testing | JUnit 5 + kotlin.test + Testcontainers | 1.20.4 |

---

## Architecture

```
api ──Redis(XADD)──▶ game-engine daemon ──JDBC batch flush──▶ PostgreSQL
 ▲                   (InMemoryTurnWorld = source of truth)         │
 └──────────── turnCompleted SSE ◀── ChangeRecorder dirty/created/deleted
```

The **game-engine daemon** holds the authoritative world in memory. Mutations are recorded as `created`/`dirty`/`deleted` (tombstone) on `ChangeRecorder` and flushed in bulk via `JdbcFlushExecutor`. **Writes never go through JPA.** JPA is read/precheck only (used in `game-api`). Having two competing dirty-truths (JPA dirty-checking + change-recorder) would silently diverge.

### Module map

Modules are declared in `settings.gradle.kts`:

| Module | Type | Port | Responsibility |
|--------|------|------|----------------|
| `common` | library | — | RNG kernel (`LiteHashDrbg`), `PhpRound`, `Josa`, log tokens, wire formats, constants |
| `logic` | library | — | Pure game logic (no Spring/DB): actions, commands, battle engine, AI, event DSL, ticks, stat pipelines |
| `infra` | library | — | JDBC flush + Flyway migrations + Redis + row mappers + world-state repository |
| `app:gateway-api` | Boot app | `:8080` | Auth and profile orchestration |
| `app:game-api` | Boot app | `:8081` | Read + precheck + mutation intake + SSE relay |
| `app:game-engine` | Boot app | `:8082` | Turn daemon: `InMemoryTurnWorld`, `ChangeRecorder`, `MonthlyPipeline`, `TurnRunService` |
| `web:gateway` | Next.js | `:3000` | Gateway frontend |
| `web:game` | Next.js | `:3001` | Game frontend |

---

## Build and test commands

**Prerequisites:** JDK 21 (Gradle 8.12 fails on newer JDKs), Docker, Node 20 + pnpm.

Always run Gradle from the **repo root** with `JAVA_HOME` pinned to 21:

```bash
# Compile + run all tests (unit + integration; Testcontainers skip cleanly if Docker is unavailable)
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build

# Run a single module's tests
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test

# Full JVM check (recommended before commits)
./gradlew :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test

# Docker smoke test (builds images, boots full stack, asserts health)
./tools/smoke.sh

# Frontend development
 cd web/gateway && corepack pnpm dev   # :3000
cd web/game    && corepack pnpm dev   # :3001
```

> **Important:** Verify builds by the **output tail** (`... 2>&1 | tail -40`, grep `BUILD SUCCESSFUL` + test counts), not the exit code. The host may route Gradle through a wrapper that returns 0 regardless. Use `--rerun-tasks` when you suspect UP-TO-DATE false-greens. Test results are also written to `**/build/test-results/test/*.xml`.

### Testcontainers on macOS

The `infra`, `app:game-api`, and `app:game-engine` modules configure Testcontainers with these system properties / env vars in `tasks.test`:
- `api.version=1.44`
- `DOCKER_HOST=unix:///var/run/docker.sock`
- `DOCKER_CONTEXT=default`
- `TESTCONTAINERS_RYUK_DISABLED=true`

Docker-unavailable ⇒ integration tests **skip**, not fail.

---

## Code style guidelines

- **Kotlin official code style** (`kotlin.code.style=official` in `gradle.properties`).
- **Indent:** 4 spaces for `.kt`/`.kts`, 2 spaces for `.ts`/`.tsx`/`.json`/`.yml`.
- **Encoding:** UTF-8, LF line endings, final newline required (see `.editorconfig`).
- **Package naming:** all lowercase under `opensamguk.<module>`.
- **File naming:** PascalCase for classes/objects, camelCase for functions/variables.
- **Comments:** Written in English. Game-content strings are in Korean.
- **Test naming:** Backtick-quoted descriptive names (e.g., `` `same seed yields identical draw streams` ``).

---

## Testing instructions

### Test structure

- `common/src/test` — unit tests for RNG, rounding, Josa, log formatting, wire serialization.
- `logic/src/test` — unit tests for commands, battle engine, AI, events, stats. Heavy use of seeded `RandUtil` + golden fixtures.
- `infra/src/test` — integration tests for JDBC flush, row mappers, Flyway migrations (Testcontainers).
- `app/game-api/src/test` — integration tests for controllers, precheck service, SSE relay, read repositories (Testcontainers).
- `app/game-engine/src/test` — integration + E2E tests for the turn daemon, flush convergence, cross-call-site precheck agreement, AI selection gates (Testcontainers).
- `app/gateway-api/src/test` — minimal Spring Boot context test.

### Golden fixtures

Golden numbers/logs/seeds come **only** from real PHP captures in `tools/php-golden/` (Docker harness: MariaDB 11.4 + `php:8.3-cli`, scenario `1010`). Golden files live under `logic/src/test/resources/golden/` and `common/src/test/resources/golden/`. They are read-only consumption targets; **never copy them** into another module's resources.

If a value cannot be captured faithfully, **quarantine it with proof** (sibling-code-path byte-match) and log it to the phase backlog. Do **not** invent it, do **not** weaken a test, do **not** edit a golden.

### Architecture tests

- `DaemonNoEntityManagerTest` / `InfraNoEntityManagerTest` — enforce the ONE daemon-write rule (no JPA `EntityManager` writes in `game-engine`).
- `PrecheckFullCrossCallSiteTest` — proves `game-api` precheck and `game-engine` reserved-turn evaluation agree on Allow/Deny + reason string.

---

## Security considerations

- The repo is **private**; do not expose it or include credentials in commits.
- `.env*` files are git-ignored. Use `.env.example` as a template.
- No external API dependencies in the runtime; the stack is fully self-hosted (LLM-free).
- Deployment target is AWS EC2 t3.large; no cloud-native secrets manager is assumed.

---

## Development conventions

### Phase-based workflow

Development proceeds in phases: `P0 → P1 → P2 → P3 → P4 → P5 → P6 → P7 → P8`.
Each phase = **spec → plan → adversarial review → execute → gate**.

- Plans live in `docs/superpowers/plans/`, research in `docs/superpowers/research/`.
- **Foundation-first:** every shared extension point (registry, base class, stat-key enum, pipeline hook) is built in a **Tier-0 foundation wave** that later families only **consume**.
- Parallel worktree families must be **disjoint** — never co-widen the same file.

### Branch stack

One branch per phase, branching off the previous:
```
p0a-foundation-scaffold → p0b-parity-kernel → p1-vertical-slice → p2-commands-constraints → p3-monthly-tick → p4-battle-engine → p5-npc-ai → …
```
PRs are stacked (base = parent phase) for clean diffs.

### Commit convention

One logical commit per task. Every commit message ends with:
```
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

## Deployment process

### Local full stack

```bash
docker compose up -d --build
```

Services (defined in `docker-compose.yml`):
- `postgres:16-alpine` (:5432)
- `redis:7-alpine` (:6379)
- `gateway-api` (:8080)
- `game-api` (:8081)
- `game-engine` (:8082)
- `web-gateway` (:3000)
- `web-game` (:3001)
- `nginx:1.27-alpine` (:80)

nginx routes:
- `/api/gateway/` → gateway-api
- `/api/game/` → game-api
- `/game/` → web-game
- `/` → web-gateway
- `/api/game/sse/` → game-api with buffering disabled for realtime frames

### Docker images

Backend images are multi-stage: `gradle:8.12-jdk21` build → `eclipse-temurin:21-jre` runtime.
Frontend images are multi-stage: `node:20-alpine` build (pnpm + `next build`) → `node:20-alpine` runtime (`next start` with standalone output).

### CI

`.github/workflows/ci.yml`:
- `jvm` job: checkout → setup-java 21 → `./gradlew build --no-daemon` → surface test XMLs.
- `web` matrix job: checkout → setup-node 20 → corepack enable → `corepack pnpm install --no-frozen-lockfile` → `corepack pnpm build` for `web/gateway` and `web/game`.

---

## Parity discipline (NON-NEGOTIABLE)

1. **RNG draw-for-draw.** All randomness is `RandUtil(LiteHashDrbg(seed))`. The draw **order, count, and method args** are parity targets. In battle, the WHOLE fight runs on **ONE** `RandUtil(warSeed)` built once in `processWar()` and threaded by reference — never re-seeded mid-stream.
2. **Rounding.** `Util::round`/`setRound` = **half-AWAY-from-zero** → use `PhpRound` (negative-scale `phpRound(v,-2)`, NEVER `phpRound(v/100)*100`). NEVER `Math.round` (half-up) or `kotlin.math.round` (half-to-even). `Util::toInt`/`intdiv` = truncate-toward-zero.
3. **Korean log byte-parity.** Log strings (`Josa` 조사, color/tag markup, prefixes, `<Y1>【name】</> <C>HP (-dead)</>`, 진격·퇴각·패퇴·전멸·분쟁·정복 …) must match exactly. Log order = execution order.
4. **Flush delta, not inline writes.** Mutations are recorded on `ChangeRecorder` and flushed in bulk. Resolvers write **only** delta.
5. **Faithful port, never fabricate.** Fix the Kotlin impl on mismatch, never the golden.
6. **Insertion order matters.** Use `LinkedHashMap` for jsonb / conflict-map / trigger-caller keys. PHP 8.0+ sorts are stable — never add a non-stable secondary comparator.

---

## Useful references

- Migration design + roadmap: `docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md`
- Detailed parity discipline + load-bearing rules: `CLAUDE.md`
- PHP golden capture harness: `tools/php-golden/`
- Full stack smoke test: `tools/smoke.sh`
- Gradle version catalog: `gradle/libs.versions.toml`

---

## Frontend Development (web/game)

### Stack
- **Next.js 15** (App Router, React Server Components where possible)
- **TypeScript 5.7**
- **Tailwind CSS** + **Pretendard** font
- **SSE** (`EventSource`) for real-time turn events

### Pages (under `app/game/`)

| Page | Route | API | Status |
|------|-------|-----|--------|
| Auction | `/game/auction` | `GET /api/auctions`, `POST /api/command/auction_bid` | Scaffolded |
| Betting | `/game/betting` | `GET /api/bettings`, `POST /api/command/place_bet` | Scaffolded |
| Diplomacy | `/game/diplomacy` | `GET /api/diplomacy`, `POST /api/diplomatic-messages/{id}/accept` | Scaffolded |
| Mailbox | `/game/mailbox` | `GET /api/mailbox`, `DELETE /api/mailbox/{id}` | Scaffolded |
| Nation | `/game/nation` | `GET /api/nation/{id}` | Scaffolded |

### API Base URL
```ts
const API_BASE = process.env.NEXT_PUBLIC_GAME_API_URL ?? 'http://localhost:8081';
```

### SSE Realtime
```ts
const es = new EventSource(`${API_BASE}/realtime/events`);
es.addEventListener('realtime', () => refreshData());
```

### Dev Server
```bash
cd web/game && corepack pnpm dev   # :3001
```
