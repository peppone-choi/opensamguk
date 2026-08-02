# Architecture — opensamguk

각 항목에 근거 등급을 표기한다: **Observed**(코드/테스트/문서로 확인) · **Inferred**(패턴에서 추정) · **Needs Confirmation**.

## 데이터 흐름 (Observed — `AGENTS.md`, 아키텍처 테스트)

```
web/game ─▶ game-api(:8081) ──Redis XADD──▶ game-engine(:8082) ──JDBC batch flush──▶ PostgreSQL
   ▲            │ read(JPA)/precheck          (InMemoryTurnWorld = 진실 원천)              │
   └── SSE ◀────┴──────────── turnCompleted ◀── ChangeRecorder dirty/created/deleted ◀────┘
```

## 레이어 책임과 의존 방향 (Observed)

- `common` ← `logic` ← (`infra`, `app/*`). `logic`은 Spring/DB 무의존(순수 JVM).
- 쓰기 경로: 리졸버(logic) → `ChangeRecorder` 델타 → `JdbcFlushExecutor`(infra) JDBC 배치.
- 읽기 경로: game-api JPA read repository + precheck.

## 금지되는 의존 방향 (Observed — 테스트로 강제)

1. **game-engine 데몬에서 JPA `EntityManager` write 금지.** 강제: `DaemonNoEntityManagerTest`, `InfraNoEntityManagerTest`. 예외(sanctioned): `engine.boot`의 `ScenarioSeedRunner`/`AdminSeeder`(JdbcTemplate), `CommandReserveService.reserve`.
2. `logic`에 Spring/DB 의존 추가 금지 (Observed — 모듈 구성).
3. 골든 픽스처를 다른 모듈로 복사 금지 (Observed — `AGENTS.md`).

## 주요 진입점 (Observed)

- 턴 데몬 루프: `app/game-engine/.../config/DaemonLoopConfig.kt` + `TurnRunService`
- 명령 intake: `app/game-api/.../reserve/CommandWireMapper.kt`(`intakeCodes`/`toCommand`) → `common/wire/TurnDaemonCommand.kt` → engine `TurnDaemonCommandDispatcher`
- 월간 파이프라인: `logic`의 `MonthlyPipeline.runMonth()` + `PostUpdateMonthly`
- 전투: `logic/war/*`의 `processWar()` — 전투 전체가 단일 `RandUtil(warSeed)`
- 프론트: `web/game/app/game/*/page.tsx` → Next route handler → game-api

## 변경 시 영향 범위 (Observed — `.claude/HARNESS.md` §7)

- 명령 하나의 완결 경로: golden → logic port → gate test → intake wire → FE page → review. `intakeCodes`에 없으면 precheck는 AVAILABLE인데 엔진이 조용히 deny — **포팅됐어도 갭**.
- 공유 확장점(`CommandWireMapper.kt`, `TurnDaemonCommand.kt`, `ChangeRecorder` 채널, `JdbcFlushExecutor` flush step)은 여러 명령이 동시에 넓히면 충돌 — 순차(creator-then-consumer).

## 대표 구현/테스트 파일 (Observed)

- RNG: `common/.../rng/LiteHashDrbg`, `RandUtil`, `SeedSerializer` · 반올림: `common/.../util/PhpRound`
- 게이트 테스트: `logic`의 `*GoldenTest` / `*ReplayGateTest`(골든: `logic/src/test/resources/golden/<area>/`)
- 아키텍처 테스트: `DaemonNoEntityManagerTest` · precheck 합의: `PrecheckFullCrossCallSiteTest`

## 배포 토폴로지 (Observed — `.github/workflows/deploy.yml`)

`main` push → GitHub-hosted build/test → GHCR image push → GCP Compute Engine `e2-standard-2`에 로컬 등록된 `gcp-prod` self-hosted runner → `opensamguk-docker` main 동기화 + GHCR login → `deployer` 재생성/호환성 확인 → shared 의존성(`gateway-postgres`, `gateway-redis`) → shared upstream(`gateway-api`, `web-gateway`, 선택적 `game-frontend`) pull/recreate → **nginx 최후 재시작**(정적 upstream, stale-DNS 502 예방).

이 deploy는 **shared stack만** 갱신하며, 각 게임 서버 `servers/<id>.env`의 `IMAGE_TAG`/`WEB_GAME_TAG` 핀은 바꾸지 않는다. 서버 승격은 별도 명시 승인 운영이다. 헬스는 nginx·gateway API를 확인하고, `s1`이 실행 중일 때에만 game API/engine health와 **`world_state.current_year/month` 전진**까지 확인한다.

## Needs Confirmation

- v2 실시간 전장 런타임 구조(fixed-tick, 연속 좌표)는 기획 채택만 완료(`docs/loops/v2-planning-2026-07-12/`) — 코드 구조 미확정.
