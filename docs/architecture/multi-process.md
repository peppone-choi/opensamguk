# Multi-Process Architecture

## Decision

Spring Boot 단일 앱 → **Gateway + Game App (버전별 JVM)** 멀티프로세스로 전환.

기존 "Single App" 결정을 대체함.

## Motivation

게임은 시즌 단위로 운영됨. 한 시즌(수 주~수 월)동안 플레이어가 전략을 세우고
경쟁하므로, **진행 중인 시즌의 게임 로직을 변경하면 안 됨.**

동시에 여러 월드가 다른 시즌 단계에 있음:
- World A: 시즌 3주차 (코드 변경 금지)
- World B: 새 시즌 시작 (최신 코드 적용 가능)
- World C: 천하통일 완료 (정산 후 재시작 대기)

단일 JVM에서는 코드 배포 = 모든 월드에 즉시 적용. 시즌 안전성 보장 불가.

### 레거시/core2026 레퍼런스

- **레거시 PHP**: 서버별 디렉토리 + 별도 DB. 같은 코드, 서버별 config 생성.
- **core2026 계획**: Profile별 PM2 프로세스. `git worktree` + `commitSha`로
  프로필당 코드 스냅샷 고정. Gateway가 오케스트레이션.

## Process Topology

```
Client
  │
  ▼
Gateway (Spring Boot, :8080)
  ├── Auth (login/register/JWT)
  ├── World Registry (CRUD, lifecycle)
  ├── Reverse Proxy → Game Instance (by worldId)
  ├── Cross-world reads (명예의전당, 역대왕조)
  └── Process Orchestrator (spawn/stop)
       │
       ├── Game JVM 1 (:9001) — commit abc123
       │   ├── worlds: [A, B]
       │   ├── Turn Daemon (할당된 worlds만)
       │   └── Game API (commands, queries)
       │
       └── Game JVM 2 (:9002) — commit def456
           ├── worlds: [C]
           ├── Turn Daemon
           └── Game API
```

**핵심: 1 JVM = 1 code version, not 1 world.** 같은 커밋의 월드는 한 JVM에서 처리.
동시 운영 버전은 보통 2~3개이므로 JVM도 2~3개.

## Gradle Module Structure

```
backend/
├── shared/          # DTO, 공통 모델, JWT 검증 유틸
├── gateway-app/     # Auth + World Registry + Proxy + Orchestrator
└── game-app/        # Game API + Turn Daemon + Engine
```

### Module Ownership

| Module | Owns |
|--------|------|
| **shared** | DTOs, common models (ScenarioData, ScenarioInfo), JWT token validation, error models |
| **gateway-app** | Auth, WorldState, shared entities (AppUser, Emperor, HallOfFame, GameHistory, OldGeneral, OldNation), world lifecycle, reverse proxy, process orchestration |
| **game-app** | 19 game entities (world_id FK), 25+ game controllers, all engine/command/AI logic, TurnDaemon, per-world game API |

### Controller Split

**Gateway:**
- AuthController (`/api/auth`)
- AccountController (`/api/account`)
- WorldController (`/api/worlds` — create/list/get + activate/deactivate lifecycle)
- ScenarioController (`/api/scenarios`)
- RankingController (hall of fame — cross-world)
- AdminController (user management, dashboard)

**Game Instance:**
- GeneralController, CommandController
- NationController, NationManagementController, NationPolicyController
- CityController, DiplomacyController, DiplomacyLetterController
- TroopController, MessageController, MapController
- ItemController, AuctionController, TournamentController
- BoardController, VoteController, HistoryController
- InheritanceController, NpcSelectionController, NpcPolicyController
- RealtimeController, TurnController, BattleSimController

## Communication

### Client → Gateway → Game Instance (reverse proxy)

```
Client: POST /api/generals/42/execute
  → Gateway: worldId 추출 → 해당 world의 Game JVM 포트 조회 → localhost:9001
  → Game JVM 1: 커맨드 실행 → 응답
  → Gateway: 응답 전달
```

### World Create Flow (Gateway 단일 진입점)

```
Client: POST /api/worlds (scenarioCode, commitSha, gameVersion)
  → Gateway: commitSha별 Game JVM ensure/start
  → Gateway: Authorization 헤더를 유지한 채 Game JVM /api/worlds 호출
  → Game JVM: 시나리오 초기화 + world_state/게임 엔티티 생성
  → Gateway: world attach + world_state.meta.gatewayActive=true 저장
```

### World Reset/Delete Flow

```
Client: POST   /api/worlds/{id}/reset
Client: DELETE /api/worlds/{id}
  → Gateway: 대상 월드의 commitSha/gameVersion으로 Game JVM ensure/start
  → Gateway: Authorization 헤더 유지 후 Game JVM lifecycle API 호출
  → reset: old world detach + new world attach + meta.gatewayActive=true
  → delete: world detach
```

### Gateway ↔ Game Instance (internal control)

```
Gateway → Game: GET  /internal/health
Gateway → Game: POST /internal/worlds/{id}/attach
Gateway → Game: POST /internal/worlds/{id}/detach
Gateway → Game: GET  /internal/turn-status
```

### Game Instance → Shared Data

시즌 종료 시 유산포인트/명예의전당 기록:
- Game → Gateway API: `POST /internal/inheritance/settle`
- Game → Gateway API: `POST /internal/hall-of-fame/record`
- 또는: Game → shared DB 직접 쓰기 (DB role로 범위 제한)

## Database Strategy

단일 PostgreSQL 인스턴스. `world_id` FK 격리 유지.

### Shared Tables (Gateway 소유)

`app_user`, `world_state`, `emperor`, `hall_of_fame`,
`game_history`, `old_general`, `old_nation`

### Game Tables (Game Instance 소유, world_id FK)

`general`, `nation`, `city`, `diplomacy`, `troop`, `message`, `event`,
`board`, `auction`, `vote`, `tournament`, `betting`, `rank_data`,
`general_turn`, `nation_turn`, `general_record`, `general_access_log`,
`world_history`, `yearbook_history`

### Optional RLS

Game JVM마다 DB role을 달리해서 자기 world_id만 접근 가능하게 제한.

## Versioning Pipeline

```bash
# 1. 빌드: commit별 JAR 생성
git checkout abc123
./gradlew :game-app:bootJar
cp game-app/build/libs/game-app.jar artifacts/game-app-abc123.jar

# 2. WorldState에 버전 기록
# world_state.commit_sha = 'abc123'
# world_state.game_version = '2.1.0'
# world_state.meta.gatewayActive = true

# 3. Gateway 시작 시:
#    - world_state 중 meta.gatewayActive=true 월드 조회
#    - commitSha별 Game JVM 복구/스폰
#    - worldId -> Game JVM 라우팅 복원
java -jar artifacts/game-app-abc123.jar \
  --server.port=9001 \
  --game.commit-sha=abc123 \
  --game.version=2.1.0 \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/opensam
```

## Season Lifecycle

```
[시즌 시작]
  Admin: POST /api/worlds (scenarioCode=184, commitSha=def456)
  Gateway: world_state.commit_sha/game_version 저장 + meta.gatewayActive=true
           → game-app-def456.jar 스폰 → world attach

[시즌 진행]
  Game JVM: TurnDaemon이 `world_state.commit_sha == process commitSha`
           그리고 `meta.gatewayActive=true` 월드만 처리
  새 코드 배포해도 이 JVM에 영향 없음 (다른 JAR)

[새 빌드]
  ./gradlew :game-app:bootJar → game-app-ghi789.jar
  기존 시즌 영향 없음. 새 시즌에만 적용.

[시즌 종료]
  천하통일 → Game → Gateway API로 유산포인트/명예의전당 기록
  Gateway: world 상태를 COMPLETED로 변경 → Game JVM에서 detach
  Gateway: world_state.meta.gatewayActive=false
  해당 버전의 모든 world가 종료되면 JVM 셧다운
```

## Memory Budget

| JVM | Heap | Expected RSS |
|-----|------|-------------|
| Gateway | `-Xms64m -Xmx192m` | ~200MB |
| Game JVM (per version) | `-Xms64m -Xmx384m` | ~400MB |
| **Total (1 GW + 3 versions)** | | **~1.4GB** |

Optimizations: `spring.main.lazy-initialization=true`, Hikari small pool
(`maximumPoolSize=5`), CDS (Class Data Sharing).

## Caveats

1. **Gateway crash → Game JVM 고아화**: Gateway가 ProcessBuilder로 직접 스폰하면
   Gateway 재시작 시 자식 프로세스 lost. systemd unit 또는 supervisor script로
   Game JVM 관리하고, Gateway는 "원하는 상태 선언"만 하는 것이 안전.

2. **Game → shared 테이블 직접 쓰기 제한**: 모노리스 커플링 재발 방지.
   유산포인트/명예의전당 기록은 Gateway API 경유 또는 DB role 권한으로 범위 제한.

3. **라우팅**: 클라이언트가 Gateway만 바라봄 (단순). Game JVM 직접 접근 불가.
