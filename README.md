# opensamguk

삼국지 모의전투 HiDCHe(삼모) — **Kotlin/Spring + Next.js, 메모리 중심 CQRS** 재작성.

A faithful migration of the PHP game **devsam/core** to a memory-centric CQRS stack. The PHP source is **grand truth**: every RNG draw, rounding, log string, and side-effect order matches it byte-for-byte, gated by golden replays.

- Migration design + roadmap: [`docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md`](docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md)
- Contributor / agent guide (parity discipline, conventions): [`CLAUDE.md`](CLAUDE.md)

> Private repository — pending Koei-IP review before any public exposure.

## Architecture

```
api ──Redis(XADD)──▶ game-engine daemon ──JDBC batch flush──▶ PostgreSQL
 ▲                    (InMemoryTurnWorld = source of truth)          │
 └─────────── turnCompleted SSE ◀── ChangeRecorder dirty/created/deleted
```

The daemon holds the authoritative world in memory and flushes changes as a bulk JDBC delta. **Writes never go through JPA** (JPA is read/precheck only) — a single dirty-truth, no competing change tracker.

## Modules

- `common` / `logic` / `infra` — shared libraries: RNG/log/rounding kernel (`LiteHashDrbg` byte-exact RNG, `PhpRound` half-away, Josa/log tokens), pure game logic (stat pipeline, commands, battle engine, event DSL), JDBC flush + Flyway + Redis.
- `app/gateway-api` — auth + profile orchestration (`:8080`).
- `app/game-api` — read + precheck + mutation intake + SSE (`:8081`).
- `app/game-engine` — turn daemon: in-memory authoritative world + bulk flush (`:8082`).
- `web/gateway` / `web/game` — Next.js apps (`:3000` / `:3001`).

## Develop

Requires **JDK 21** (Gradle 8.12 fails on newer JDKs), Docker, Node 20 + pnpm.

```bash
# Always build with JAVA_HOME on 21; run from the repo root.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build      # compile + unit/integration tests (Testcontainers)
./tools/smoke.sh                                               # build + boot full Docker stack, assert health
cd web/gateway && corepack pnpm dev                            # run a frontend
```

> Verify a build by the gradle **output tail** (`... 2>&1 | tail -40`, grep `BUILD SUCCESSFUL` + test counts), not the exit code. Testcontainers integration tests skip cleanly when Docker is unavailable.

## Parity gates

Each phase ships a real PHP golden captured via the Docker harness in [`tools/php-golden/`](tools/php-golden/) (MariaDB + `php:8.3-cli`, scenario `1010`), replayed **draw-for-draw** against the Kotlin engine. Goldens are never fabricated — an un-capturable value is quarantined with proof, not invented. See `CLAUDE.md` for the full discipline.

## Status

`P0 → P1 → P2 → P3 → P4 → P5 → P6 → P7 → P8` (`P7 ← {P2, P6}`, parallelizable).

| Phase | Scope | State |
| --- | --- | --- |
| P0-A / P0-B | scaffold · parity kernel (RNG/round/log + CQRS skeleton) | ✅ gate-closed |
| P1 | vertical slice (one command, full CQRS loop) | ✅ gate-closed |
| P2 | ~35 commands + constraint library + 9-source stat-stack | ✅ gate-closed |
| P3 | monthly economy tick + city-supply BFS + event engine | ✅ gate-closed |
| P4 | battle engine (`processWar_NG` + triggers + ConquerCity) | ✅ gate-closed (G1 battle/conquest draw-for-draw + quarantines logged; ~1543 tests) |
| P5 / P6 | NPC AI · diplomacy/auction/inheritance | ⬜ |
| P7 / P8 | read API + Next.js + SSE · parity harness + AWS deploy | ⬜ |

Deployment target: AWS EC2 t3.large, fully LLM-free (rule engine + templates), no external API dependencies.
