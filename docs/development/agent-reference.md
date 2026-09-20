# OpenSamguk 작업별 참고

아래 경로와 명령은 저장소 루트 기준이다. 작업 경계·불변식은 준수하고 나머지는 해당 작업의 절만 읽는다.

Kotlin/Spring Boot + Next.js + PostgreSQL + Redis 기반의 메모리 중심 CQRS 턴제 게임이다. 제품 정본은 최신 승인 ADR·spec과 현재 구현이다. PHP/TS `legacy/`는 역사·구조·동결 회귀 조사용이며 신규 기능의 정답이 아니다(ADR-LITE-042).

## 작업 경계

- 메타레포에서 작업할 때는 메타레포의 `bin/start-task opensamguk <task>`가 만든 worktree를 사용한다. 원본 `projects/opensamguk` checkout과 다른 작업의 변경을 보존한다.
- 승인된 범위의 로컬 편집과 필요한 검증, 이번 변경 때문에 발생한 실패 수정은 이어서 완료한다. 검토 전용 요청은 편집 허가가 아니다.
- commit/push/merge/deploy/데이터 삭제·재시드는 해당 행위와 대상에 대한 사용자 승인이 필요하다. 이미 받은 승인은 같은 범위에서 다시 묻지 않는다. 운영 권한이 필요한 부분만 분리하고 안전한 로컬 작업은 계속한다.
- `.env*`·키·토큰·인증 파일의 실제 값을 읽거나 출력하지 않는다. 공개 템플릿 `.env.example`로 계약을 확인한다. 저장소는 IP 검토 전까지 비공개이며 비밀값과 `legacy/`를 커밋하지 않는다.
- 이미지 원본·생성기·미리보기의 정본은 `opensamguk-images`, 앱에는 배포용 export만 둔다. 자체 브랜드 자산은 `assets/brand/README.md`의 별도 계보를 따른다. RTK14 초상 CDN 사용 예외(ADR-LITE-048)와 CHGIS 격리·서빙 범위(ADR-LITE-039/040)는 `docs/development/product-reference.md`의 해당 절을 확인한다. 예외를 다른 자산의 반입 허가로 넓히지 않는다.

## 제품·아키텍처 불변식

1. 같은 seed·입력·순서는 같은 결과를 재현해야 한다. 결과에 영향을 주는 map·이벤트 삽입/실행 순서를 보존한다.
2. 반올림·절삭·clamp와 `PhpRound` 변경은 의도와 회귀 영향을 기록한다.
3. 한글 로그는 UX 산출물이다. 실행 순서를 안정적으로 보존하고 의도한 문구 변경을 기록한다.
4. 데몬 write는 `ChangeRecorder`의 `created`/`dirty`/`deleted` → `JdbcFlushExecutor` JDBC 배치만 허용한다. JPA `EntityManager` write와 리졸버 인라인 write를 금지한다. 부팅 `ScenarioSeedRunner`·`AdminSeeder`의 `JdbcTemplate` 예외는 유지한다.
5. 골든·테스트·명령·검증 결과를 날조하거나 합격을 위해 약화하지 않는다. 동결 회귀 기대값 변경에는 명시적 기획 근거와 회귀 증거가 필요하다. 신규 기능에 PHP 캡처를 선행 조건으로 두지 않는다.
6. 서버 목록은 관리자 생성 런타임 데이터다. 빈 목록에 가짜 서버·지도·로그·탭을 만들지 않는다. 버튼 노출이나 HTTP 202 수신만으로 명령 실행 성공을 선언하지 않는다.

## 작업별 문서와 검증

아래에서 작업에 해당하는 절만 확인한다. `CLAUDE.md`는 Claude의 상시 진입점이며 Codex 자동 적용을 가정하지 않는다. 제품·아키텍처 상세는 `docs/development/product-reference.md`의 해당 절로 직접 연결한다.

| 작업 | 참고·검증 |
| --- | --- |
| 모듈·서비스 경계, 스타일 | `docs/development/agent-reference.md`의 모듈 구조·코드 스타일 |
| 백엔드 | 같은 문서의 빌드·테스트 절. JDK 21로 영향 모듈 테스트; 광범위 변경은 `tools/parity/gate.sh backend`. 출력과 XML을 확인하고 Docker 미사용으로 skip된 통합 테스트를 합격으로 세지 않는다. |
| 데몬·flush·precheck | `DaemonNoEntityManagerTest`, `InfraNoEntityManagerTest`, `PrecheckFullCrossCallSiteTest` 및 변경 경로의 테스트 |
| `data/map/han-tiles.json`·`han-world-v3.json` | `tools/map/check_han_tiles_coupled.py`가 결합 산출물 정본이다. `--regenerate` 후 산출물을 함께 반영하고 `--check --include-slow`로 검증한다. 머지 전 main과 합친 결과를 다시 검사한다. 새 han-tiles 검사 도구도 결합 목록에 등록한다. |
| 역사 주장·지명·관직·사료 | `.claude/skills/historical-sources/SKILL.md`를 직접 읽는다. 경로를 유지하며 Codex 자동 발견을 가정하지 않는다. 정사·연의 등급과 미확인 범위를 구분한다. |
| UI·브랜드 | `docs/development/product-reference.md`의 UI 정본·브랜드 에셋 절, `docs/design/ui-redesign-2026-09/`, `assets/brand/README.md`. 영향 앱의 검사와 실제 화면·조작을 확인한다. |
| 운영·배포·도달성 | `docs/superpowers/WORKING_SYSTEM.md`의 Production policy와 작업별 참고의 배포 절. 라이브 nginx 정본은 `opensamguk-docker/infra/nginx/nginx.conf`; 앱의 `infra/nginx/default.conf`를 운영 도달성 근거로 쓰지 않는다. |
| 제품 규칙·출처·라이선스 | `docs/development/product-reference.md`의 관련 절과 `.ai/decisions.md`의 해당 ADR. 승인 상태를 확인하며 임의로 approved로 바꾸지 않는다. |
| 작업 재개·인수인계 | `.ai/README.md`와 해당 작업 상태. 과거 task/handoff를 현재 요청으로 간주하지 않는다. |

완료 전 구현·문서가 일치해야 한다. 플레이어 규칙은 `docs/user/`, 운영은 `docs/admin/`, 제품 방향은 `docs/design/`와 승인 spec에 반영한다. 세부 소유권은 작업별 참고의 살아 있는 문서 규칙을 따른다. `V1`/`V2`는 내부 식별자이며 사용자용 제품명으로 노출하지 않는다. 문서만 수정했다면 링크·형식·diff 등 관련 검증을 수행하고 불필요한 전체 빌드를 반복하지 않는다.

결과에는 실제 변경, 실행한 검증, skip/미검증과 남은 위험을 구분한다. 확인하지 못한 사실은 `UNKNOWN`이다. 메타레포 작업 report에 결과와 변경 커밋(없으면 미커밋), 검증, 남은 위험을 남긴다.

## 제거된 운영 체계

ADR-LITE-047 이후 프로젝트의 `.codex/`, `.agents/skills`, `docs/agent/`, `skills-lock.json`, `tools/agent-system/`와 `/os-*` 어댑터는 없다. 과거 기록의 명령을 복원하거나 필수 단계로 실행하지 않는다. 남아 있는 Claude 보호 훅 `scripts/agent/protect-sensitive-files.sh`·`.claudeignore`는 유지하며 Codex에도 같은 훅이 활성화됐다고 가정하지 않는다. 사용 가능한 스킬 중 현재 작업 조건에 맞는 것만 선택한다.

---

필요한 절만 읽는다. 이 문서는 AGENTS.md에서 분리한 구조·검증·운영 참고이며 명령 실행 승인을 대신하지 않는다. 경로와 명령은 저장소 루트 기준이다.

## 모듈 구조

`settings.gradle.kts`에 선언:

| 모듈 | 종류 | 포트 | 책임 |
|------|------|------|------|
| `common` | 라이브러리 | — | RNG 커널(`rng/LiteHashDrbg`+`RandUtil`+`SeedSerializer`), `util/PhpRound`, `log/*`(조사·ConvertLog·토큰), `constants/GameConst` |
| `logic` | 라이브러리 | — | 순수 게임 로직(Spring/DB 없음): `stats/ActionPipeline`, `actions/*`+`CommandRegistry`, `war/*` 전투, `ai/*` GeneralAI, `event/*` DSL, `tick/*`, 베팅·경매·유산·메시지 |
| `infra` | 라이브러리 | — | `JdbcFlushExecutor`(JDBC 전용 flush + 델타/툼스톤 + row mapper), Flyway `db/migration/V*.sql`, Redis, JPA read repository, `seed/ScenarioImporter` |
| `app:gateway-api` | Boot 앱 | `:8080` | 인증(JWT/BCrypt) · 프로필 · 어드민(`AdminSeeder`) |
| `app:board-api` | Boot 앱 | `:8083` | 게시판 read/write · gateway 발급 access JWT 검증 · 공유 users DB 조회 |
| `app:game-api` | Boot 앱 | `:8081` | read + precheck + 명령 intake + SSE 릴레이 |
| `app:game-engine` | Boot 앱 | `:8082` | 턴 데몬: `InMemoryTurnWorld`·`ChangeRecorder`·`MonthlyPipeline`·`TurnRunService`, `boot/ScenarioSeedRunner`+`WorldSnapshotLoader` |
| `web/gateway` | Next.js | `:3000` | 게이트웨이 프론트(로그인/로비/어드민) |
| `web/game` | Next.js | `:3001` | 게임 프론트(인게임 UI) |

아키텍처:

```
api ──Redis(XADD)──▶ game-engine daemon ──JDBC batch flush──▶ PostgreSQL
 ▲                   (InMemoryTurnWorld = source of truth)         │
 └──────────── turnCompleted SSE ◀── ChangeRecorder dirty/created/deleted
```

## 빌드 · 테스트 명령

**전제**: JDK 21 LTS(Gradle 8.12는 Java 25+ 파싱 실패), Docker, Node 20 + pnpm(corepack). **Gradle 명령은 repo root에서, `JAVA_HOME`을 21로 고정.**

```bash
# 전체 빌드 + 테스트
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build

# 동결 회귀 백엔드 표준 게이트(XML 검증 포함, 명령명은 역사적으로 parity 유지)
tools/parity/gate.sh backend

# 단일 모듈
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test

# 광범위한 백엔드 변경에 필요한 풀 체크
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test :app:board-api:test

# Docker 스모크 (이미지 빌드 + 전체 스택 + health 단언)
./tools/smoke.sh

# 프론트 dev
cd web/gateway && corepack pnpm dev   # :3000
cd web/game    && corepack pnpm dev   # :3001
```

### Gradle 결과 판정

context-mode 래퍼를 사용하는 호스트에서는 `task-notification` exit 0만으로 성공을 판단하지 않는다. 실제 빌드 출력과 테스트 결과를 확인한다:

- 출력 tail로 검증: `... 2>&1 | tail -40` 후 `BUILD SUCCESSFUL` + 테스트 카운트 grep
- 또는 테스트 결과 XML: `**/build/test-results/test/*.xml`
- UP-TO-DATE false-green 의심 시 `--rerun-tasks`

### Testcontainers (macOS)

`infra`·`app:game-api`·`app:game-engine`의 `tasks.test`에 배선: `api.version=1.44`, `DOCKER_CONTEXT=default`, `TESTCONTAINERS_RYUK_DISABLED=true`. **Docker 미사용 시 통합 테스트는 fail이 아니라 skip.**

### han-tiles 를 바꾸는 PR (GH #818)

`data/map/han-tiles.json`·`han-world-v3.json` 을 바꾸면 거기에 묶인 커밋 산출물이 낡는다. 결합 목록과 재생성 명령의 정본은 `tools/map/check_han_tiles_coupled.py` 다.

- [ ] `python3 tools/map/check_han_tiles_coupled.py --regenerate` 를 돌리고 바뀐 산출물을 같은 PR 에 넣는다.
- [ ] `python3 tools/map/check_han_tiles_coupled.py --check --include-slow` 출력을 PR 본문에 붙인다. 「사람 판정」 항목이 STALE 이면 지목된 원장·노트를 검토해 고친다.
- [ ] 머지 직전에 main 을 다시 합쳐 한 번 더 돈다 — 각 PR 의 CI 는 제 merge ref 에서만 초록이라, 거의 동시에 머지되는 타일 PR 끼리는 서로를 못 본다.
- han-tiles 를 읽는 `--check` 도구를 새로 만들면 목록에 넣는다(안 넣으면 `test_check_han_tiles_coupled.py` 가 빨개진다).

## 살아 있는 문서 규칙 (NON-NEGOTIABLE)

문서 갱신은 출시 직전 정리 단계가 아니라 구현 작업의 일부다. 코드·데이터·운영 방식이 바뀌면 같은 이슈와
PR에서 영향을 받는 정본을 함께 수정한다.

- `README.md`: 처음 방문한 사람을 위한 공개 소개, 현재 제공 범위, 실행 방법이 바뀔 때 수정한다. 내부
  대화, 임시 판단, 에이전트 전용 용어, 비공개 운영 정보를 넣지 않는다.
- `V1`·`V2`는 코드·마이그레이션·회귀 기준선에 남아 있는 내부 식별자일 뿐 사용자용 제품명이 아니다.
  공개 제목과 사용자 문서에서는 `기존 구현`, `현재 구현`, `신규 기능`처럼 실제 의미를 쓴다.
- `docs/user/**`: 플레이어가 보는 규칙, 화면, 튜토리얼, 도움말, 성공·실패 조건이 바뀔 때 수정한다.
- `docs/admin/**`: 서버 운영, 초기화, 백업, 복원, 권한, 장애 대응이 바뀔 때 수정한다.
- `docs/design/**` 및 승인 spec: 제품 방향, 도메인 의미, 출시 관문이 바뀔 때 수정한다.
- `CLAUDE.md`와 `docs/development/product-reference.md`: 장기적인 제품·아키텍처 불변식과 검증 규칙이 바뀔 때 해당 상시 요약·상세 절을 함께 맞춘다.
- `AGENTS.md`: 저장소 구조, 작업 절차, 필수 검증, 문서 책임이 바뀔 때만 수정한다.
- 하위 `README.md`: 해당 모듈·도구·자산의 입력, 출력, 실행법, 소유권이 바뀔 때 수정한다.

파일을 매 작업마다 의미 없이 건드리지 않는다. 영향이 없으면 report에 `docs-impact: none`과 근거를 남긴다.
영향이 있는데 문서를 수정하지 않았거나, 문서가 아직 구현되지 않은 기능을 현재 기능처럼 설명하면 작업은
완료가 아니다. 구현 검증과 함께 링크·명령·화면 문구의 정확성도 확인한다.

## 코드 스타일

- Kotlin official(`kotlin.code.style=official`). 들여쓰기: `.kt`/`.kts` 4칸, `.ts`/`.tsx`/`.json`/`.yml` 2칸.
- UTF-8, LF, 끝 개행 필수(`.editorconfig`). 패키지 소문자 `opensamguk.<module>`. 클래스 PascalCase, 함수/변수 camelCase.
- 주석은 영어, 게임 콘텐츠 문자열은 한글. 테스트명은 backtick 서술형.

## 프론트엔드 / 배포 (F0–F5 과거 단계 기록)

기존 온보딩 표를 보존한 기록이다. 현행 완료 여부는 현재 코드와 승인된 spec으로 확인한다.

| 단계 | 핵심 | 상태 |
|------|------|------|
| **F0 게이트웨이 인증** | gateway-api 자체 JWT/BCrypt(Kakao 제거 divergence). `web/gateway` 로그인/회원가입/로비/어드민. 토큰은 Next route handler 프록시 + **httpOnly 쿠키**(`sam_access`/`sam_refresh`), 브라우저 JS 미노출, 동일출처(CORS 불필요). `AdminSeeder`가 `ADMIN_USERNAME`/`ADMIN_PASSWORD` env로 peppone(role=ADMIN) 멱등 시드. | ✅ |
| **F1 시나리오 시드** | `ScenarioImporter` + `ScenarioSeedRunner`가 외부 `SCENARIO_DIR`의 동일 파일명을 classpath보다 우선해 모든 시나리오를 시드. 정치·매력은 tuple 14/15 원수치, 생성물은 gitignored. env: `SCENARIO_SEED_ENABLED`/`SCENARIO_CODE`/`SCENARIO_DIR`. | ✅ |
| **F2 메인화면 + 메뉴 척추** | `web/game` 메인(`GameChrome` = GameInfo 헤더 + GlobalMenu + MainControlBar 20버튼 + 게이팅). | ✅ |
| **F3 read API + 랭킹/내정보** | game-api read 컨트롤러 + `web/game` 랭킹(`a_*`)·내정보(`b_*`) 페이지, **read-only 렌더**. | ✅ |
| **F4 액션 페이지 + mutation** | 예약·서신·베팅·경매·외교·게시판·투표·유산·NPC 정책·토너먼트·장수 선택 풀을 실제 intake/daemon 경로에 연결. 잔여 명령은 라이브 루프로 폐쇄 중. | 🔄 |
| **F5 turnkey + docs** | 정본 `docker-compose.yml`(로컬) + 호환용 `docker-compose.production.yml`(GCP Compute Engine e2-standard-2/GHCR) + `.env.example` + 한글 `README/AGENTS/CLAUDE`. `git pull && docker compose up`로 자동 설치·시드. | 🔄 |

> 프론트는 read-only 단계를 지났습니다. 버튼이 보이면 실제 API/daemon 결과까지 검증해야 하며, 하드 스텁·상수 빈 응답·disabled dead control은 완료로 세지 않습니다.

## 배포

> **nginx 도달성 규칙:** 라이브 정본은 sibling `opensamguk-docker` 저장소의
> `infra/nginx/nginx.conf`이다. 이 저장소의 `infra/nginx/default.conf`는 배포·마운트·로드되지
> 않는 과거 토폴로지 참고용 파일이므로 프로덕션 라우팅 근거로 인용하지 않는다. 런타임
> 도달성이 중요도·심각도 판정에 영향을 주면 compose 마운트, 배포 동기화 경로, 라이브
> `nginx -T` 중 해당하는 증거를 확인한다. 확인하지 못한 도달성은 `UNKNOWN`으로 보고한다.

```bash
# 로컬 전체 스택 (9서비스: postgres·redis·gateway-api·board-api·game-api·game-engine·web-gateway·web-game·nginx)
docker compose up -d --build

# 프로덕션 호환 표면 (GCP Compute Engine e2-standard-2, GHCR 이미지 풀, POSTGRES_PASSWORD 필수)
docker compose -f docker-compose.production.yml up -d
```

- 백엔드 이미지 멀티스테이지: `gradle:8.12-jdk21` 빌드 → `eclipse-temurin:21-jre` 런타임. 프론트: `node:22-alpine` 빌드(`next build`) → `node:22-alpine` standalone 런타임.
- nginx(`infra/nginx/nginx.conf`) 라우팅: `/api/gateway/`→gateway-api · `/api/board/`→web-gateway Next 프록시→board-api · `/api/game/`→web-gateway Next 프록시(httpOnly 쿠키→Bearer, 서버 선택) · `/api/game/realtime/`→game-api(SSE, 버퍼링 off) · `/game/`→web-game · `/`→web-gateway · `/health`.
- CI/CD: `.github/workflows/deploy.yml`(빌드 → GHCR push → GCP VM의 `gcp-prod` self-hosted runner에서 공유 스택 동기화), 호환용 수동 `scripts/deploy.sh`(헬스 체크 루프). 런타임 외부 API 의존 0, LLM-free.
