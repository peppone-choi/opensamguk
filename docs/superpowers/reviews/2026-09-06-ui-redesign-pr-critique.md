# UI 리디자인 PR #651 교차 비평 (2차) — 코드·마이그레이션

- Date: 2026-09-06
- Target: PR #651 `work/opensamguk/ui-redesign-2026-09` → `main`, **HEAD `a119a57b`** (비평 중 브랜치가 두 번 움직였다: `04551374` → `25b8232f` → `a119a57b`. 아래 file:line 은 전부 `a119a57b` 기준이며, `25b8232f` 로 닫힌 항목은 §1-C 에 따로 적었다.)
- Base: `origin/main` (`git diff --stat origin/main...HEAD` = 512 files, +17580/−3549). `docs/design/**` 와 캡처 PNG 는 범위 밖.
- Plan: `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md`
- Method: 워크트리에서 파일을 직접 열어 대조했다. gradle/vitest 는 돌리지 않았다(제약) — 실행 결과에 기대는 주장은 UNKNOWN 으로 남긴다. 워크트리에 커밋되지 않은 Phase 4X-B 작업(`git status`: `TruncateContract.kt`·`TurnDaemonCommand*.kt`·`BoardActions.kt`·`V32…Test.kt` 수정 + `logic/operation/**` 신규)이 있어 **커밋된 HEAD 만** 판정 대상으로 삼았다(§3 Q4).
- Verdict: **fix-required 1건** — 대표 장수 「해제」 가 API 검증에 막혀 UI 에서 불가능하다(§1 F1). should-fix 9건, 그중 S1(board-api 네이티브 쿼리의 Postgres null 바인딩)은 PLAUSIBLE 이지만 커뮤니티 목록 전체가 걸린 항목이라 **PG 적색 프로브 없이 머지하지 말 것**을 권고한다.

---

## 0. 범위 · 확인한 것 (통과)

| 축 | 확인 | 근거 |
|---|---|---|
| 인증 등록 | `/api/my-retinue`·`/api/generals/*/retinue` 가 authenticated 목록에 있다. 컨트롤러는 401(익명)·404(내 장수 없음)·403(타국/재야 대상)·200(본인·같은 국가, 둘 다 nationId≠0) — 적국 장수의 사병·군량 누출 없음. `findById` 는 process-world 스코프. | `GameApiSecurityConfig.kt:44`, `RetinueController.kt:35-51`, `GeneralReadRepository.kt:348`, `RetinueReadControllerTest.kt:66-99` (3) |
| gateway-api 보안 | `GET /notices` 만 permitAll(메서드 한정), `/admin/**` ADMIN, `/auth/account/representative` 는 `anyRequest().authenticated()`. 소유 검증은 `general.user_id = 계정 id` 한 축. | `SecurityConfig.kt:48,52-53`, `RepresentativeService.kt:37,43-44`, `OwnedGeneralReader.kt:21-29` |
| board-api 보안 | 신고 POST 는 authenticated, `/board/admin/reports` 는 GET permitAll 이지만 서비스가 비관리자 403. | `BoardSecurityConfig.kt:33-35`, `GatewayBoardService.kt:120,135` |
| XSS / LogText | `LogText` 는 세그먼트 배열 → span 렌더, innerHTML 없음. `style=color` 는 `#hex` 정규식 통과값만. 게임 8곳 + 게이트웨이 `ServerLog` 가 소비. | `LogText.tsx:22-34`, `logTokens.ts:27-28,37-38`, `logText.test.tsx` |
| HanMapCanvas | `web/shared/src/HanMapCanvas.tsx` 와 두 테스트 모두 diff 0. | `git diff --stat origin/main...HEAD -- '**/HanMapCanvas*'` = 빈 출력 |
| 초상 파일 · 브랜드 | 커밋된 래스터는 `reports/ui-redesign/**/*.png` 캡처뿐. 기본 초상은 자작 `portrait-default.svg`. 워드마크는 `Brand.tsx` 의 `/logo-wordmark.png` 하나. RTK14 는 CDN URL 계약만. | `git diff --name-status` 필터, `portraitResolver.ts:16,44-48`, `Brand.tsx:20` |
| 데몬 쓰기 규칙 | 가신·부곡은 `InMemoryTurnWorld.create/update/remove*` + 주인 장수는 `applyGeneralDirtyFree`+`recorder.diffGeneral` 짝. 월 정산도 같은 경로. `DatabaseHooks.toFlushPayload` → `JdbcFlushExecutor` 8g. 엔진에 JPA 쓰기 없음. | `RetainerHandler.kt:42-46`, `RetainerMonthlyService.kt:66-70`, `DatabaseHooks.kt:698-704`, `JdbcFlushExecutor.kt:264-273` |
| 세계 범위 규약 | V53 `board_post_read`·V55 `general_retainers`/`general_bugok`: PK `(world_id,id)`, `world_state` FK, 부모 `(world_id,id)` 복합 FK, 인덱스 world_id 선행. V32 인벤토리에 세 표 + 전역 표(V51 `gateway_notice`, V54 `gateway_board_report`) 등록. | `V53:10-21`, `V55:6-62`, `V32WorldScopeCompletionMigrationTest.kt:654-657,676-678` |
| flush 순서 · FK | 8g 는 5단계 general DELETE(CASCADE) 뒤. 표마다 DELETE→CREATE→UPDATE 라 「해제→같은 이름 서약」 UNIQUE 만족, 새 가신 INSERT 가 부곡 commander FK 앞. created 는 updated 에서 제외. `removeGeneral` 이 가신·부곡을 map+dirty/created/deleted 전부에서 즉시 가지치기 → `requireExactlyOneAffected` 가 CASCADE 로 사라진 행을 만나지 않는다. `RetainerFlushIT` 가 PG16 에서 순서·`SET NULL (col)`·CASCADE·meta 키를 실측. | `JdbcFlushExecutor.kt:149,264-273`, `DatabaseHooks.kt:598-602,698-704`, `InMemoryTurnWorld.kt` `pruneRetinueOf`·`removeGeneral`, `RetainerFlushIT.kt:82-135` |
| PG 문법 | `ON DELETE SET NULL (commander_retainer_id)` 는 PG15+ — 로컬·프로덕션·IT 전부 `postgres:16-alpine`. | `docker-compose.yml:25`, `docker-compose.production.yml:7`, `RetainerFlushIT`/`*IT.kt` 컨테이너 태그 |
| 기존 세계 바이트 동일 | `maxRetainerId/maxBugokId` 는 값이 있을 때만 meta 에 병합(행 0 세계 meta 불변). `RetainerMonthlyNoopGateTest` 가 행 0 동일·행 1 상이 적색 프로브. `board_post` INSERT 는 `kind='general'`, `vote_id=NULL` 기본. | `InMemoryTurnWorld.kt` `recordMaxRetainerId`, `DatabaseHooks.kt:653-657`, `JdbcFlushExecutor.kt:527-537`, `RetainerMonthlyNoopGateTest.kt:105-130`, `JdbcFlushExecutor.kt:1793-1795` |
| 202 ≠ 성공 | 가신 6 명령·`boardRead` 모두 `submitCommandAndAwaitResult` 로 RESOLVED 까지 기다린 뒤 분기·재조회. 서버 상태 패널은 「접수 ≠ 반영」 을 명시(엔드포인트가 requestId 없는 `publishImmediate` 라 폴링 불가 — 기존 계약). | `RetinuePanels.tsx:40-47`, `board/page.tsx:284-289`, `ServerStatusPanel.tsx:35,48`, `AdminWriteController.kt:48-50` |
| 게이팅 불변 | `gateAllows` 가 main 의 `MainControlBar` 판정과 문자 그대로 같고, `MainControlBar` 가 같은 함수를 import 한다. 20 버튼·14 잎 1회 배치, 사유 문자열, 로딩 중 사유 미날조 테스트. | `dept-menu-config.ts:53-66` vs `origin/main:MainControlBar.tsx:24-33`, `MainControlBar.tsx:12,96`, `dept-menu-config.test.ts` |
| DTO ↔ TS 계약 | `RetinueResponse`·`RetinueRulesDto` ↔ `types/game.ts` 필드 1:1. `BoardResponse` 확장(`kind/vote/readers/participants/chiefCount/myPermission`) 은 TS 에서 optional. gateway `board.ts` ↔ `GatewayBoardPostResponse` 신규 4필드 optional. | `RetinueDto.kt`, `game.ts:1329-1388`, `F4Dto.kt:640-735`, `board.ts:32-36` |
| 수치 날조 없음 | 도시 화면 `수비○`·`守` 는 원천 미배선이라 `-` 마스킹. 가신 상한·비용은 응답 `rules` 로만, 「잠정」 칩. 세력 현황은 `/api/rankings/kingdoms`. | `city/page.tsx:209-211,286-288`, `RetinuePanels.tsx:58-63,80`, `NationSummary.tsx:8,40-41` |
| 시크릿 | compose/.env.example 은 `INTERNAL_SERVICE_TOKEN` 키만 추가(빈 기본값). `.env*` 미열람. | `.env.example:52-53`, `docker-compose.yml:98-99,202-205` |
| 동결 테스트 | `WaterControlPersistenceIT` 는 cold boot 전에 최신 스키마까지 올리는 `migrateLatest()` 만 추가 — 로더가 V55 표를 읽어서 필요한 변경이지 기대값 약화가 아니다. | diff `WaterControlPersistenceIT.kt` |

---

## 1. fix-required

### F1. 대표 장수 「해제」 가 API 검증에 막힌다 (confirmed)

- UI 는 「없음」 을 고르면 `generalId: null` 을 보낸다: `RepresentativeSection.tsx:31` (`draft === '' ? null : Number(draft)`) → `representative.ts:39-44` (`body: JSON.stringify({ generalId })`) → Next 라우트가 null 을 그대로 전달 `app/api/account/representative/route.ts:36-41`.
- gateway-api 는 `@Valid @RequestBody SetRepresentativeRequest` (`RepresentativeController.kt:27-30`) 인데 필드가 `@field:NotNull val generalId: Int?` 다 (`RepresentativeDto.kt:24-28`, 주석은 「null 이면 대표 장수 해제」). Bean Validation 이 null 을 거부 → `MethodArgumentNotValidException` → 400 (`GlobalExceptionHandler.kt:91-92`). 서비스의 해제 분기(`RepresentativeService.kt:38-41`)에는 도달하지 못한다.
- 테스트가 잡지 못한 이유: `RepresentativeServiceTest.kt:41` 은 서비스를 직접 호출(검증 레이어 없음), `account-representative.test.tsx:9-14` 는 fetch 를 mock. 계획 §Phase 4 C-2(`:305`)의 「GET/POST /auth/account/representative」 는 해제 경로를 한 번도 실제 컨트롤러로 통과시키지 않았다.
- 고치는 법: `@field:NotNull` 제거(타입은 `Int?` 유지, 본문 부재와 명시 null 을 같은 「해제」 로) + MockMvc 로 `{"generalId":null}` → 200 · `current.generalId == null` 을 단언하는 테스트 1건.

### 1-C. 비평 중 닫힌 항목 (기록)

- **`boardRead` 결과 직렬화기 throw** — `04551374` 에서 confirmed: `BOARD_ACTION_TYPES = setOf("boardArticle","boardComment")` 에 `boardRead` 가 없어 `BoardHandler.handleRead` 가 돌려주는 `BoardActionResult("boardRead", …)` 가 `selectSerializer` 의 `else -> throw IllegalArgumentException("unknown result type=…")` 로 떨어졌고, 이 함수는 **encode 경로**(`concreteSerializer` → `TurnRunService.toCommandResultRows`)에서도 불리므로 기밀실을 여는 순간 그 배치의 결과 기록이 통째로 죽는다. `25b8232f` 가 집합에 등록하고 `BoardIntakeWireTest.kt:48-52` 왕복 회귀를 더했다. HEAD `a119a57b` 에서 `TurnDaemonCommandResult.kt:634` 확인. 닫힘.

---

## 2. should-fix

### S1. board-api 네이티브 `(:x IS NULL OR …)` 의 Postgres null 바인딩 — **PLAUSIBLE, PG 실측 요구**

- `GatewayBoardRepositories.kt:19-21,27-29` (`searchLatest`), `:49-50,57-58` (`searchPopular`): `:category`·`:author`·`:q` 를 `IS NULL` 과 비교식 양쪽에 쓴다. 기본 목록(`GET /board/posts`)은 셋 다 null 로 들어간다(`GatewayBoardService.kt:52-56`).
- Spring Boot 3.4.1(`gradle/libs.versions.toml:3`) = Hibernate 6.6. Hibernate 6 는 타입을 모르는 null 네이티브 파라미터를 PostgreSQL 에서 `bytea`/unspecified 로 보내 `operator does not exist: character varying = bytea` 또는 `could not determine data type of parameter $n` 이 나는 사례가 널리 보고돼 있다. 이 저장소에는 같은 패턴을 PG 에서 돌린 선례가 없다(`IS NULL OR` 네이티브 사용처는 이 파일뿐).
- 확인된 사실: board-api 테스트는 **H2 만** 쓴다 — `application-test.yml:3` `MODE=PostgreSQL`, `:9` `ddl-auto: create-drop`, `:13` `flyway.enabled: false`. 즉 (a) V54 마이그레이션 자체가 테스트에서 한 번도 실행되지 않고, (b) `ILIKE`/`NULLS LAST`/`||`/null 바인딩의 PG 동작이 검증되지 않았다. 계획 `:305` 와 커밋 `969371b5` 본문의 「native 쿼리 — H2/Postgres 공통」 은 H2 결과를 PG 증거로 쓴 주장이다(「검사가 버그를 공유한다」). 유일한 커뮤니티 캡처 `reports/ui-redesign/phase4c/13-community-desktop.png` 는 「게시글을 불러오는 중…」 + 옛 3탭(공지·자유·건의) 상태라 증거가 아니다.
- 요청: gateway-api 의 `NoticePostgresIT` 선례대로 Testcontainers PG16 IT 를 하나 두고 `GET /board/posts`(필터 0)·`?sort=popular`·`?q=` 를 통과시켜라(V54 도 Flyway 로 실행). 실패하면 `CAST(:q AS text)` 계열로 바꾸거나 JPQL/Specification 으로 내려라.

### S2. ADR-LITE-050 위반 잔존 + 계획의 허위 주장

- 계획 `:293` 「`dangerouslySetInnerHTML` 로그 렌더 0」 · ADR-050 「두 앱의 모든 로그 표시는 … `LogText` 로만」 — 남아 있는 로그 innerHTML: `web/game/components/admin/GeneralLogPanel.tsx:54` (관리 허브 장수 로그 4종, 주석 `:16` 은 이미 바뀐 world-log/history 를 선례로 인용), `web/game/app/game/inherit/page.tsx:635` (`log.text`). 둘 다 main 에 있던 코드가 옮겨지거나 남은 것이지만 ADR 이 「모든 로그」 라 못 박았다. `LogText` 로 바꾸고 계획 문장을 고쳐라.

### S3. 부곡 지휘관 사기 +6 이 재배정으로 무한 반복된다

- `RetainerHandler.kt:150-153`: `newlyAssigned = retainerId != null && b.commanderRetainerId != retainerId` → 해제(null)→재배정, 또는 부장 A→B→A 마다 +6(상한 100). 스펙 `§4 :131` 「새로 배정할 때만」 을 문자 그대로 구현했지만 결과는 공짜 사기 펌프다. `RetainerIntakeTest.kt:135-146` 은 같은 부장 재지정(무변화)만 본다. 부곡당 1회 플래그 또는 「정산 한 번 지나기 전엔 재부여 없음」 중 하나를 스펙에 적고 테스트로 고정해라(§3 Q2).

### S4. `RetinueSlot` 이 오류를 「휘하 없음」 으로 위장한다

- `RetinueSlot.tsx:16-28`: `api.myRetinue` 실패(`catch → setCounts(null)`)와 진짜 0건이 같은 「휘하 없음 / 서약하면 여기 나옵니다」 로 그려진다 — 「서버 정보 없음」 을 따로 둔 `dept-menu-config.ts:179` 원칙과 어긋난다. 또 0건일 때 disabled 버튼이라 서약 화면(`/game/my#retinue`)으로 가는 길이 없다. 오류 상태 분리 + 빈 상태는 링크로.

### S5. 사유 없는 `<select disabled>`

- `RetinuePanels.tsx:101,157` (`disabled={busy !== ''}`), `ServerStatusPanel.tsx:55` (`disabled={busy}`). ADR-049 (7) 「비활성은 점선 + 사유」 는 `Button` 타입에서만 강제된다(`Button.tsx:8-9`). `title`/`aria-describedby` 로 「처리 중」 을 붙여라. (`RepresentativeSection.tsx:51` 은 옵션 텍스트가 사유 역할을 해 제외.)

### S6. 가신·부곡 인테이크에 NPC 가드가 없다

- `RetainerHandler.kt:21` 은 「NPC 는 서약하지 않는다(인테이크만)」 라 적었지만 `preGate`(`:32-39`)는 `npcState` 를 보지 않는다. 인테이크 소유 가드는 principal 이 있을 때만 작동하고 F2 전환기엔 `?generalId=` 를 그대로 믿는다(`InstantActionController.kt:47-48,89-92`, `GameApiSecurityConfig.kt:52` anyRequest permitAll). 전환기 구멍은 기존 것이지만, 이 절편은 NPC 의 crew/rice 를 부곡으로 옮길 수 있게 하므로 엔진 쪽에서 `npcState >= 2 → deny` 한 줄과 테스트 1건을 두는 게 싸고 결정적이다.

### S7. V52 번호 공백

- `infra/src/main/resources/db/migration/` 에 V51 → V53 (V52 없음; `git log --all -- 'V52*'` 0건). Flyway 기본 `outOfOrder=false` 라 이 브랜치가 배포된 뒤 다른 브랜치가 V52 를 들고 오면 적용이 거부된다. 머지 전에 V53~V55 를 당기거나 V52 를 예약 파일로 못 박아라(§3 Q3).

### S8. 신고 목록 N+1

- `GatewayBoardService.kt:150-153` `reportResponse` 가 행마다 `postRepository.findById`/`commentRepository.findById` — `listReports` 는 size ≤ 100. 같은 파일의 `authorsOf`/`commentCountsOf` 처럼 한 번에 끌어와라.

### S9. V54 주석 오기

- `V54__gateway_board_extend.sql:2` 「분류 6종(공지·자유·건의·전략·공략·서버 이야기·창작·일지)」 — 라벨 8개, 값 6개. `전략·공략` / `창작·일지` 로 고쳐라.

---

## 3. 질문 · UNKNOWN

- **Q1 (S1)** `searchLatest(null, null, null, …)` 이 PG16 + Hibernate 6.6 에서 실제로 무엇을 반환하는가 — 이 워크트리에서는 실행하지 않았다. UNKNOWN. 적색 프로브(PG IT) 결과로 닫아라.
- **Q2 (S3)** 지휘관 사기 보너스의 의도는 「부곡당 1회」 인가 「배정 이벤트당 1회」 인가. 스펙 `:131` 만으로는 펌프를 막지 못한다.
- **Q3 (S7)** V52 를 비운 이유가 있는가(다른 브랜치 예약?). 없으면 번호를 당기는 게 안전하다.
- **Q4** 워크트리에 커밋되지 않은 Phase 4X-B 코드(`TurnDaemonCommand.kt` +49, `TurnDaemonCommandResult.kt` +14 `OperationActionResult`, `TruncateContract.kt`, `BoardActions.kt`, `logic/operation/**`, `reports/ui-redesign/phase4xa/`)가 있다. 이 PR 범위가 아니면 PR 브랜치에 섞이지 않게 해라 — 비평 중 HEAD 가 두 번 움직였다.
- **Q5** 계획이 적은 테스트 수(「board-api 전체 57 녹색」·「game vitest 667」·「gateway 240」)는 실행하지 않아 UNKNOWN. XML mtime 으로 확인해라(JDK 25 함정).
- **Q6** `BoardActions.addArticle(kind=vote)` 는 `voteId` 존재·국가 일치를 검사하지 않는다(`BoardActions.kt:56-57`). 읽기 쪽 `voteSummary` 가 `votePolls.findById` null 이면 `vote=null` 로 그리므로 화면은 안전하지만, 타국 설문 id 를 붙인 글이 저장될 수 있다. 의도인가.

---

## 4. 읽은 파일

마이그레이션: `infra/src/main/resources/db/migration/V51__gateway_notice.sql`, `V53__board_post_kind.sql`, `V54__gateway_board_extend.sql`, `V55__general_retainers_and_bugok.sql` (+ `V1`/`V32` 의 `board_post` PK 확인)

common/logic: `common/…/wire/TurnDaemonCommand.kt`, `TurnDaemonCommandResult.kt`(HEAD 와 `04551374` 두 판), `common/src/test/…/RetainerIntakeWireTest.kt`, `BoardIntakeWireTest.kt`(HEAD), `logic/…/retainer/RetainerRules.kt`, `logic/…/actions/intake/BoardActions.kt`, `logic/…/memory/HotColdCatalog.kt`, `logic/src/test/…/RetainerRulesTest.kt`

infra: `infra/…/persistence/JdbcFlushExecutor.kt`(diff), `RetainerRowMapper.kt`, `infra/…/read/OwnedGeneralReader.kt`, `GatewayNoticeRepository.kt`, `infra/…/entity/GatewayNoticeEntity.kt`, `UserEntity.kt`(diff), `infra/src/test/…/RetainerFlushIT.kt`, `BoardFlushIT.kt`(diff), `V32WorldScopeCompletionMigrationTest.kt`(diff)

game-engine: `boot/WorldSnapshotLoader.kt`(diff), `config/DaemonLoopConfig.kt`(diff), `flush/DatabaseHooks.kt`(diff), `flush/TruncateContract.kt`(diff), `intake/RetainerHandler.kt`, `intake/BoardHandler.kt`(diff), `retainer/RetainerMonthlyService.kt`, `run/MonthlyPostUpdateHook.kt`(diff), `run/TurnDaemonCommandDispatcher.kt`(diff), `run/TurnRunService.kt`(diff + `:590-660`), `turn/ChangeRecorder.kt`(diff), `turn/DirtyState.kt`(diff), `turn/InMemoryTurnWorld.kt`(diff), `turn/TurnWorldModel.kt`(diff), 테스트 `RetainerIntakeTest.kt`, `RetainerMonthlyNoopGateTest.kt`, `BoardIntakeSliceCTest.kt`(diff), `WaterControlPersistenceIT.kt`(diff)

game-api: `controller/RetinueController.kt`, `controller/BoardController.kt`(diff), `controller/AdminWriteController.kt`(`:38-51`), `controller/InstantActionController.kt`(가드 부분), `dto/RetinueDto.kt`, `dto/F4Dto.kt`(diff), `read/RetainerReadRepository.kt`, `read/BoardReadRepository.kt`(diff), `read/GeneralReadRepository.kt`(스코프 부분), `owner/GeneralResolver.kt`(필드), `reserve/CommandWireMapper.kt`(diff), `security/GameApiSecurityConfig.kt`, 테스트 `RetinueReadControllerTest.kt`, `F4ReadControllersTest.kt`(diff), `CommandWireMapperTest.kt`(grep)

gateway-api: `controller/NoticeController.kt`, `controller/RepresentativeController.kt`, `service/NoticeService.kt`, `service/RepresentativeService.kt`, `dto/NoticeDto.kt`, `dto/RepresentativeDto.kt`, `security/SecurityConfig.kt`, `config/InfraBeanConfig.kt`(diff), `web/GlobalExceptionHandler.kt`, 테스트 `NoticeControllerTest.kt`, `NoticePostgresIT.kt`, `NoticeServiceTest.kt`(이름), `RepresentativeServiceTest.kt`

board-api: `board/GatewayBoardContracts.kt`, `GatewayBoardController.kt`, `GatewayBoardEntities.kt`, `GatewayBoardRepositories.kt`, `GatewayBoardService.kt`(전부 diff), `security/BoardSecurityConfig.kt`, `build.gradle.kts`(테스트 의존), `src/test/resources/application-test.yml`, 테스트 `GatewayBoardFeedAndReportTest.kt`

web/shared: `LogText.tsx`, `logTokens.ts`, `Portrait.tsx`, `portraitResolver.ts`, `Button.tsx`, `Brand.tsx`, `HanMapCanvas.tsx`(diff 0 확인)

web/game: `lib/dept-menu-config.ts`, `lib/control-bar-config.ts`, `lib/api.ts`(diff), `lib/commandSubmit.ts`, `lib/auth-context.tsx`(diff), `types/game.ts`(diff), `hooks/useShellFrontInfo.ts`, `components/game/MainControlBar.tsx`(+ main 판), `components/game/RetinuePanels.tsx`, `components/game/RetinueSlot.tsx`, `components/game/MapViewer.tsx`(`:351-352`), `components/admin/ServerStatusPanel.tsx`, `components/admin/GeneralLogPanel.tsx`, `components/DeptNav.tsx`(권한 부분), `app/game/board/page.tsx`(`:260-310`, kind 게이팅), `app/game/city/page.tsx`(`:203-291`), `app/game/my/page.tsx`(마운트), `app/game/inherit/page.tsx`(`:635`), 테스트 `dept-menu-config.test.ts`, `retinue-panels.test.tsx`(이름), `board-council.test.tsx`(`:85-110`)

web/gateway: `lib/board.ts`(diff), `lib/representative.ts`, `lib/lobbyEntry.ts`, `lib/auth-context.tsx`(diff), `app/api/notices/route.ts`, `app/api/server-nations/[id]/route.ts`, `app/api/account/representative/route.ts`, `app/api/board/[...path]/route.ts`(diff), `components/NationSummary.tsx`, `components/account/RepresentativeSection.tsx`, `components/admin/AdminOverview.tsx`(원천), 테스트 `account-representative.test.tsx`

문서·설정: `CLAUDE.md`, `.ai/decisions.md`(diff, ADR-049/050), `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md`(게이트·테스트 주장), `docs/superpowers/specs/2026-09-06-retinue-buqu-vertical-slice.md`(사기·정산 절), `docs/superpowers/reviews/2026-09-06-retinue-spec-critique.md`(형식), `docs/superpowers/reviews/2026-09-06-ui-phase-6-closeout.md`, `docker-compose.yml`(diff), `docker-compose.production.yml`(PG 태그), `.env.example`(diff), `gradle/libs.versions.toml`, `reports/ui-redesign/phase4c/13-community-desktop.png`

`.env*` 는 열지 않았다. 빌드·테스트는 실행하지 않았다.
