# opensamguk

삼국지 모의전투 HiDCHe(삼모) — Kotlin/Spring + Next.js, 메모리 중심 CQRS 재작성.

Migration program design: `docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md`.

## Modules

- `common` / `logic` / `infra` — shared libraries (RNG/log kernel, pure game logic, JPA+Flyway+Redis).
- `app/gateway-api` — auth + profile orchestration (`:8080`).
- `app/game-api` — read + precheck + mutation intake + SSE (`:8081`).
- `app/game-engine` — turn daemon: in-memory authoritative world + bulk flush (`:8082`).
- `web/gateway` / `web/game` — Next.js apps (`:3000` / `:3001`).

## Develop

Requires JDK 21 (build Gradle with `JAVA_HOME` on 21), Docker, Node 20 + pnpm.

```bash
./gradlew build           # compile + unit/integration tests (Testcontainers)
./tools/smoke.sh          # build + boot full Docker stack, assert health
cd web/gateway && corepack pnpm dev   # run a frontend
```
