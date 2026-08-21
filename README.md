# opensamguk

삼국지 모의전투 HiDCHe(삼모)에서 출발한 **Kotlin/Spring + Next.js, 메모리 중심 CQRS** 전략 게임.

초기에는 PHP 게임 **devsam/core**의 동작을 충실히 이식했고, 그 결과는 동결된 회귀 기준선으로 보존합니다.
2026-08-20 ADR-LITE-042 이후 신규 기획의 정본은 오픈삼국의 승인된 ADR·spec·현재 구현이며, PHP와의
draw-for-draw·byte-for-byte 일치는 신규 기능의 절대 조건이 아닙니다. 결정론적 재현과 단일 daemon write
경로 같은 아키텍처 불변식은 계속 유지합니다.

- 마이그레이션 설계 + 로드맵: [`docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md`](docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md)
- 프론트엔드 패러티 + 시드 계획(F0–F5): [`docs/superpowers/plans/2026-06-02-frontend-parity-and-scenario-seed-plan.md`](docs/superpowers/plans/2026-06-02-frontend-parity-and-scenario-seed-plan.md)
- 문서 포털: [`docs/README.md`](docs/README.md) · [사용자 매뉴얼](docs/user/README.md) · [관리자 매뉴얼](docs/admin/README.md) · [제품 로드맵](docs/design/roadmap.md)
- 기여자/에이전트 가이드(패러티 규율·관례): [`CLAUDE.md`](CLAUDE.md) · 모듈/빌드/테스트 온보딩: [`AGENTS.md`](AGENTS.md)
- 원작·라이선스: HideD님의 [**devsam**](https://storage.hided.net/gitea/devsam) (MIT) — 자세한 감사의 말은 [감사 / 라이선스](#감사--라이선스)
- 관련 저장소: 배포(오케스트레이션) [**opensamguk-docker**](https://github.com/peppone-choi/opensamguk-docker) · 이미지 자산 [**opensamguk-images**](https://github.com/peppone-choi/opensamguk-images)(jsDelivr CDN)

---

## 목차

1. [프로젝트 소개](#프로젝트-소개)
2. [현재 제품 기준과 회귀 상태](#현재-제품-기준과-회귀-상태)
3. [아키텍처](#아키텍처)
4. [빠른 시작](#빠른-시작)
5. [서비스 / 포트](#서비스--포트)
6. [시나리오 시드](#시나리오-시드)
7. [개발](#개발)
8. [테스트](#테스트)
9. [작업 운영 체계](#작업-운영-체계)
10. [프론트엔드 / 배포 (F0–F5)](#프론트엔드--배포-f0f5)
11. [회귀·아키텍처 규율](#회귀아키텍처-규율)
12. [감사 / 라이선스](#감사--라이선스)

---

## 프로젝트 소개

**opensamguk**는 PHP 웹 전략 게임 *삼국지 모의전투 HiDCHe*("삼모")의 이식에서 시작해 독립적인 제품
기획으로 발전한 재작성판입니다. 기존 이식 결과는 회귀 보호 자산으로 유지하고, 신규 세계·시스템·UX는
오픈삼국의 승인된 의사결정을 기준으로 발전시킵니다.

### 핵심 원칙

- **오픈삼국 제품 정본** — 신규 기획은 최신 ADR·승인 spec·현재 구현을 따른다. PHP와 외부 레거시는 역사적 근거와 회귀 참고 자료다.
- **메모리 중심 CQRS** — 게임 엔진 데몬이 전체 월드 상태를 메모리(`InMemoryTurnWorld`)에 보유. 모든 변경은 `ChangeRecorder`에 `created`/`dirty`/`deleted` 델타로 기록되고 JDBC 배치로 일괄 flush. JPA는 **읽기/사전검증 전용**(game-api)이며 데몬 write에는 절대 쓰지 않습니다.
- **결정론적 회귀 보호** — 기존 골든과 테스트는 동결 기준선으로 보존한다. 신규 규칙은 승인 근거와 재현 가능한 테스트로 보호하며 결과·골든·검증을 날조하지 않는다.
- **완전 LLM-free 런타임** — 프로덕션 게임 서버는 전부 룰 엔진 + 템플릿으로 동작. 런타임 LLM API 호출 0건, 외부 API 의존 0개.

### 기술 스택

| 레이어 | 기술 |
|--------|------|
| 백엔드 언어 | Kotlin 2.1 / JVM 21 LTS |
| 백엔드 프레임워크 | Spring Boot 3.4 |
| 게임 엔진 | 메모리 내 턴 데몬 (CQRS) |
| 영속화 | PostgreSQL 16 (JDBC 배치 flush) |
| 캐시 / wake 버스 | Redis 7 (best-effort 명령 wake, SSE 릴레이) |
| 프론트엔드 | Next.js 15 (App Router) / React 19 / TypeScript 5.7 |
| 스타일 | CSS variables / global CSS + Pretendard (공유 UI 패키지 통합 진행 중) |
| 빌드 | Gradle 8.12 (Kotlin DSL) |
| 마이그레이션 | Flyway |
| 테스트 | JUnit 5 + kotlin.test + Testcontainers |
| 리버스 프록시 | nginx 1.27 |
| 배포 | GCP Compute Engine e2-standard-2 · Docker Compose · GitHub Actions (GHCR) |

---

## 현재 제품 기준과 회귀 상태

> 아래 v1 패러티 기록은 2026-07-30에 관측한 역사적 회귀 기준선입니다. 2026-08-20 ADR-LITE-042가
> 신규 제품 기준을 supersede했으며, 현재 방향은 [제품 로드맵](docs/design/roadmap.md)을 따릅니다.

2026-07-30 기준 v1은 **2026-07-26 레거시 감사의 비운영 범위**를 완료했습니다.
이는 "프로덕션 활성화 완료"가 아닙니다. PHP `devsam/core`를 정본으로 한
명령·월간/이벤트·전투/점령·AI·부가 시스템·world scope/restart·프런트·저장
로그의 감사 차단 항목을 PHP capture, Kotlin replay, 백엔드/프런트 게이트와
**로컬 Docker**로 재측정한 bounded 판정입니다. 최종 reviewer가 처음 발견한
`SelectPool`·`VotePoll`·`DiplomacyLetter` unscoped read도 V32 복합 key와
local-ID-only read의 경계 결함으로 보정했으며, 최종 parity review는
**`CLEARED`**입니다.

- 최종 감사: [`v1 레거시 동등성 감사`](docs/superpowers/research/2026-07-26-v1-legacy-equivalence-audit.md)
- 종결 검토: [`v1 비운영 완료 review`](docs/superpowers/reviews/2026-07-27-v1-nonoperational-completion-review.md)
- 측정 원장: [`v1 비운영 폐쇄 LEDGER`](docs/loops/v1-nonoperational-completion-2026-07-27/LEDGER.md)
- 제품 날짜 규칙: **상순·중순·하순, 월 3순, 연 36순**(ADR-LITE-024).

| 범위 | 상태 | 확인 근거 |
|------|------|-----------|
| v1 비운영 감사 §6.1–§6.8 | ✅ 완료 | PHP schema 4 12개월·36순 exact replay, 92-command matrix, battle/event/AI/side-system capture, scoped read/restart 검증; final parity review `CLEARED` |
| backend | ✅ 완료 | canonical `tools/parity/gate.sh backend`: 552 suites / 4,758 tests / failure·error 0, known `LongSim` skip 1; fresh 영향 범위 237 suites / 1,366 tests green. Golden은 current green이나 logic은 `UP-TO-DATE`여서 fresh rerun으로 과장하지 않음 |
| frontend | ✅ 완료 | `web/game` typecheck + 46 files / 227 tests, `git diff --check` green |
| local Docker | ✅ 완료 | five images sequential green, 8 health green, Playwright 1 passed (`241634ms`); join `RESOLVED`/`ok=true`/general `1230`, 정확히 14 DOM, restart 뒤 general/result/repository `200`, auth `false|false` 복원, project containers 0 |
| CQRS S6 / production activation | ⬜ 미수행 | canary·expand/backfill·capacity/admission·실제 운영 월드 cutover는 별도 인간 승인과 운영 게이트 대상 |
| v2 | 🔄 별도 트랙 | v1을 오리지널로 보존하고 v2 뉴버전 구현/운영을 준비하는 별도 범위 |

동결된 문서 감사 입력은 388개였고, 동결 뒤 작성된 이 종결 문서와 실행
증거는 그 수에 소급 포함하지 않습니다. 더 이른 병렬 image build OOM과 port
3000 collision 뒤 120초 timeout은 제품 실패가 아니라 하니스/환경 실패였고,
최종 상태는 순차 corrected gate만 따른다(기본 timeout `420000`, override test
green). 자세한 경계와 원래의 발견 사항은 감사 보고서의 2026-07-30 사후 검토
부록을 따릅니다. 이 표는 git action 전 release-candidate 증거이며 commit,
push, merge, deploy를 승인하거나 실행한 기록이 아닙니다.

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
                          │ Redis (best-effort wake + │
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
- **`app/game-api` (:8081)** — 읽기(read) + 사전검증(precheck) + 명령 인입(intake) + SSE 릴레이. JPA는 read/precheck 전용. 명령은 durable inbox를 DB에 먼저 커밋하고 Redis에는 best-effort wake만 보냅니다.
- **`app/game-engine` (:8082)** — 턴 데몬. `InMemoryTurnWorld`(진실의 원천) + `ChangeRecorder`(델타) + `MonthlyPipeline` + `TurnRunService`.

### The ONE 데몬-write 규칙

게임 엔진 데몬은 **절대** JPA `EntityManager`로 write하지 않습니다. JPA는 read/precheck 전용(game-api). 데몬 write는 **오직** `ChangeRecorder` → `JdbcFlushExecutor` JDBC 배치 경로만 사용합니다. 두 dirty-truth(JPA dirty-checking + change-recorder)가 공존하면 조용히 발산하기 때문입니다. 이 규칙은 아키텍처 테스트로 강제됩니다.

### 명령 흐름

1. **즉시 데몬 명령**: game-api가 `202` 이전 durable inbox를 DB에 커밋하고, best-effort Redis wake를 보냅니다.
2. 엔진이 inbox를 claim/apply하고, 상태 효과·result·outbox·inbox terminal 전이를 하나의 DB 트랜잭션으로 flush합니다.
3. 그 DB 커밋 뒤에만 XACK와 결과 publication을 수행하고, 프론트는 result/read 경로로 상태를 갱신합니다.
4. **턴 예약 `che_*` 명령**: game-api가 reserved ring과 admission lifecycle을 write/read합니다.
5. due ring에서 `ReservedTurnHandler`가 `CommandRegistry`를 통해 실행하고, `ChangeRecorder` 델타를 `JdbcFlushExecutor`가 flush합니다.

### 턴 박자 (turn cadence)

- **현실 1시간 = 게임 1턴(상순/중순/하순)** · **36턴 = 게임 1년**
- durable inbox + wake 알림 패턴. 메모리=진실의 원천, DB=영속화.

### CQRS 정합성 하드닝 (ARCH-S1–S6)

메모리 중심 CQRS의 다중 월드·크래시·읽기 정합성을 조이는 트랙(OPENSAM-127~139). 전부 **build-only**(프로덕션 cutover/activation 미수행)이며, 동결 회귀 기준선과 아키텍처 불변식은 유지합니다. 현재 S5까지 main에 머지됐고 S6(롤아웃)은 잔여입니다.

- **월드 스코프** — 로더·쿼리·예약·Redis 키·flush를 `world_id`로 스코프하고, 동일 local-ID 2월드 격리 게이트 통과. (OPENSAM-127~129)
- **flush 무결성** — 불변 `DeltaGenerationSession`(prepare/commit/abort), `world_version` CAS + `writer_epoch` 펜스, `FlushRecoveryGate`(FLUSH_RETRY/RELOAD 안전). (OPENSAM-130~132)
- **S4 durable 명령 경로** — `command_inbox`를 DB에 먼저 커밋한 뒤 best-effort Redis wake, 엔진 claim/apply, 상태 효과·`command_result`·`command_outbox`·inbox terminal 전이를 **하나의 DB 트랜잭션**으로 flush, 커밋 후 XACK와 결과 publication 순으로 처리합니다. PR #312 머지, 독립 리뷰 cleared. (OPENSAM-133~136)
- **S5 읽기·부팅 경계** — hot/cold 카탈로그 + 아키텍처 가드, 부팅 아카이브 읽기 bounded/on-demand화, game-api `minVersion` read barrier(stale read → 409 `VERSION_NOT_VISIBLE`). PR #314/#315 머지. (OPENSAM-137~139)
- **S6 롤아웃** — canary/expand-backfill/replica ADR는 **잔여**(S2–S5 완료 후 착수).

---

## 빠른 시작

전제: Docker + Docker Compose. (백엔드 직접 빌드/프론트 dev는 [개발](#개발) 참조.)

```bash
# 1) 클론 (비공개 저장소)
git clone git@github.com:peppone-choi/opensamguk.git
cd opensamguk

# 2) 환경변수 — 예시를 복사한 뒤, 로컬 .env에 필요한 값을 직접 설정
cp .env.example .env
# Required: JWT_PRIVATE_KEY, JWT_PUBLIC_KEY, OPENSAMGUK_WORLD_ID

# 3) 전체 스택 기동 (postgres·redis·4 API·2 프론트·nginx)
docker compose up -d --build

# 4) 브라우저 접속
#   http://localhost:3000        ← 게이트웨이(로그인/로비). nginx 경유 시 http://localhost/
#   http://localhost:3001/game   ← 게임 프론트(로그인 후 진입)
```

### 로그인 → 로비 → 게임

1. `http://localhost:3000/login` 접속.
2. 관리자 계정으로 로그인 — 기본 admin 아이디는 **`peppone`**이며 비밀번호는 `.env`의 `ADMIN_PASSWORD`에 직접 설정합니다. (gateway-api `AdminSeeder`가 `ADMIN_USERNAME`/`ADMIN_PASSWORD` 환경변수가 **둘 다** 설정돼 있을 때 부팅 시 1회 생성. 둘 중 하나라도 비면 시드를 건너뜁니다. 일반 계정은 `/join`에서 가입.)
3. 로그인 성공 → `/lobby`로 이동. 서버가 있으면 서버 목록 + 10분 캐싱 맵 프리뷰를 보여주고, 서버가 없으면 맵/로그/서버 선택을 렌더하지 않습니다.
4. 로비에서 **입장** → web/game `/game` 메인 화면.

> 인증 토큰은 Next route handler가 gateway-api로 프록시한 뒤 **httpOnly 쿠키**(`sam_access`/`sam_refresh`)에만 저장합니다. 브라우저 JS는 토큰을 읽지 못하며(XSS 토큰 탈취 방지), gateway-api/game-api를 브라우저가 직접 호출하지 않습니다(동일출처 프록시 → CORS 불필요). access JWT는 15분 만료이고, 만료 시 `/api/auth/me`가 refresh 쿠키로 자동 재발급합니다.

로컬 개발 compose는 game-engine 부팅 시 `scenario_1010`을 **자동 시드**하도록 둘 수 있습니다(아래 [시나리오 시드](#시나리오-시드)). 프로덕션 compose는 기본값이 `SCENARIO_SEED_ENABLED=false`라서 관리자 서버 생성 전에는 빈 월드가 정상 상태입니다.

---

## 서비스 / 포트

`docker-compose.yml`(로컬 개발) 기준 9개 서비스:

| 서비스 | 이미지/빌드 | 포트 | 역할 |
|--------|-------------|------|------|
| `postgres` | `postgres:16-alpine` | 5432 | 영속 저장소 (DB `sammo`) |
| `redis` | `redis:7-alpine` | 6379 | best-effort 명령 wake + SSE pub/sub |
| `gateway-api` | `docker/gateway-api.Dockerfile` | 8080 | 인증(JWT/BCrypt)·프로필·어드민 |
| `board-api` | `docker/board-api.Dockerfile` | 8083 | 게시판 read/write·access JWT 검증·공유 users DB 조회 |
| `game-api` | `docker/game-api.Dockerfile` | 8081 | read · precheck · intake · SSE |
| `game-engine` | `docker/game-engine.Dockerfile` | 8082 | 턴 데몬 (`InMemoryTurnWorld`) |
| `web-gateway` | `docker/web-gateway.Dockerfile` | 3000 | Next.js 게이트웨이(로그인/로비) |
| `web-game` | `docker/web-game.Dockerfile` | 3001 | Next.js 게임 프론트 |
| `nginx` | `nginx:1.27-alpine` | 80 | 리버스 프록시 (`infra/nginx/nginx.conf`) |

nginx 라우팅(`infra/nginx/nginx.conf`, production): `/api/gateway/` → gateway-api · `/api/board/` → web-gateway Next 프록시 → board-api · `/api/game/` → web-gateway Next 프록시(httpOnly 쿠키 → Bearer, 서버 선택) · `/api/game/realtime/` → game-api(SSE, 버퍼링 off) · `/game/` → web-game · `/` → web-gateway · `/health` 헬스 체크.

### 환경변수 (`.env.example`)

`.env.example`을 복사한 뒤 값은 로컬의 git-ignore된 `.env`에만 넣습니다. Compose가 명시적으로 요구하는 이름은 다음 세 개입니다. 값·토큰·비밀번호를 문서나 커밋에 넣지 마세요.

- `JWT_PRIVATE_KEY` — gateway-api만 받는 Base64 PKCS#8 RSA 서명 키입니다.
- `JWT_PUBLIC_KEY` — 세 API가 같은 발급자를 검증하는 Base64 X.509 RSA 공개 키입니다.
- `OPENSAMGUK_WORLD_ID` — game-api와 game-engine이 같은 월드를 선택하는 식별자입니다.

기존 HS256 토큰을 받는 `JWT_LEGACY_*` 값은 소비자 먼저 배포하는 전환 기간에만 사용합니다. 정확한 단계와 중단 조건은 [JWT 키 롤아웃](docs/operations/jwt-key-rollout.md)을 따릅니다.

`POSTGRES_PASSWORD`는 로컬 기본값을 바꿀 때, `ADMIN_PASSWORD`는 관리자 시드를 만들 때
설정합니다. 나머지 데이터베이스·포트·프로필·시나리오 변수의 이름과 기본 동작은
`.env.example` 및 `docker-compose.yml`을 기준으로 확인합니다.

프론트는 각자 `.env.example`을 둡니다(브라우저 비노출 변수는 `NEXT_PUBLIC_` 접두사 없음):

- `web/gateway/.env.example` — `GATEWAY_API_URL`(서버 전용 프록시 대상), `NEXT_PUBLIC_GAME_URL`(로비 입장 링크).
- `web/game/.env.example` — `GAME_API_URL`·`GATEWAY_API_URL`(서버 전용), `NEXT_PUBLIC_GATEWAY_URL`(미인증 시 로그인 리다이렉트 대상).

관리자 시드용 `ADMIN_USERNAME`/`ADMIN_PASSWORD`는 gateway-api에 주입합니다(코드/리포에 평문 비밀번호 하드코딩 금지).

### 프로덕션 (GCP Compute Engine e2-standard-2)

운영 오케스트레이션 정본은 별도 저장소 [`opensamguk-docker`](https://github.com/peppone-choi/opensamguk-docker)입니다. 이 앱 저장소는 GHCR 이미지를 빌드·푸시하고, GCP VM의 `gcp-prod` self-hosted runner에서 docker repo main을 동기화한 뒤 **공유 스택**(`gateway-api`, `web-gateway`, `nginx`, `deployer`)만 자동 갱신합니다.

- 첫 설치 직후에는 게임 서버가 0개여도 정상입니다. 공유 스택만 먼저 뜰 수 있어야 합니다. 런타임 정본은 `game_server` 테이블(`ServerRegistry.kt`가 요청 시점 DB 조회)이고, `SERVER_REGISTRY_JSON`은 **테이블이 비어 있을 때만** 최초 시드로 쓰입니다.
- 실행 중인 게임 서버의 `servers/<id>.env` 버전 핀(`IMAGE_TAG`, `WEB_GAME_TAG`)은 앱 CI가 수정하지 않습니다.
- 게임 서버 승격은 어드민/deployer에서 서버별로 하거나, 리셋·재시드·새 기수 시작 같은 명시 운영 시점에 수행합니다.
- `deployer` 자체는 docker repo main 동기화 후 공유 스택에서 자동 rebuild/recreate됩니다.
- 이 저장소의 단일 compose(`docker-compose.production.yml`)와 `scripts/deploy.sh`는 **호환 전용** 표면이며 현재 프로덕션 제어면이 아닙니다. 멀티서버 운영·서버 승격은 docker repo의 `docker-compose.shared.yml` + `docker-compose.server.yml`와 승인된 운영 절차를 따릅니다.

---

## 시나리오 시드

게임 루프는 부팅된 **DB 스냅샷만 읽습니다**. 다만 fresh/빈 DB에서는 `ScenarioSeedRunner`가 외부 시나리오 디렉터리를 먼저 확인하고, 없으면 classpath 시나리오로 폴백해 한 번 시드합니다.

- **시드 주체**: `app/game-engine`의 `ScenarioSeedRunner`(`SeedBootstrap.ensureSeeded`) — `ApplicationRunner`로 부팅 시 실행.
- **configured-world admission / 멱등**: 빈 DB에는 설정된 `OPENSAMGUK_WORLD_ID`만 admission합니다. 같은 구성 월드의 재호출은 no-op이지만, 다른 월드 또는 혼합된 world identity 집합은 조용히 건너뛰지 않고 거부합니다.
- **임포터**: `infra`의 `ScenarioImporter`(+`ScenarioJson`)가 선택된 `scenario_*.json`과 대응 `map/<mapName>.json`을 opensamguk 스키마 행으로 매핑해 **JDBC INSERT**(`world_state, nation, city, general, general_turn, nation_turn, diplomacy, rank_data, ng_games`).
- **부팅 배선**: `WorldSnapshotLoader`가 DB → `InMemoryTurnWorld` 스냅샷을 구성(시드 직전 방어적으로 `ensureSeeded` 재호출).
- **JDBC-only — one-daemon-write 규칙 비위반**: `JdbcTemplate`만 사용(Flyway/AdminSeeder와 동일 범주). JPA `EntityManager`나 `ChangeRecorder`를 쓰지 않으며, 아키텍처 테스트 write-path scan(`opensamguk.engine.{flush,turn,run}`) 밖인 `opensamguk.engine.boot` 패키지에 위치합니다.
- **env fence**: `SCENARIO_SEED_ENABLED`(로컬 `.env.example` 기본 `true`, production compose 기본 `false`) · `SCENARIO_CODE`(기본 `scenario_1010`) · `SCENARIO_DIR`(외부 JSON 우선 디렉터리).

> `scenario_1010`은 시나리오 JSON과 `map/<mapName>.json`을 함께 읽어 **94도시** 결과를 구성합니다. 게이트: configured-world admission, `general`/`city`/`nation` 행 > 0, 엔진 부팅·턴 진행. 이는 빠른 플레이와 동결 회귀 검증을 위한 기존 시드이며, 신규 한나라 세계 목표와는 구분합니다.

### RTK14 전체 장수 데이터

`tools/rtk14/build_rtk14_stats.py`는 RTK14 원본의 모든 행과 열을 저장소의 모든 런타임 `scenario_*.json`에 반영합니다. 기존 장수는 통솔·무력·지력·정치·매력과 생년·등장년·몰년을 원본 값으로 교체하면서 소속·도시·관직·전용 대사 같은 시나리오 고유 정보는 보존합니다. 동명이인은 장수 번호, 능력치 지문, 생몰년, 별칭·자를 함께 사용해 1:1 배정하고, 원본에만 있는 장수는 빙의 가능한 기본 장수 풀에 추가합니다. 원본에 없는 시나리오 전용 인물의 정치·매력은 검토된 로컬 override를 사용합니다.

확장 tuple은 정치·매력(14/15), 등장년(16), 장수 번호·성별·수명·활동기간·5능력 합계·주의(17–22), RTK14 신규 추가 표식(23)을 보존합니다. 비공개 생성기는 기존 RNG 재현용 `legacyActiveAtStart` 표식(24)도 함께 기록합니다. RTK14 행은 `등장년 <= 현재년 <= 몰년`일 때만 활성화되며, 등장년이 시나리오 시작 뒤면 해당 연도 1월에 예약 등장합니다. 기존 PHP tuple은 원래의 `생년+14`·몰년 배타 규칙과 RNG 순서를 그대로 유지합니다. 원본과 생성물은 모두 gitignored이며 저장소에 커밋하지 않습니다.

```bash
python3 tools/rtk14/build_rtk14_stats.py \
  --xlsx "/path/to/삼국지14 무장정보.xlsx" \
  --scenario-dir infra/src/main/resources/scenario \
  --out-dir /data/scenarios
```

프로덕션 workflow는 `RTK14_STATS_JSON_B64` secret의 gzip+base64 source JSON과 checkout된 시나리오를 결합해 이미지의 classpath 시나리오를 재생성합니다. 멀티서버 compose는 `SCENARIO_HOST_DIR`을 game-engine의 읽기 전용 `SCENARIO_DIR`에 마운트할 수도 있으며, 외부 파일이 없을 때 classpath 시나리오를 사용합니다.

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

### v1 로컬 Docker 수용 게이트

로컬 값만 사용해 격리 Compose 프로젝트를 만들고, 인증 가입/로그인, 장수 생성,
daemon terminal 결과, route 렌더, 엔진 재기동 뒤 영속성을 확인합니다. 실제
운영 데이터·기존 Compose 프로젝트를 재사용하거나 삭제하지 않으며, 완료 후
인증 fixture와 컨테이너를 복원/정리합니다.

```bash
# JWT_PRIVATE_KEY·JWT_PUBLIC_KEY와 OPENSAMGUK_WORLD_ID에는 로컬 .env의 값을 직접 넣습니다.
# 값 자체를 셸 기록이나 문서에 남기지 마세요.
OPENSAMGUK_WORLD_ID=<local-world-id> JWT_PRIVATE_KEY=<local-private-key> JWT_PUBLIC_KEY=<local-public-key> \
  E2E_ENABLE_AUTH=true \
  E2E_ARTIFACT_DIR="$PWD/.omo/evidence/local-v1-manual" \
  ./tools/e2e/local_v1_gate.sh
```

이미지를 미리 빌드한 경우에만 `E2E_SKIP_BUILD=true`를 추가합니다. 이 경우
gate는 기존 이미지를 덮어쓰지 않고 격리 이름으로 tag합니다.

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

Codex 사용자 관점의 프로젝트 열기, 업무 요청, 구현·검증·리뷰, skills.sh 스킬 탐색, MCP, commit·배포 승인 절차는 [`docs/agent/codex-user-manual.md`](docs/agent/codex-user-manual.md)에 정리되어 있습니다.

- `skills-lock.json`은 skills.sh에서 설치한 프로젝트 스킬 목록을 고정합니다. Next.js/React 지침은 `next-best-practices` 대신 공식 Vercel 출처의 `vercel-react-best-practices`를 사용합니다.
- 다운로드한 외부 `.agents/skills/*`는 로컬 실행 표면이며 git-ignore입니다. 새 환경에서는 `scripts/agent/project-skills.sh restore`로 복원하고, Codex는 프로젝트 `SessionStart` 훅에서 같은 복원을 자동 실행합니다.
- Codex의 `.codex/config.toml`, `.codex/hooks.json`, `.codex/agents/*.toml`과 저장소 운영용 `$os-*`·`$find-project-skill` 프로젝트 스킬은 추적됩니다. Claude의 `/os-*`와 Codex의 `$os-*`는 동일한 `docs/agent/` 절차를 실행합니다.
- 작업에 필요한 전문 스킬이 없으면 Codex에서 `$find-project-skill`로 skills.sh 후보를 검색·검토한 뒤 프로젝트에만 설치합니다. 전역 설치는 기본값이 아닙니다.
- Claude 훅은 `.claude/settings.json`, Codex 훅은 `.codex/hooks.json`에 배선돼 있습니다. Codex에서 저장소를 처음 열 때 훅을 신뢰하고, 설정·훅 변경 뒤에는 reload/restart해야 합니다.
- provider/model 공통 개발도구는 `tools/agent-system/check.py`입니다. 로컬은 `tools/agent-system/check.py`, PR/CI는 `tools/agent-system/check.py --strict --base origin/main`, 에이전트 통합은 `--format json`을 사용합니다.
- 비자명 작업은 구현자와 별개 agent/provider의 비판적 검증을 거칩니다. 병렬 agent는 서로 구현 증거·테스트·문서·운영 불변식을 공격적으로 검토하고, `fix-required`가 남아 있으면 ship/merge하지 않습니다.
- 백엔드 표준 게이트는 `tools/parity/gate.sh backend`입니다. Java 21로 Gradle을 실행하고 `BUILD SUCCESSFUL` 및 테스트 XML의 `failures=0 errors=0`을 확인합니다.
- PHP 레거시 분석이 필요한 회귀 작업은 `legacy/devsam-core` 소스 경로와 line range를 먼저 잡고, 실제 캡처가 필요한 동작은 `tools/php-golden/`로 증거를 만든 뒤 Kotlin/Next 구현과 비교합니다.
- 외부 스킬은 보조 지식입니다. 최신 ADR, `CLAUDE.md`, `AGENTS.md`, one-daemon-write 규칙과 충돌하면 저장소 규칙이 이깁니다.

---

## 프론트엔드 / 배포 (F0–F5)

P7 프론트 + P8 시드/배포를 점진적으로 닫는 F-시리즈. 계획서: [`docs/superpowers/plans/2026-06-02-frontend-parity-and-scenario-seed-plan.md`](docs/superpowers/plans/2026-06-02-frontend-parity-and-scenario-seed-plan.md). `hwe/ts/` Vue는 기존 흐름을 이해하는 참고 자료이며, 현재 구현과 승인된 디자인 시스템이 신규 UI의 기준입니다. 인증은 JWT 로컬(Kakao 제거)을 의도적 변경으로 확정했습니다.

| 단계 | 내용 | 상태 |
|------|------|------|
| **F0 게이트웨이 인증** | `web/gateway` 엔트런스/로그인/회원가입/로비/어드민. JWT를 Next route handler 프록시 + httpOnly 쿠키(`sam_access`/`sam_refresh`)로 연결. `AdminSeeder`로 peppone(role=ADMIN) 자동 생성. | ✅ |
| **F1 시나리오 시드** | `ScenarioImporter` + `ScenarioSeedRunner` → 외부 `SCENARIO_DIR` 우선, classpath 폴백으로 모든 시나리오를 시드. 로컬 fresh DB 자동 시드 가능, production은 관리자 서버 생성 전 기본 비활성. | ✅ |
| **F2 메인화면 + 메뉴 척추** | `web/game` 메인 화면(`GameChrome` = GameInfo 헤더 + GlobalMenu + MainControlBar + 메인 보드). path-server, SSE, 맵 크기/링크/현재 위치/툴팁은 실서버 루프로 지속 수렴 중. | ✅ |
| **F3 read API + 랭킹/내정보** | game-api read 컨트롤러 + `web/game` 랭킹(`a_*`)·내정보(`b_*`) 페이지. game-api read 데이터 렌더가 기본 완성선. | ✅ |
| **F4 액션 페이지 + mutation** | 예약·서신·베팅·경매·외교·게시판·투표·유산·NPC 정책·토너먼트·장수 선택 풀을 실제 intake/daemon 경로로 닫고, 202 접수와 terminal 결과를 구분한다. PHP matrix·local Docker E2E로 v1 비운영 감사 범위를 확인했다. | ✅ (v1 비운영 범위) |
| **F5 turnkey + docs** | 로컬 compose·이미지·문서·인증 브라우저/재시작 gate까지는 완료했다. 실제 운영 오케스트레이션은 `opensamguk-docker`의 shared/server/deployer 모델이며 S6/cutover는 별도 미수행이다. | ✅ 로컬 / ⬜ 운영 |

> **상태 표기 주의**: F0–F3는 기본 동선 사용 가능, F4는 실제 mutation을 포함합니다. 기존 회귀 결함은 근거 캡처와 재현 루프로 확인하고, 신규 기능은 승인 spec과 실제 API·daemon 결과로 검증합니다.

---

## 회귀·아키텍처 규율

신규 제품 기획에서도 유지하는 여섯 가지 규칙입니다. 상세 결정은 `.ai/decisions.md`의 ADR-LITE-042를 따릅니다.

1. **결정론적 재현** — 같은 seed·입력·실행 순서에서 같은 결과를 재현한다. 신규 기능에 PHP draw-for-draw 일치를 요구하지는 않는다.
2. **수치 변경의 의도성** — 반올림·절삭·clamp를 바꾸면 기획 근거와 회귀 영향을 기록하고 테스트한다. 기존 `PhpRound` 사용처는 의도 없이 바꾸지 않는다.
3. **로그의 순서와 품질** — 사용자에게 보이는 한글 로그와 실행 순서는 안정적으로 유지한다. byte 일치가 필요한 동결 기준선과 신규 UX 문구를 구분한다.
4. **델타 flush, 인라인 write 금지** — 변경은 `ChangeRecorder`에 `created`/`dirty`/`deleted`로 기록 후 일괄 flush한다. 리졸버는 델타만 쓴다.
5. **날조 금지** — 수치·로그·골든·테스트 결과를 만들지 않는다. 기존 테스트를 약화해 신규 설계를 통과시키지 않는다.
6. **순서 보존** — 결과에 영향을 주는 map과 이벤트의 삽입·실행 순서를 명시적으로 보존한다.

---

## 감사 / 라이선스

이 저장소는 **HideD**님이 만드신 PHP 원작 게임 **devsam** — 삼국지 모의전투 HiDCHe(삼모) — 없이는 존재할 수 없었습니다. 원작의 게임 메커니즘·계산·로그·운영 경험은 오픈삼국의 역사적 토대이며, 초기 이식 결과는 지금도 중요한 동결 회귀 기준선입니다.

- 원작 소스: **devsam** — <https://storage.hided.net/gitea/devsam>
- 원작 `devsam/core`는 **MIT 라이선스**로 공개되어 있습니다. opensamguk은 그 출처와 저작자 표시를 유지하며, 파생물로서 MIT 라이선스의 정신을 존중합니다.

> HideD님의 노력 없이는 이 저장소도 없었습니다. 깊이 감사드립니다 — 이 프로젝트의 토대 전부가 그분의 헌신에서 비롯되었습니다.

---

*최종 갱신: 2026-08-20 · ADR-LITE-042 제품 기준 전환과 문서 포털·사용자·관리자·기획 매뉴얼 반영. 2026-07-30 v1 회귀 기록은 역사적 증거로 보존합니다. S6/프로덕션 cutover는 미수행이며, `main` 반영은 별도 승인 대상입니다.*
