---
paths:
  - "{common,logic,infra,app,web}/**"
  - "**/*.{kt,kts}"
---

# 아키텍처·모듈·코드 스타일

[작업 참고](../../docs/development/agent-reference.md)에서 옮긴 경로별 규칙이다. 경로와 명령은 저장소 루트 기준이다. Codex는 자동으로 읽지 않으므로 작업 참고 표를 보고 직접 읽는다. 이 규칙은 명령 실행 승인을 대신하지 않는다.

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

### 층과 의존 방향 (ADR-LITE-070)

새 코드는 아래 표를 지킨다. 옛 코드의 위반은 기준선 수로 잡고 줄여 간다.
위반 수를 세는 검사는 셋이다.
- `tools/ci/arch_lint.py`(`naming-lint` 잡, **막음**): 한 커맨드 한 파일 · 크기 · 죽은 코드 · 동결 패키지 · 화면 → api 클라이언트 · raw fetch.
- ArchUnit(JVM 시험 잡, 보고만): 백엔드 층 의존(아래 표의 「가져오면 안 됨」 열).
- `tools/ci/depcruise_counts.py`(`web-shared` 잡, **막음**): dependency-cruiser 로 프론트 순환 · shared → 앱 · game ↔ gateway · 역방향 의존 · 뷰모델 · Parts → api. 판정은 기준선(병합 기준 비교 없음 — 그 커밋의 node_modules 가 필요하다)과 새 파일 규칙이다.
- 막기 전인 규칙은 리뷰가 본다.

막는 검사의 판정은 두 겹이다.
- 실측 ≤ min(기준선, 병합 기준 커밋 실측)이어야 한다.
- PR 이 **새로 더한 파일은 위반 0** 이어야 한다(이름만 옮긴 파일은 새 파일이 아니다). 시행일은 그 검사의 **래칫 PR 병합 시각**이다. 검사 파일의 `NEW_FILE_RULE_MARKER` 가 main first-parent 이력에 처음 들어온 커밋 시각을 git 에서 읽는다(`tools/ci/ratchet.py` 의 `rule_active_since`). 그 전에 연 PR 은 안내(NOTE)만 받는다.
- 기준선 내리기는 따로 하는 래칫 PR(`--write-baseline`)로만 한다.

```
web ─▶ application ─▶ domain ◀─ adapter
```

| 층 | 위치(기존 모듈·패키지) | 가져와도 됨 | 가져오면 안 됨 |
|---|---|---|---|
| web | `*Controller`(game-api·gateway-api·board-api·engine.status), 엔진 Redis 명령 수신(`engine.run.TurnDaemonCommandDispatcher`) | 같은 모듈 application, `*Dto`, `common` | `*Repository`·`*Reader`·`JdbcTemplate`·`RedisTemplate`·`EntityManager`, `opensamguk.infra..`, `opensamguk.logic..`(규칙 직접 호출), 다른 컨트롤러 |
| application | game-api `*Service`·`*Query`·`*Admission`·`*OptionsService`, engine `<도메인>.command.*Handler`·`*Executor`·`*NpcSelector` | `:logic`, `:common`, 같은 모듈 adapter, 엔진 상태(`InMemoryTurnWorld`·`ChangeRecorder`) | web 층, engine 에서 `opensamguk.infra..` 행 타입·seed 로더 직접 사용, 다른 app 모듈 |
| domain | `:logic`, `:common` | Kotlin 표준, kotlinx.serialization, `:common`, 지정 로더(`*Catalog`·`*Design`)를 거친 번들 정적 리소스 | Spring·JPA·JDBC·Redis·`infra`·`app`, 벽시계(`Instant.now`·`System.currentTimeMillis`)·환경변수·파일 I/O, `common.rng` 밖 난수 |
| adapter | `:infra`, game-api `*Reader`·`*Repository`, engine `flush`·`redis`·`boot`·`config` | domain, `:common`, Spring/JDBC/Redis | web·application(같은 모듈 포함) |

- 엔진 쓰기는 `InMemoryTurnWorld` + `ChangeRecorder` → `JdbcFlushExecutor`만 쓴다. 읽기도 `Controller → *Query → *Reader`를 거친다(얇아도 Query 를 둔다).
- 패키지 순환은 0이 목표다. 새 코드는 `opensamguk.<모듈>.<도메인>`(ADR-LITE-066 목록)에 둔다. 수평 패키지 `gameapi.controller`·`gameapi.web`·`gameapi.dto`·`gameapi.read`·`engine.campaign`·`logic.input`에는 새 파일을 더하지 않는다.

```
app/**/page.tsx ─▶ components/<기능>/*Screen.tsx ─▶ hooks(use*) ─▶ lib/<기능>/*-view.ts · lib/api/* ─▶ @opensamguk/ui
   (얇게)              (컨테이너)          │                (순수 뷰모델)   (fetch 는 여기만)    (web/shared)
                        └─▶ *Parts.tsx(표시, props 전용)
web/gateway app/api/**/route.ts = BFF(서버) — 상류 fetch 허용, 클라이언트 코드에서 import 금지
```

| 프론트 층 | 가져와도 됨 | 금지 |
|---|---|---|
| route `page.tsx`·`layout.tsx` | Screen, 훅, shared | api 클라이언트·fetch, 업무 규칙(파라미터 해석만) |
| Screen(컨테이너) | 훅, Parts, 뷰모델, shared | `fetch`, api 클라이언트 직접 import |
| Parts·표시 컴포넌트 | 다른 Parts, shared, 뷰모델의 순수 함수 | 훅(UI 로컬 상태 제외), api, fetch |
| 훅 `use*`(`hooks/` 또는 `lib/<기능>/use-*.ts`) | api 클라이언트, 뷰모델 | JSX 컴포넌트, raw fetch(api 클라이언트로) |
| 뷰모델 `*-view.ts` | 타입, 순수 유틸 | React, api, fetch |
| api 클라이언트 `lib/api/*`(기존 `api.ts`·`*-reads.ts`) | 전송 유틸(`fetchGame` 등), 타입 | 컴포넌트·훅 |
| shared `@opensamguk/ui` | 자기 안 | web/game·web/gateway import, 게임 API 호출(정적 자산 로더 제외) |

- 값 import 파일 순환 0, shared → 앱 0, game ↔ gateway 0.
- 새 API 함수는 `api.ts`에 더하지 않고 `lib/api/<도메인>.ts`에 둔다.

## 코드 스타일

- Kotlin official(`kotlin.code.style=official`). 들여쓰기: `.kt`/`.kts` 4칸, `.ts`/`.tsx`/`.json`/`.yml` 2칸.
- UTF-8, LF, 끝 개행 필수(`.editorconfig`). 패키지 소문자 `opensamguk.<module>`. 클래스 PascalCase, 함수/변수 camelCase.
- 주석은 영어, 게임 콘텐츠 문자열은 한글. 테스트명은 backtick 서술형.
- 한 커맨드 한 파일(ADR-LITE-070): 한 파일에는 커맨드가 하나다. 한 커맨드는 모듈마다 최대 한 파일 — `:logic` `<도메인>/command/<Name>Command.kt`(정의·인자·검증·순수 효과), `:app:game-engine` `<도메인>/command/<Name>Handler.kt`(상태 적용·채널), 커맨드 고유 접수 규칙이 있을 때만 `:app:game-api` `<Name>Admission.kt`. 원장 inputId 문자열 리터럴은 그 커맨드 파일에만 쓴다(생성 파일·색인·시험은 `tools/ci/arch_lint_allowlist.json`에 사유와 함께). 프론트는 `web/game/lib/commands/<도메인>/<명령>.ts` 하나에 명령 명세를 두고, 공용 흐름 UI 에 inputId 분기를 두지 않는다. 새 `TurnDaemonCommand` 변형은 만들지 않는다.
- 역할 접미사: `Controller`·`Service`·`Query`·`Admission`·`Handler`·`Executor`·`NpcSelector`·`Command`·`Reader`·`Repository`·`Dto`, 프론트 `*Screen`·`*Parts`·`*Form`·`*-view.ts`·`use*`.
- 크기: 새 파일은 측정 p95 이하, 새 함수는 측정 p99 이하다(Kotlin 436 · 119줄, web game 300 · 169, gateway 230 · 174, shared 414 · 129). 기존 초과 수와 쓰이지 않는 private 멤버 · export 수는 늘지 않는다. 지금 수는 `python3 tools/ci/arch_lint.py --counts`, 종류별 목록은 `--list <종류>`.
