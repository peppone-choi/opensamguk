# opensamguk

삼국지 모의전투 HiDCHe(삼모) — **Kotlin/Spring + Next.js, 메모리 중심 CQRS** 재작성.

PHP 게임 **devsam/core**를 메모리 중심 CQRS 스택으로 충실 이식한 프로젝트입니다. PHP 소스가 **grand truth**이며, 모든 RNG 추출·반올림·로그 문자열·부수효과 순서를 byte-단위로 일치시키고 PHP 골든 리플레이로 게이팅합니다. 게임 동작은 절대 "개선"하지 않고 원작 그대로 재현합니다.

- 마이그레이션 설계 + 로드맵: [`docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md`](docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md)
- 프론트엔드 패러티 + 시드 계획(F0–F5): [`docs/superpowers/plans/2026-06-02-frontend-parity-and-scenario-seed-plan.md`](docs/superpowers/plans/2026-06-02-frontend-parity-and-scenario-seed-plan.md)
- 기여자/에이전트 가이드(패러티 규율·관례): [`CLAUDE.md`](CLAUDE.md) · 모듈/빌드/테스트 온보딩: [`AGENTS.md`](AGENTS.md)
- 원작(grand truth) · 라이선스: HideD님의 [**devsam**](https://storage.hided.net/gitea/devsam) (MIT) — 자세한 감사의 말은 [감사 / 라이선스](#감사--라이선스)
- 관련 저장소: 배포(오케스트레이션) [**opensamguk-docker**](https://github.com/peppone-choi/opensamguk-docker) · 이미지 자산 [**opensamguk-images**](https://github.com/peppone-choi/opensamguk-images)(jsDelivr CDN)

---

## 목차

1. [프로젝트 소개](#프로젝트-소개)
2. [아키텍처](#아키텍처)
3. [빠른 시작](#빠른-시작)
4. [서비스 / 포트](#서비스--포트)
5. [시나리오 시드](#시나리오-시드)
6. [개발](#개발)
7. [테스트](#테스트)
8. [작업 운영 체계](#작업-운영-체계)
9. [프론트엔드 / 배포 (F0–F5)](#프론트엔드--배포-f0f5)
10. [패러티 규율](#패러티-규율)
11. [감사 / 라이선스](#감사--라이선스)

---

## 프로젝트 소개

**opensamguk**는 PHP로 작성된 웹 전략 게임 *삼국지 모의전투 HiDCHe*("삼모")의 완전 재작성판입니다. 원작 `devsam/core`의 모든 메커니즘·계산·로그 메시지를 Kotlin/Spring Boot + Next.js 스택으로 이식하면서 원작 PHP와 **byte-단위 동작 패러티**를 유지합니다.

### 핵심 원칙

- **PHP = grand truth** — RNG 추출, 반올림 방식, 한글 로그 문자열(조사 포함), 부수효과 순서까지 PHP 소스와 byte 단위로 일치. 원작 동작을 절대 "개선"하지 않습니다.
- **메모리 중심 CQRS** — 게임 엔진 데몬이 전체 월드 상태를 메모리(`InMemoryTurnWorld`)에 보유. 모든 변경은 `ChangeRecorder`에 `created`/`dirty`/`deleted` 델타로 기록되고 JDBC 배치로 일괄 flush. JPA는 **읽기/사전검증 전용**(game-api)이며 데몬 write에는 절대 쓰지 않습니다.
- **골든 게이팅** — 각 페이즈는 실제 PHP 골든 리플레이로 마감. Docker(MariaDB 11.4 + `php:8.3-cli`, 시나리오 `1010` = 174장수)로 PHP 실행을 캡처해 Kotlin 엔진과 draw-for-draw로 대조. 골든은 절대 날조하지 않습니다.
- **완전 LLM-free 런타임** — 프로덕션 게임 서버는 전부 룰 엔진 + 템플릿으로 동작. 런타임 LLM API 호출 0건, 외부 API 의존 0개.

### 기술 스택

| 레이어 | 기술 |
|--------|------|
| 백엔드 언어 | Kotlin 2.1 / JVM 21 LTS |
| 백엔드 프레임워크 | Spring Boot 3.4 |
| 게임 엔진 | 메모리 내 턴 데몬 (CQRS) |
| 영속화 | PostgreSQL 16 (JDBC 배치 flush) |
| 캐시 / 메시지 버스 | Redis 7 (XADD 명령 스트림, SSE 릴레이) |
| 프론트엔드 | Next.js 15 (App Router) / React 19 / TypeScript 5.7 |
| 스타일 | Tailwind CSS + Pretendard |
| 빌드 | Gradle 8.12 (Kotlin DSL) |
| 마이그레이션 | Flyway |
| 테스트 | JUnit 5 + kotlin.test + Testcontainers |
| 리버스 프록시 | nginx 1.27 |
| 배포 | AWS EC2 t3.large · Docker Compose · GitHub Actions (GHCR) |

---

## 아키텍처

메모리 중심 CQRS. 데몬이 메모리에 권위 월드를 들고, 변경 델타만 JDBC 배치로 flush, 턴 완료는 SSE로 브로드캐스트합니다.

```
                                  브라우저
                                     │
                                     ▼
                         ┌──────────────────────┐
                         │   nginx (:80 / :443)  │  리버스 프록시
                         └──────────┬───────────┘
            ┌───────────────────────┼───────────────────────┐
            ▼                       ▼                         ▼
   ┌────────────────┐     ┌────────────────┐
   │  web/gateway   │     │   web/game     │       (정적 / SSR · Next.js)
   │    (:3000)     │     │   (:3001)      │
   │ 인증·로비·어드민 │     │  인게임 UI      │
   └───────┬────────┘     └───────┬────────┘
   httpOnly│쿠키 프록시    동일출처│ /api/game 프록시
           ▼                      ▼
   ┌────────────────┐     ┌────────────────────────┐
   │ app/gateway-api│     │     app/game-api        │
   │    (:8080)     │     │       (:8081)           │
   │ 인증(JWT/BCrypt)│     │ read · precheck · intake │
   │ 프로필·어드민    │     │ · SSE 릴레이             │
   └────────────────┘     └───────────┬─────────────┘
                                       │
                          ┌────────────┴────────────┐
                          │   Redis (XADD 명령 큐 +   │
                          │   SSE turnCompleted)     │
                          └────────────┬────────────┘
                                       ▼
                       ┌──────────────────────────────────┐
                       │      app/game-engine (:8082)       │  턴 데몬
                       │  ┌──────────────────────────────┐  │
                       │  │  InMemoryTurnWorld (진실의 원천)│  │
                       │  └───────────────┬──────────────┘  │
                       │                  ▼                  │
                       │  ┌──────────────────────────────┐  │
                       │  │ ChangeRecorder               │  │
                       │  │  created │ dirty │ deleted    │  │
                       │  └───────────────┬──────────────┘  │
                       │                  ▼                  │
                       │  ┌──────────────────────────────┐  │
                       │  │ JdbcFlushExecutor (BATCH)    │  │
                       │  │  델타만 INSERT/UPDATE/DELETE   │  │
                       │  └───────────────┬──────────────┘  │
                       └──────────────────┼─────────────────┘
                                          ▼
                       ┌──────────────────────────────────┐
                       │           PostgreSQL (:5432)        │
                       │  world_state · nation · city ·      │
                       │  general · general_turn · diplomacy │
                       │  · auction · ng_betting · game_kv … │
                       └──────────────────────────────────┘
```

### 책임 분리

- **`app/gateway-api` (:8080)** — 인증/프로필. 자체 JWT/BCrypt 로컬 인증(원작 Kakao OAuth에서 의도적 divergence), 회원가입, 세션, 관리자 시드.
- **`app/game-api` (:8081)** — 읽기(read) + 사전검증(precheck) + 명령 인입(intake) + SSE 릴레이. JPA는 read/precheck 전용. 명령은 Redis 스트림으로 XADD.
- **`app/game-engine` (:8082)** — 턴 데몬. `InMemoryTurnWorld`(진실의 원천) + `ChangeRecorder`(델타) + `MonthlyPipeline` + `TurnRunService`.

### The ONE 데몬-write 규칙

게임 엔진 데몬은 **절대** JPA `EntityManager`로 write하지 않습니다. JPA는 read/precheck 전용(game-api). 데몬 write는 **오직** `ChangeRecorder` → `JdbcFlushExecutor` JDBC 배치 경로만 사용합니다. 두 dirty-truth(JPA dirty-checking + change-recorder)가 공존하면 조용히 발산하기 때문입니다. 이 규칙은 아키텍처 테스트로 강제됩니다.

### 명령 흐름

1. 플레이어가 game-api로 명령 제출 (`POST /api/command/{code}`)
2. game-api가 사전검증(JPA read-only) 후 Redis 스트림에 XADD
3. game-engine 데몬이 큐를 비워 `InMemoryTurnWorld`에 실행
4. 변경을 `ChangeRecorder`에 델타로 기록
5. 턴 종료 시 `JdbcFlushExecutor`가 델타를 단일 JDBC 배치로 flush
6. 연결된 클라이언트에 `turnCompleted` SSE 브로드캐스트
7. 프론트가 game-api read 엔드포인트로 상태 갱신

### 턴 박자 (turn cadence)

- **현실 1시간 = 게임 1턴(1순)** · **36턴 = 게임 1년**
- 명령 큐 + 알림 패턴. 메모리=진실의 원천, DB=영속화.

---

## 빠른 시작

전제: Docker + Docker Compose. (백엔드 직접 빌드/프론트 dev는 [개발](#개발) 참조.)

```bash
# 1) 클론 (비공개 저장소)
git clone git@github.com:peppone-choi/opensamguk.git
cd opensamguk

# 2) 환경변수 — 예시를 복사해 .env 생성 (필요 시 비밀번호/프로필 수정)
cp .env.example .env

# 3) 전체 스택 기동 (postgres·redis·3 API·2 프론트·nginx)
docker compose up -d --build

# 4) 브라우저 접속
#   http://localhost:3000        ← 게이트웨이(로그인/로비). nginx 경유 시 http://localhost/
#   http://localhost:3001/game   ← 게임 프론트(로그인 후 진입)
```

### 로그인 → 로비 → 게임

1. `http://localhost:3000/login` 접속.
2. 관리자 계정으로 로그인 — 기본 admin **`peppone`**. (gateway-api `AdminSeeder`가 `ADMIN_USERNAME`/`ADMIN_PASSWORD` 환경변수가 **둘 다** 설정돼 있을 때 부팅 시 1회 생성. 둘 중 하나라도 비면 시드를 건너뜁니다. 일반 계정은 `/join`에서 가입.)
3. 로그인 성공 → `/lobby`로 이동. 서버가 있으면 서버 목록 + 10분 캐싱 맵 프리뷰를 보여주고, 서버가 없으면 맵/로그/서버 선택을 렌더하지 않습니다.
4. 로비에서 **입장** → web/game `/game` 메인 화면.

> 인증 토큰은 Next route handler가 gateway-api로 프록시한 뒤 **httpOnly 쿠키**(`sam_access`/`sam_refresh`)에만 저장합니다. 브라우저 JS는 토큰을 읽지 못하며(XSS 토큰 탈취 방지), gateway-api/game-api를 브라우저가 직접 호출하지 않습니다(동일출처 프록시 → CORS 불필요). access JWT는 15분 만료이고, 만료 시 `/api/auth/me`가 refresh 쿠키로 자동 재발급합니다.

로컬 개발 compose는 game-engine 부팅 시 `scenario_1010`을 **자동 시드**하도록 둘 수 있습니다(아래 [시나리오 시드](#시나리오-시드)). 프로덕션 compose는 기본값이 `SCENARIO_SEED_ENABLED=false`라서 관리자 서버 생성 전에는 빈 월드가 정상 상태입니다.

---

## 서비스 / 포트

`docker-compose.yml`(로컬 개발) 기준 8개 서비스:

| 서비스 | 이미지/빌드 | 포트 | 역할 |
|--------|-------------|------|------|
| `postgres` | `postgres:16-alpine` | 5432 | 영속 저장소 (DB `sammo`) |
| `redis` | `redis:7-alpine` | 6379 | 명령 스트림(XADD) + SSE pub/sub |
| `gateway-api` | `docker/gateway-api.Dockerfile` | 8080 | 인증(JWT/BCrypt)·프로필·어드민 |
| `game-api` | `docker/game-api.Dockerfile` | 8081 | read · precheck · intake · SSE |
| `game-engine` | `docker/game-engine.Dockerfile` | 8082 | 턴 데몬 (`InMemoryTurnWorld`) |
| `web-gateway` | `docker/web-gateway.Dockerfile` | 3000 | Next.js 게이트웨이(로그인/로비) |
| `web-game` | `docker/web-game.Dockerfile` | 3001 | Next.js 게임 프론트 |
| `nginx` | `nginx:1.27-alpine` | 80 | 리버스 프록시 (`./nginx/nginx.conf`) |

nginx 라우팅(`infra/nginx/nginx.conf`, production): `/api/gateway/` → gateway-api · `/api/game/` → web-gateway Next 프록시(httpOnly 쿠키 → Bearer, 서버 선택) · `/api/game/realtime/` → game-api(SSE, 버퍼링 off) · `/game/` → web-game · `/` → web-gateway · `/health` 헬스 체크.

### 환경변수 (`.env.example`)

```env
# Game DB
GAME_DATABASE_URL=jdbc:postgresql://localhost:5432/sammo
GAME_DB_USER=sammo
GAME_DB_PASSWORD=sammo
# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
# Ports
GATEWAY_API_PORT=8080
GAME_API_PORT=8081
GAME_ENGINE_PORT=8082
# Profile (server:scenario)
TURN_PROFILE_NAME=che:scenario_2
```

프론트는 각자 `.env.example`을 둡니다(브라우저 비노출 변수는 `NEXT_PUBLIC_` 접두사 없음):

- `web/gateway/.env.example` — `GATEWAY_API_URL`(서버 전용 프록시 대상), `NEXT_PUBLIC_GAME_URL`(로비 입장 링크).
- `web/game/.env.example` — `GAME_API_URL`·`GATEWAY_API_URL`(서버 전용), `NEXT_PUBLIC_GATEWAY_URL`(미인증 시 로그인 리다이렉트 대상).

관리자 시드용 `ADMIN_USERNAME`/`ADMIN_PASSWORD`는 gateway-api에 주입합니다(코드/리포에 평문 비밀번호 하드코딩 금지).

### 프로덕션 (EC2 t3.large)

`docker-compose.production.yml`은 GHCR 이미지(`ghcr.io/${GHCR_OWNER}/<svc>:${IMAGE_TAG}`)를 풀해서 메모리 제한·헬스체크·볼륨(`pgdata`/`redisdata`/`nginx-cache`)·`opensamguk` 브리지 네트워크로 기동합니다. `POSTGRES_PASSWORD`는 필수(미설정 시 기동 실패). 배포는 `.github/workflows/deploy.yml`(빌드 → GHCR push → SSH → 롤링 재시작) + `scripts/deploy.sh`(수동 EC2 배포 + 헬스 체크 루프)로 수행합니다.

```bash
docker compose -f docker-compose.production.yml up -d
```

---

## 시나리오 시드

게임 엔진은 부팅 시 **DB만 읽습니다**(`WorldStateReadRepository`) — 시나리오 JSON을 런타임에 직접 로드하지 않습니다. fresh/빈 DB가 비어 있지 않도록, 부팅 시 한 번 시드합니다.

- **시드 주체**: `app/game-engine`의 `ScenarioSeedRunner`(`SeedBootstrap.ensureSeeded`) — `ApplicationRunner`로 부팅 시 실행.
- **멱등**: `world_state` 행이 이미 있으면(`count(*) > 0`) 로그 남기고 건너뜁니다. 두 번째 호출은 no-op이라 시드→로드 순서가 빈 라이프사이클에 무관하게 보장됩니다.
- **임포터**: `infra`의 `ScenarioImporter`(+`ScenarioJson`)가 커밋된 리소스 `scenario/scenario_1010.json` + `scenario/cities_1010.json`(grand truth 값)을 opensamguk 스키마 행으로 매핑해 **JDBC INSERT**(`world_state, nation, city, general, general_turn, nation_turn, diplomacy, rank_data, ng_games`).
- **부팅 배선**: `WorldSnapshotLoader`가 DB → `InMemoryTurnWorld` 스냅샷을 구성(시드 직전 방어적으로 `ensureSeeded` 재호출).
- **JDBC-only — one-daemon-write 규칙 비위반**: `JdbcTemplate`만 사용(Flyway/AdminSeeder와 동일 범주). JPA `EntityManager`나 `ChangeRecorder`를 쓰지 않으며, 아키텍처 테스트 write-path scan(`opensamguk.engine.{flush,turn,run}`) 밖인 `opensamguk.engine.boot` 패키지에 위치합니다.
- **env fence**: `SCENARIO_SEED_ENABLED`(로컬 `.env.example` 기본 `true`, production compose 기본 `false`) · `SCENARIO_CODE`(기본 `scenario_1010`).

> `scenario_1010` = 2국 · 24도시 · 678장수. 24도시는 시나리오 JSON에 없고 `cities_1010.json`로 채웁니다. 게이트: `general`/`city`/`nation` 행 > 0 + 엔진 부팅·턴 진행. (이는 빠른 플레이를 위한 최소 시드(A)이며, PHP `Scenario::build` draw-for-draw 패러티 보정(B)은 후속 작업입니다.)

---

## 개발

### 전제

- **JDK 21 LTS 필수** (Gradle 8.12는 Java 25+ 파싱 실패)
- Docker (Testcontainers 통합 테스트용)
- Node 20 + pnpm (corepack)

### 백엔드 빌드 (항상 Java 21, 항상 repo root)

```bash
# 전체 빌드 + 테스트
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build

# 단일 모듈
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test
```

> **빌드 검증은 exit code가 아니라 출력 tail + 테스트 XML로**: 호스트가 gradle을 context-mode 래퍼로 라우팅해 `task-notification` exit 0이 부정확합니다. `... 2>&1 | tail -40`으로 `BUILD SUCCESSFUL` + 카운트를 grep하거나 `**/build/test-results/test/*.xml`을 확인하세요. UP-TO-DATE false-green이 의심되면 `--rerun-tasks`.

### 프론트 dev

```bash
cd web/gateway && corepack pnpm dev   # :3000 (로그인/로비)
cd web/game    && corepack pnpm dev   # :3001 (인게임)
```

각 앱은 `.env.example`을 `.env.local`로 복사해 사용합니다.

### Docker 스모크

```bash
./tools/smoke.sh   # 이미지 빌드 + 전체 스택 부팅 + health 단언
```

---

## 테스트

### 전체 체크 (커밋 전 권장)

```bash
tools/parity/gate.sh backend
```

### 테스트 피라미드 (개략)

| 레이어 | 범위 |
|--------|------|
| `common` | RNG 추출 시퀀스, 반올림, 조사, 로그 토큰, 시드 직렬화 |
| `logic` | 순수 게임 로직: 명령, 전투 엔진, AI, 이벤트, 베팅, 경매, 유산 |
| `infra` | JDBC flush, row mapper, Flyway 마이그레이션, 시나리오 임포터, repository read |
| `app:game-engine` | 턴 데몬, ChangeRecorder, InMemoryTurnWorld, 명령 디스패치, 시나리오 부팅 IT |
| `app:game-api` | 컨트롤러, precheck, SSE 릴레이 |

### Testcontainers (macOS)

`api.version=1.44` + `DOCKER_CONTEXT=default` + Ryuk 비활성이 `tasks.test`에 배선돼 있습니다. **Docker 미사용 시 통합 테스트는 fail이 아니라 skip**됩니다.

### 골든 테스트

`tools/php-golden/` Docker 캡처 하네스: MariaDB 11.4 + `php:8.3-cli`, 시나리오 `1010`(174장수, 빈 scenario_0 아님). quirk — `j_install.php` 두 번 호출, install 비멱등(매 실행 fresh DB), 덤프는 두 실행 간 byte-identical. 골든은 read-only 소비 대상이며 절대 날조/수정하지 않습니다.

---

## 작업 운영 체계

주먹구구식 구현을 막기 위한 정본 문서는 [`docs/superpowers/WORKING_SYSTEM.md`](docs/superpowers/WORKING_SYSTEM.md)입니다.

- `skills-lock.json`은 skills.sh에서 설치한 프로젝트 스킬 목록을 고정합니다.
- `.agents/skills/`는 로컬 실행 표면이며 git-ignore입니다. 새 환경에서는 `DISABLE_TELEMETRY=1 npx --yes skills experimental_install`로 복원합니다.
- provider/model 공통 개발도구는 `tools/agent-system/check.py`입니다. 로컬은 `tools/agent-system/check.py`, PR/CI는 `tools/agent-system/check.py --strict --base origin/main`, 에이전트 통합은 `--format json`을 사용합니다.
- 비자명 작업은 구현자와 별개 agent/provider의 비판적 검증을 거칩니다. Kimi-backed Claude Code, Codex, Gemini 등 병렬 agent는 서로 PHP 증거·테스트·문서·운영 불변식을 공격적으로 검토하고, `fix-required`가 남아 있으면 ship/merge하지 않습니다.
- 백엔드 표준 게이트는 `tools/parity/gate.sh backend`입니다. Java 21로 Gradle을 실행하고 `BUILD SUCCESSFUL` 및 테스트 XML의 `failures=0 errors=0`을 확인합니다.
- PHP 레거시 분석은 항상 `legacy/devsam-core` 소스 경로와 line range를 먼저 잡고, 실제 캡처가 필요한 동작은 `tools/php-golden/`로 증거를 만든 뒤 Kotlin/Next 구현과 비교합니다.
- 외부 스킬은 보조 지식입니다. PHP grand truth, `CLAUDE.md`, `AGENTS.md`, one-daemon-write 규칙과 충돌하면 repo 규칙이 이깁니다.

---

## 프론트엔드 / 배포 (F0–F5)

P7 프론트 + P8 시드/배포를 점진적으로 닫는 F-시리즈. 계획서: [`docs/superpowers/plans/2026-06-02-frontend-parity-and-scenario-seed-plan.md`](docs/superpowers/plans/2026-06-02-frontend-parity-and-scenario-seed-plan.md). 원칙: `hwe/ts/` Vue가 프론트 grand truth(`hwe/*.php`는 dist mount 셸), PHP가 이깁니다. 인증은 JWT 로컬(Kakao 제거)을 패러티 예외로 확정했습니다.

| 단계 | 내용 | 상태 |
|------|------|------|
| **F0 게이트웨이 인증** | `web/gateway` 엔트런스/로그인/회원가입/로비/어드민. JWT를 Next route handler 프록시 + httpOnly 쿠키(`sam_access`/`sam_refresh`)로 연결. `AdminSeeder`로 peppone(role=ADMIN) 자동 생성. | ✅ |
| **F1 시나리오 시드** | `ScenarioImporter` + `ScenarioSeedRunner` → 로컬 fresh DB에는 `scenario_1010` 자동 시드 가능, production은 관리자 서버 생성 전 기본 비활성. `WorldSnapshotLoader`로 엔진 부팅·턴 진행. | ✅ |
| **F2 메인화면 + 메뉴 척추** | `web/game` 메인 화면(`GameChrome` = GameInfo 헤더 + GlobalMenu + MainControlBar 20버튼 + 게이팅). | ✅ |
| **F3 read API + 랭킹/내정보** | game-api read 컨트롤러 + `web/game` 랭킹(`a_*`)·내정보(`b_*`) 페이지. game-api로 **read-only 렌더**. | ✅ |
| **F4 액션 페이지 (read)** | chief-center/battle/troop/auction/board/vote/diplomacy/inherit/npc-control/simulator 등 read 렌더. **명령 제출(mutation) 경로는 미완** — 현재 read 우선. | ✅(read) |
| **F5 turnkey + docs** | 정본 compose(로컬 + production) + `.env.example` + 한글 `README/AGENTS/CLAUDE`. `git pull && docker compose up`로 자동 설치·시드. | 🔄 |

> **상태 표기 주의**: F0–F4의 화면은 game-api **read 데이터를 렌더**하는 단계까지 구축돼 있습니다. 프론트에서의 명령 제출(mutation/턴 예약) 경로는 아직 완성되지 않았습니다. 백엔드 로직(P2~P6 명령·전투·경매·베팅·외교)은 게이트가 닫혀 있으나, 그 기능을 프론트에서 끝까지 조작하는 흐름은 후속 작업입니다.

---

## 패러티 규율

여섯 가지 비타협 규칙. 하나라도 어기면 골든 게이트가 조용히 깨집니다. 상세는 [`CLAUDE.md`](CLAUDE.md).

1. **RNG draw-for-draw** — 모든 난수는 `RandUtil(LiteHashDrbg(seed))`. 추출 **순서·횟수·메서드 인자**가 패러티 타깃. 전투는 `processWar()`에서 한 번 만든 **단일** `RandUtil(warSeed)`를 참조로 전달, 중간 재시드 금지.
2. **반올림** — `Util::round` = **half-AWAY-from-zero** → `PhpRound`(음수 스케일 `phpRound(v, -2)`, `phpRound(v/100)*100` 금지). `Math.round`(half-up)·`kotlin.math.round`(half-to-even) 금지. `Util::toInt`/`intdiv` = 0 방향 절삭. 데미지 클램프 = `ceil()`.
3. **한글 로그 byte-패러티** — 조사·색/태그 마크업·접두어(`<Y1>【name】</> <C>HP (-dead)</>`, 진격·퇴각·패퇴·전멸·분쟁·정복 …) byte 일치. 로그 순서 = 실행 순서.
4. **델타 flush, 인라인 write 금지** — 변경은 `ChangeRecorder`에 `created`/`dirty`/`deleted`로 기록 후 일괄 flush. 리졸버는 델타만 write.
5. **충실 이식, 날조 금지** — 골든 수치/로그/시드는 실제 PHP 캡처에서만. 캡처 불가 시 증거(sibling-code-path byte-match)와 함께 격리 + 백로그 기록. 발명·테스트 약화·골든 수정 금지. 불일치 시 Kotlin 구현을 고칩니다.
6. **삽입 순서 보존** — jsonb / conflict-map / trigger-caller 키는 `LinkedHashMap` 삽입 순서 유지. PHP 8.0+ 정렬은 stable — 비-stable 2차 비교자 추가 금지.

---

## 감사 / 라이선스

이 저장소는 **HideD**님이 만드신 PHP 원작 게임 **devsam** — 삼국지 모의전투 HiDCHe(삼모) — 없이는 존재할 수 없었습니다. 모든 게임 메커니즘·계산·로그 문자열·RNG 추출의 grand truth가 그분의 작업물이며, opensamguk은 그 동작을 byte-단위로 충실히 이식·재현한 파생물입니다.

- 원작 소스 (grand truth): **devsam** — <https://storage.hided.net/gitea/devsam>
- 원작 `devsam/core`는 **MIT 라이선스**로 공개되어 있습니다. opensamguk은 그 출처와 저작자 표시를 유지하며, 파생물로서 MIT 라이선스의 정신을 존중합니다.

> HideD님의 노력 없이는 이 저장소도 없었습니다. 깊이 감사드립니다 — 이 프로젝트의 토대 전부가 그분의 헌신에서 비롯되었습니다.

---

*최종 갱신: 2026-06-08 · skills.sh + working-system 기준.*
