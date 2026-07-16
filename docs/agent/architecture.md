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

## 배포 토폴로지 (Observed — `.claude/HARNESS.md` §6)

main push → `.github/workflows/deploy.yml` → GHCR → EC2 SSH → `docker-compose.production.yml` pull → 업스트림 선기동 → **game-engine 마지막** 재시작(메모리 상태 소유) → **nginx 최후 재시작**(정적 upstream, stale-DNS 502 예방). 헬스는 `/actuator/health` green + **`world_state.current_year/month` 전진 확인**까지.

## Needs Confirmation

- v2 실시간 전장 런타임 구조(fixed-tick, 연속 좌표)는 기획 채택만 완료(`docs/loops/v2-planning-2026-07-12/`) — 코드 구조 미확정.
