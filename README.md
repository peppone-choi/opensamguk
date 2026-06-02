# opensamguk

삼국지 모의전투 HiDCHe(삼모) — **Kotlin/Spring + Next.js, 메모리 중심 CQRS** 재작성.

A faithful migration of the PHP game **devsam/core** to a memory-centric CQRS stack. The PHP source is **grand truth**: every RNG draw, rounding, log string, and side-effect order matches it byte-for-byte, gated by golden replays.

- Migration design + roadmap: [`docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md`](docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md)
- Contributor / agent guide (parity discipline, conventions): [`CLAUDE.md`](CLAUDE.md)
- Agent onboarding guide: [`AGENTS.md`](AGENTS.md)

> Private repository — pending Koei-IP review before any public exposure.

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Architecture](#architecture)
3. [Modules](#modules)
4. [Phase Progress](#phase-progress)
5. [P6 Subsystem Details](#p6-subsystem-details)
6. [Build & Development](#build--development)
7. [Testing Strategy](#testing-strategy)
8. [Parity Discipline](#parity-discipline)
9. [Deployment Infrastructure](#deployment-infrastructure)
10. [Contributing](#contributing)

---

## Project Overview

**opensamguk** is a complete rewrite of the classic web-based strategy game *삼국지 모의전투 HiDCHe* (commonly known as "삼모"). The original game, `devsam/core`, was written in PHP and has served as a beloved online strategy game for years. This project ports every mechanic, every calculation, and every log message to a modern Kotlin/Spring Boot + Next.js stack while preserving **byte-exact behavioral parity** with the original PHP implementation.

### Key Principles

- **PHP is Grand Truth**: Every behavior — RNG draws, rounding modes, Korean log strings with correct 조사 (josa), side-effect order — matches the PHP source byte-for-byte. We never "improve" the original behavior.
- **Memory-Centric CQRS**: The game engine daemon holds the entire world state in memory (`InMemoryTurnWorld`). All mutations are recorded as deltas (`created`/`dirty`/`deleted`) on `ChangeRecorder` and flushed in bulk via JDBC batch. JPA is used **only** for reads and prechecks — never for daemon writes.
- **Golden Gating**: Every phase closes with a real PHP golden replay. We capture PHP execution via Docker (MariaDB 11.4 + `php:8.3-cli`, scenario `1010` = 174 generals) and replay it draw-for-draw against the Kotlin engine. No golden is ever fabricated.
- **LLM-Free Runtime**: The production game server runs entirely on rule engines and templates. Zero LLM API calls at runtime. Zero external API dependencies.

### Technology Stack

| Layer | Technology |
|-------|------------|
| Backend Language | Kotlin 2.1+ on JVM 21 |
| Backend Framework | Spring Boot 3.4 |
| Game Engine | In-memory turn daemon with CQRS |
| Persistence | PostgreSQL 16 (JDBC batch flush) |
| Cache / Message Bus | Redis 7 (XADD command stream, SSE relay) |
| Frontend | Next.js 15 (App Router, React Server Components) |
| Frontend Styling | Tailwind CSS + Pretendard |
| Build Tool | Gradle 8.12 (Kotlin DSL) |
| Database Migration | Flyway |
| Testing | JUnit 5 + Kotest + Testcontainers |
| Deployment | AWS EC2 t3.large, Docker Compose, GitHub Actions |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT LAYER                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                       │
│  │  web/gateway │  │   web/game   │  │   Browser    │                       │
│  │   (:3000)    │  │   (:3001)    │  │              │                       │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘                       │
└─────────┼─────────────────┼─────────────────┼───────────────────────────────┘
          │                 │                 │
          ▼                 ▼                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              API LAYER                                       │
│  ┌────────────────────────────────────┐  ┌────────────────────────────────┐  │
│  │     app/gateway-api (:8080)        │  │      app/game-api (:8081)      │  │
│  │  Auth · Profile · User Management  │  │  Read · Precheck · Intake · SSE │  │
│  └────────────────────────────────────┘  └────────────────────────────────┘  │
│                              │                              │                 │
│                              ▼                              ▼                 │
│                    ┌─────────────────────┐                                    │
│                    │   Redis (XADD)      │  ◄── Command intake queue          │
│                    │   SSE Pub/Sub       │  ──► turnCompleted events          │
│                    └─────────────────────┘                                    │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            GAME ENGINE DAEMON                                │
│                     app/game-engine (:8082)                                  │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                     InMemoryTurnWorld                                 │   │
│  │              (Source of Truth — entire game state)                    │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                              │                                               │
│                              ▼                                               │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                     ChangeRecorder                                    │   │
│  │   created │ dirty │ deleted (tombstone)                               │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                              │                                               │
│                              ▼                                               │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                  JdbcFlushExecutor (BATCH)                            │   │
│  │   INSERT · UPDATE · DELETE — delta only, no inline writes             │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                              │                                               │
│                              ▼                                               │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                     PostgreSQL                                        │   │
│  │   general · general_turn · general_record · city · nation · storage   │   │
│  │   auction · auction_bid · message · ng_betting · game_kv · diplomacy  │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### The ONE Daemon-Write Rule

The game-engine daemon **NEVER** uses a JPA `EntityManager` for writes. JPA is read/precheck only (game-api). Daemon writes go **only** through `ChangeRecorder` → `JdbcFlushExecutor` JDBC batch. Two competing dirty-truths (JPA dirty-checking + change-recorder) would silently diverge. This rule is architecture-test-enforced.

### Command Flow

1. Player submits command via game-api (`POST /api/command/{code}`)
2. game-api validates preconditions (JPA read-only), XADDs command to Redis stream
3. game-engine daemon drains command queue, executes against `InMemoryTurnWorld`
4. Mutations recorded as delta on `ChangeRecorder`
5. At turn end, `JdbcFlushExecutor` flushes all deltas in a single JDBC batch
6. `turnCompleted` SSE broadcast to all connected clients
7. Frontend refreshes state via game-api read endpoints

### Data Flow

- **1 real hour = 1 game turn (1순)**
- **36 turns = 1 game year**
- Command queue + notification pattern for player turns
- Order Ledger: immediate append + 5-minute snapshot + forced save on shutdown/boot

---

## Modules

### `common` — RNG / Log / Rounding Kernel

The foundational parity layer. No Spring, no DB.

- **`rng/LiteHashDrbg`** — Byte-exact SHA-512 DRBG. Mirrors PHP `mt_rand` draw sequence exactly when seeded identically.
- **`rng/RandUtil`** — The draw interface: `nextInt(bound)`, `nextFloat1()`, `nextBits()`, `nextBytes()`. All battle RNG runs on a single `RandUtil(warSeed)` built once in `processWar()` and threaded by reference — never re-seeded mid-stream.
- **`rng/SeedSerializer`** — Multi-component seed serialization for deterministic replay.
- **`util/PhpRound`** — PHP `round()` parity: **half-AWAY-from-zero**, negative-scale support (`phpRound(v, -2)`). NEVER `Math.round` (half-up) or `kotlin.math.round` (half-to-even).
- **`log/*`** — Josa (조사) engine, color/tag markup tokens (`<Y1>`, `<C>`, `<S>`, `<B>`, `<L>`), `ConvertLog`, log token registry.
- **`constants/GameConst`** — Golden numbers, level thresholds, cost tables, Fibonacci helper for inheritance.

### `logic` — Pure Game Logic

No Spring, no DB. The rule engine.

- **`stats/ActionPipeline`** — Multi-source stat fold + `getStatValue` + calc-cache. General stat resolution pipeline.
- **`actions/*`** — Command resolvers (~35 commands + constraints). `CommandRegistry` for dispatch.
- **`actions/nation/*`** — Nation commands: diplomacy proposals (`선전포고`, `불가침제의`, `종전제의`, `불가침파기제의`, `천도`), nation internal commands.
- **`war/*`** — Battle engine: `processWar_NG`, triggers, `WarUnit`, city-conflict, `ConquerCity`, battle items/specialties.
- **`ai/*`** — NPC AI: `GeneralAI`, 4-layer autorun policy, F-BRIDGE candidate gate (`candidateAllowed`), per-general module stat stack.
- **`event/*`** — Event DSL: `EventAction`, `EventActionFactory`, `EventDispatcher`, `EventStore`, `MonthlyPipeline`. World event leaves (UpdateNationLevel, ProcessIncome, RaiseDisaster, etc.).
- **`betting/*`** — Betting engine: `BettingInfo`, `BettingEngine` (calcReward/giveReward), `SelectItem`, `RewardItem`.
- **`auction/*`** — Auction system: `AuctionBase`, `AuctionBidHandler`, `AuctionFinalizeHandler`, `ObfuscatedNamePool` (6525-entry shuffle).
- **`inheritance/*`** — Inheritance: `InheritanceKey`, `InheritancePointMath`, `InheritancePointStore`, `MergeAndApply`.
- **`message/*`** — Messaging: `Message`, `MessageType`, `Mailbox`, `DiplomaticMessage`.
- **`tick/*`** — Turn tick logic, monthly pipeline assembly.

### `infra` — Persistence & Infrastructure

- **`JdbcFlushExecutor`** — JDBC-only batch flush + delete/tombstone delta + row mappers. The ONLY daemon write path.
- **`db/migration/V*.sql`** — Flyway migrations (V1-V7). `V7__p6_messaging_economy.sql`: message table, ng_betting, game_kv.
- **`read/*`** — JPA read repositories: `AuctionRepository`, `AuctionBidRepository`, `BettingRepository`, `MessageRepository`, `CityReadRepository`, `DiplomacyReadRepository`, `GeneralReadRepository`, `NationReadRepository`, `WorldStateReadRepository`.
- **`persistence/*`** — Row mappers: `MessageRowMapper`, `NgBettingRowMapper`, `AuctionRowMapper`, `AuctionBidRowMapper`, `GameKvRowMapper`.

### `app/gateway-api` (:8080)

Auth + profile orchestration. OAuth2/OIDC integration, user registration, session management.

### `app/game-api` (:8081)

Read + precheck + mutation intake + SSE.

- **`web/CommandController`** — `POST /api/command/{code}`: precheck + reserve + XADD to Redis.
- **`web/HealthCheckController`** — `GET /health`: DB + Redis connectivity check.
- **`web/RealtimeRelayController`** — `GET /sse/turn`: SSE relay for turn completion events.

### `app/game-engine` (:8082)

The turn daemon. In-memory authoritative world + bulk flush.

- **`turn/InMemoryTurnWorld`** — The world state (generals, cities, nations, storage, diplomacy, auction, betting, messages).
- **`turn/ChangeRecorder`** — Delta recording: `created`, `dirty`, `deleted`, `kvDirty`, `createdMessages`, `recordAuctionUpsert`, `recordAuctionBidInsert`, `recordInheritancePointSet/Increase`.
- **`turn/DirtyState`** — Mutable delta accumulator.
- **`turn/ProcessNationCommand`** — Nation command dispatch with `NationActionResolveContext`.
- **`turn/ReservedTurnHandler`** — General command dispatch with `GeneralActionResolveContext`.
- **`turn/TurnDaemonCommandDispatcher`** — Routes drained intake commands (AuctionBid, AuctionFinalize, PlaceBet) to handlers.
- **`run/TurnRunService`** — Turn execution orchestration.
- **`run/TurnDaemonLifecycle`** — Daemon lifecycle: start, monthly tick, shutdown.
- **`config/EngineEventConfig`** — Event store wiring.

### `web/gateway` (:3000)

Next.js gateway frontend. Auth pages, lobby, server list.

### `web/game` (:3001)

Next.js game frontend. In-game UI.

- **`app/game/auction/page.tsx`** — Auction UI (경매).
- **`app/game/betting/page.tsx`** — Betting UI (내기).
- **`app/game/diplomacy/page.tsx`** — Diplomacy UI (외교).
- **`app/game/mailbox/page.tsx`** — Mailbox UI (우편함).
- **`app/game/nation/page.tsx`** — Nation UI (국가 정보).

---

## Phase Progress

```
P0 → P1 → P2 → P3 → P4 → P5 → P6 → P7 → P8
                          ↑         ↑
                          └─────────┘ (P6 depends on P4, P5)
                                    ↑
                                    └── P7 parallel with P3/P4/P5
```

| Phase | Scope | State | Tests | Notes |
|-------|-------|-------|-------|-------|
| **P0-A** | Scaffold (Gradle modules, CI, Docker) | ✅ gate-closed | — | Foundation structure |
| **P0-B** | Parity kernel (RNG, round, log, CQRS skeleton) | ✅ gate-closed | common 169 | `LiteHashDrbg`, `PhpRound`, Josa engine |
| **P1** | Vertical slice (one command, full CQRS loop) | ✅ gate-closed | — | End-to-end proof |
| **P2** | ~35 commands + constraint library + 9-source stat-stack | ✅ gate-closed | logic ~800 | `ActionPipeline`, `CommandRegistry` |
| **P3** | Monthly economy tick + city-supply BFS + event engine | ✅ gate-closed | logic ~1200 | `MonthlyPipeline`, `PostUpdateMonthly`, all 9 world leaves |
| **P4** | Battle engine (`processWar_NG` + triggers + ConquerCity) | ✅ gate-closed | logic ~1543 | G1 battle/conquest draw-for-draw |
| **P5** | NPC AI (GeneralAI + 4-layer autorun) | ✅ gate-closed | logic 1661 | 174/174 turns (667/667 draws), 8 families 11/11 |
| **P6** | Diplomacy · Messaging · Auction · Betting · Inheritance | ✅ pure-logic done | ~2195 | See [P6 Subsystem Details](#p6-subsystem-details) |
| **P7** | Read API + Next.js + SSE | ⬜ in progress | — | REST controllers for auction/betting/message/mailbox |
| **P8** | Parity harness + gateway orchestration + AWS deploy | ⬜ pending | — | PHP 93-command compare, EC2 t3.large |

### P5 Backlog (documented, not fabricated)

- Long-sim multi-turn gate (gate dim c)
- G12 nation reserved-fail deny-log

### P5 Quarantines (proven with sibling-code-path byte-match)

- `genfound-방랑군`: needs 거병→건국 mini-sim
- `chooseInstantNationTurn`: zero PHP callers
- Q1 ORDER BY RAND (`do선양`/`오랑캐임관`): unreachable in scenario 1010, deterministic substitute

---

## P6 Subsystem Details

### ✅ P6 Pure-Logic Completed (gate-closed)

| Subsystem | Files | Description |
|-----------|-------|-------------|
| **Inheritance enum parity** | `InheritanceKey.kt` | 11-entry enum with correct `keyName` mapping |
| **Inheritance buff fold** | `GeneralActionModuleFactory` slot #7 | Buffs integrated into stat pipeline (was identity stub) |
| **BuyHiddenBuff cost fix** | `BuyHiddenBuff.kt` | Cumulative-difference cost (`points[level] - points[prevLevel]`) + already-purchased/higher-grade guards |
| **Turn daemon command dispatcher** | `TurnDaemonCommandDispatcher.kt` | Routes drained intake commands to auction/betting handlers |
| **Betting event registrar** | `BettingActions.kt` | `OpenNationBetting` / `FinishNationBetting` resolvable by name |
| **Missing diplomacy proposals** | `CheJongjeonjeui.kt`, `CheBulgachimPagijeui.kt` | `종전제의` (peace proposal) + `불가침파기제의` (NA cancellation proposal) |
| **Auction bid handler** | `AuctionBidHandler.kt` | Bid validation, charge, refund, bid insert, close-date extend |
| **Auction finalize handler** | `AuctionFinalizeHandler.kt` | Rollback/finish, resource transfer, unique-item slot check |
| **Messaging sink unification** | `GeneralActionResolveContext.kt`, `NationActionResolveContext.kt` | Both contexts now have unified `sendMessage()` API |
| **Betting engine** | `BettingEngine.kt` | `calcReward` (exclusive/compound), `giveReward` (gold/inheritance-point), betting key codec |
| **BettingInfo realignment** | `BettingInfo.kt` | String-typed `type`, `SelectItem`/`RewardItem`/`BettingResult`, `AnySerializer` for JSON aux |
| **Open/Finish nation betting** | `OpenNationBetting.kt`, `FinishNationBetting.kt` | Adapted to new BettingInfo + BettingEngine |

### ⬜ P6 P7-Coupled Remainder

| Subsystem | Gap | Blocking Phase |
|-----------|-----|----------------|
| **Auction expiry daemon** | Auctions never auto-close | P7 |
| **PlaceBet command/handler** | No betting intake endpoint | P7 |
| **Betting rank updates** | `betwin`/`betwingold` rank_data updates after payout | P7 |
| **Messaging accept/decline API** | `DiplomaticMessage.agree/decline` needs API endpoints | P7 |
| **Game-api read controllers** | No `@RestController` for auction/betting/message/mailbox | P7 |
| **Diplomacy matrix read** | `GetDiplomacy.php` neutral-map masking 3-7→2 | P7 |

### ⬜ P6 P8-Coupled Remainder

| Subsystem | Gap | Blocking Phase |
|-----------|-----|----------------|
| **P6 golden capture scripts** | `capture_diplomacy.php`, `capture_message.php`, `capture_auction.php`, `capture_betting.php`, `capture_inheritance.php`, `capture_worldcmd.php` | P8 |
| **Parity harness integration** | Wire P6 goldens into PHP 93-command compare | P8 |
| **Restart-rehydrate lossless** | Daemon restart re-reads auction/betting/message pools + obfuscatedNamePool from KV | P8 |
| **Tournament wall-clock scheduling** | Deterministic game-tick predicate under turn cadence | P8 |

---

## Build & Development

### Prerequisites

- **JDK 21 LTS** (Gradle 8.12 fails on Java 25+)
- Docker (for Testcontainers integration tests)
- Node.js 20 + pnpm (for frontend)
- Docker Compose (for local stack)

### Quick Start

```bash
# Clone (private repo)
git clone git@github.com:peppone-choi/opensamguk.git
cd opensamguk

# Build backend (always use Java 21)
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build

# Run tests
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test

# Full Docker smoke test
./tools/smoke.sh

# Run frontend
cd web/gateway && corepack pnpm dev
cd web/game && corepack pnpm dev
```

### Build Verification

> **CRITICAL**: Verify by **OUTPUT TAIL + test XML**, not exit code. The host routes gradle through a context-mode wrapper; `task-notification` exit 0 is unreliable.

```bash
# Method 1: tail + grep
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test 2>&1 | tail -40
# grep: BUILD SUCCESSFUL + test counts

# Method 2: test result XML
ls logic/build/test-results/test/*.xml
```

### Testcontainers on macOS

Requires `api.version=1.44` + `DOCKER_CONTEXT=default` + Ryuk disabled. Already wired in `tasks.test`:

```kotlin
systemProperty("api.version", "1.44")
```

Docker-unavailable ⇒ IT **skipped**, not failed.

---

## Testing Strategy

### Test Pyramid

| Layer | Count | Scope |
|-------|-------|-------|
| `common` | ~175 | RNG draw sequence, rounding, Josa, log tokens, seed serialization |
| `logic` | ~1794 | Pure game logic: commands, battle engine, AI, events, betting, auction, inheritance |
| `infra` | ~65 | JDBC flush, row mappers, Flyway migrations, repository reads |
| `app:game-engine` | ~147 | Turn daemon, ChangeRecorder, InMemoryTurnWorld, command dispatch |
| `app:game-api` | ~10 | Controllers, prechecks, SSE relay |
| **Total** | **~2191** | |

### Golden Testing

The `tools/php-golden/` directory contains the Docker-based PHP capture harness:

- **MariaDB 11.4** + `php:8.3-cli`
- **Scenario `1010`** (174 generals, NOT empty scenario_0)
- `j_install.php` called twice (PHP quirk)
- Fresh DB per run (install is NOT idempotent)
- Dumps must be byte-identical across two runs

### Key Test Categories

- **Draw-for-draw**: RNG sequence byte-match (method, args, result, cursor)
- **Number parity**: `PhpRound` half-away, `Util::toInt` truncation, damage clamp `ceil()`
- **Byte parity**: Korean log strings (josa, color/tags, prefixes) byte-identical
- **Insertion order**: jsonb / conflict-map / trigger-caller keys preserve `LinkedHashMap` order
- **Restart-rehydrate**: Flush→reload cycle reconstructs polymorphic Messages via `buildFromArray`

---

## Parity Discipline

The six non-negotiable rules. Violating any silently breaks the golden gates.

1. **RNG draw-for-draw**: All randomness is `RandUtil(LiteHashDrbg(seed))`. The draw **order, count, and method args** are parity targets, not just the result. Battle: ONE `RandUtil(warSeed)` per fight, threaded by reference.
2. **Rounding**: `Util::round` = **half-AWAY-from-zero** → `PhpRound`. Negative-scale `phpRound(v, -2)`. NEVER `Math.round` (half-up) or `kotlin.math.round` (half-to-even). `Util::toInt`/`intdiv` = truncate-toward-zero.
3. **Korean log byte-parity**: Log strings (`Josa` 조사, color/tag markup, prefixes, `<Y1>【name】</> <C>HP (-dead)</>`, 진격·퇴각·패퇴·전멸·분쟁·정복 …) must match exactly. **Log order = execution order**.
4. **Flush delta, not inline writes**: Mutations recorded as `created`/`dirty`/`deleted` on `ChangeRecorder`, flushed in bulk. Resolvers write **only** delta.
5. **Faithful port, never fabricate**: Golden numbers/logs/seeds come **only** from real PHP capture. If un-capturable, quarantine with proof + log to backlog — do **not** invent it.
6. **Insertion order matters**: jsonb / conflict-map keys preserve `LinkedHashMap` insertion order. PHP 8.0+ sorts are stable — never add non-stable secondary comparator.

---

## Deployment Infrastructure

Production deployment target: **AWS EC2 t3.large** (~$80-85/month).

### Present (P8 scaffold)

| File | Purpose |
|------|---------|
| `.github/workflows/deploy.yml` | GitHub Actions: build Docker image → push GHCR → SSH to EC2 → rolling restart |
| `docker-compose.prod.yml` | 8 services (gateway-api, game-api, game-engine, postgres, redis, nginx, prometheus, grafana) with memory limits and healthchecks |
| `infra/nginx/nginx.conf` | Reverse proxy: upstreams, rate limiting, SSE `/realtime/` with `proxy_buffering off`, static asset caching |
| `scripts/deploy.sh` | Manual EC2 deploy script with health check loops |
| `app/game-api/.../HealthCheckController.kt` | Custom `/health` endpoint checks DB + Redis connectivity |

### Deployment Architecture

```
Internet → nginx (:80/:443) → gateway-api (:8080) / game-api (:8081)
                                     ↓
                              game-engine (:8082)
                                     ↓
                    PostgreSQL (:5432) ←── JDBC batch flush
                    Redis (:6379)    ←── XADD commands + SSE pub/sub
```

### Design Decisions

- **LLM-free runtime**: All decisions = rule engine, all prose = templates. M0~V1.1: LLM 0 calls.
- **Zero external API dependencies**: No payment gateways, no analytics, no third-party APIs.
- **Memory-centric**: In-memory world = source of truth. DB = persistence only.
- **Turn cadence**: 1 hour real time = 1 game turn (1순). 36 turns = 1 game year.

---

## Contributing

### Branch Stack

One branch per phase, stacked PRs (base = parent):

```
main ← p0a-foundation-scaffold ← p0b-parity-kernel ← p1-vertical-slice ← p2-commands-constraints
  ← p3-monthly-tick ← p4-battle-engine ← p5-npc-ai ← p6-diplomacy-auction-inheritance
```

### Commit Convention

One logical commit per task. Every commit message ends with:

```
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

### Phase Gate

Each phase = **spec → plan → adversarial review → execute → gate**. The gate is a real PHP golden replayed draw-for-draw. Not "done" until green (or gaps quarantined with proof + logged to backlog).

### Agent Onboarding

See [`AGENTS.md`](AGENTS.md) for:
- Project overview and stack
- Architecture deep-dive
- Parity rules summary
- Build and test commands
- Deployment guide
- Common pitfalls

### Documentation

- **Specs**: `docs/superpowers/specs/`
- **Plans**: `docs/superpowers/plans/`
- **Research**: `docs/superpowers/research/`
- **Status**: `docs/superpowers/P6_STATUS.md`

---

## Recent Changes

| Date | Commit | Description |
|------|--------|-------------|
| 2026-06-02 | `129f538` | `feat(p6-betting)`: BettingEngine calcReward/giveReward + BettingInfo structural realignment |
| 2026-06-02 | `6e68674` | `docs`: Update CLAUDE.md Roadmap — P3 complete, P6 pure-logic done |
| 2026-06-01 | `b80c38b` | `fix(p6-messaging)`: Unify message sink across General/Nation resolve contexts |
| 2026-06-01 | `b0f586e` | `feat(p6-auction)`: Wire ChangeRecorder + repos through TurnDaemonCommandDispatcher |
| 2026-06-01 | `9f8a6fb` | `feat(p6-auction)`: AuctionFinalizeHandler — rollback/finish, resource transfer, unique-item slot check |
| 2026-06-01 | `56bcd0c` | `feat(p6-auction)`: AuctionBidHandler — bid validation, charge, refund, bid insert, close-date extend |
| 2026-05-31 | `d7f3b8c` | `feat(p3-pipeline)`: Monthly pipeline assembly + WorldActionContext + V8 migration |
| 2026-05-31 | `9994ed7` | `feat(p6)`: Pure-logic gap closure + engine pipeline wiring WIP |
| 2026-05-31 | `1687393` | `fix(p6-blockers)`: jsonb entity annotations + JPA repo package move + wire fixture |
| 2026-05-31 | `4314cb3` | `docs(p6)`: Add P6_STATUS — done units + P3/P7/P8-bounded remainder |

---

*Last updated: 2026-06-02. Branch: `p6-diplomacy-auction-inheritance`.*
