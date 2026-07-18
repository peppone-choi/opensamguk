# OPENSAM-94 — 프로필 아이콘 typed sync (gateway→game) 검증 + 독립 적대 리뷰

- **리뷰어**: `reviewer-94-sync` (검증 + 독립 적대 리뷰 겸임 패스)
- **일자**: 2026-07-17
- **대상**: OPENSAM-94 — canonical 프로필 아이콘 변경을 게임 서버로 typed sync fan-out → daemon이 소유 general의 표시 컬럼(picture/image_server)만 재도색
- **기준**: 실행계약 §6 (`docs/superpowers/plans/2026-07-17-opensam-92-93-94-97-103-execution-contract.md`) + repo `CLAUDE.md` one-daemon-write 규칙 / 패리티 규율
- **소유 산출물(이 리뷰어가 쓰는 유일 파일)**: 이 문서. **코드는 전량 READ-ONLY** — 결함은 fix-required finding으로 보고하며 직접 패치하지 않는다. commit/push/Jira 없음, `.ai/*` 미변경.
- **구현자**: lane-94b (코드 작성). 이 리뷰어는 코드를 작성하지 않았고, 모든 검증을 스스로 수행·재현했다.

---

## 1. 검토 범위 (변경 표면)

`git diff --stat` 기준 순수 additive (109 insertions, 0 deletions). 94 전용 표면:

| 파일 | 성격 | 요지 |
| --- | --- | --- |
| `common/.../wire/TurnDaemonCommand.kt` | +25 additive | 신규 sealed 변형 `ProfileIconSync(requestId?, userId, picture, imgsvr, grade)`, `@SerialName("profileIconSync")` |
| `app/game-api/.../web/ProfileIconSyncController.kt` | 신규 | 내부 엔드포인트 `POST /api/internal/profile-icon-sync`, 선택 토큰(`X-Profile-Sync-Token`), `validShape()`, `publishImmediate` → 202 |
| `app/game-engine/.../intake/ProfileIconSyncHandler.kt` | 신규 | 서버측 eligibility 게이트 + owner/npc predicate + idempotency + `applyGeneralDirtyFree` + `recordProfileIconUpdate` |
| `app/gateway-api/.../service/ProfileIconSyncPublisher.kt` | 신규 | best-effort fan-out to `serverRegistry.all()`, per-server try/catch, PII 없는 warn |
| `app/gateway-api/.../profile/ProfileIconService.kt` | 91/92 공유 파일 수정 | syncPublisher 주입 + `ProfileIconSyncCompletion.afterCommit()` 발행 |
| `app/game-engine/.../turn/ChangeRecorder.kt` | +16 additive | `profileIconUpdates` 채널 + isDirty OR-체인 + clear() |
| `app/game-engine/.../turn/DirtyState.kt` | +2 | `data class ProfileIconUpdate(columns)` |
| `app/game-engine/.../flush/DatabaseHooks.kt` | +4 | 3-arg `toFlushPayload`에 profileIconUpdates 매핑 |
| `app/game-engine/.../run/TurnDaemonCommandDispatcher.kt` | +6 | `ProfileIconSyncHandler` 바인딩 + 디스패치 분기 |
| `infra/.../persistence/JdbcFlushExecutor.kt` | +39 | step-8b `profileIconUpdateMany` UPDATE + `ProfileIconUpdateRow` + payload 필드 |
| `infra/.../db/migration/V30__profile_icon_changed_at.sql` | 신규 | `users`에 `profile_icon_changed_at`, `profile_icon_managed` 추가 |
| `infra/.../entity/UserEntity.kt` | +7 | 위 두 users 컬럼 매핑 (grade는 기존 nullable Int) |

테스트: `ProfileIconSyncControllerTest`(4), `ProfileIconSyncHandlerTest`(8), `ProfileIconSyncPublisherTest`(3), `ProfileIconFlushIT`(2), `V30ProfileIconMigrationIT`.

---

## 2. 검증 (mandatory check #1 — 직접 재실행, XML/OUTPUT-TAIL 판정, exit code 불신)

판정 규칙(CLAUDE.md): `<testsuite ... failures="0" errors="0">` + BUILD SUCCESSFUL, Testcontainers Docker-미가용 ⇒ IT **skipped**(fail 아님). 이 환경은 Docker **가용** → IT는 실제 실행.

### 2.1 단위 테스트 XML (직접 판독)

| suite | tests | skipped | failures | errors | 판정 |
| --- | --- | --- | --- | --- | --- |
| `engine.intake.ProfileIconSyncHandlerTest` | 8 | 0 | 0 | 0 | ✅ PASS |
| `gameapi.web.ProfileIconSyncControllerTest` | 4 | 0 | 0 | 0 | ✅ PASS |
| `gateway.service.ProfileIconSyncPublisherTest` | 3 | 0 | 0 | 0 | ✅ PASS |

증거(XML testsuite 헤더, 직접 grep):
```
engine ProfileIconSyncHandlerTest  tests="8" skipped="0" failures="0" errors="0"
gameapi ProfileIconSyncControllerTest tests="4" skipped="0" failures="0" errors="0"
gateway ProfileIconSyncPublisherTest  tests="3" skipped="0" failures="0" errors="0"
```

### 2.2 인프라 IT (Testcontainers, Docker 가용)

`ProfileIconFlushIT`(2: typed-컬럼 persist + WHERE predicate NPC/타-owner 불변) + `V30ProfileIconMigrationIT`(1: users 컬럼 timestamptz/boolean 매핑).

**증거 출처별 판정(well-formed XML 직접 판독)**:

| suite | 출처 실행 | tests | fail | err | 판정 |
| --- | --- | --- | --- | --- | --- |
| `V30ProfileIconMigrationIT` | 무경합 격리 재실행(실 컨테이너 2.555s) | 1 | 0 | 0 | ✅ PASS |
| `ProfileIconFlushIT` | gate.sh backend 집계 실행(12:40:25 XML 2/0/0) | 2 | 0 | 0 | ✅ PASS |

- **V30ProfileIconMigrationIT** — 무경합 격리 재실행에서 실제 Postgres 컨테이너를 띄워 2.555s에 깨끗이 통과(users 컬럼 `timestamp with time zone`/`boolean` 매핑 단언).
- **ProfileIconFlushIT** — gate.sh backend 실행(§2.3)에서 `infra:test`의 일부로 실행돼 2/0/0 통과했고, 그 XML을 직접 판독했으며 486-스위트 green 집계에 포함된다.

> **경합/인프라 오탐 2건 기록(코드 결함 아님)**:
> 1. **경합 XML 손상**: 동일 워크트리 다중 `infra:test`가 `build/test-results/`를 동시 기록하던 중 V30 XML이 정확히 8192B(8KB 페이지 경계)에서 `name 'betti`로 잘려 닫는 태그 없는 **비-well-formed 부분 쓰기**로 관측됐다. 오류도 profile-icon과 무관한 betting 빈 `@DataJpaTest` context-load(2ms). gate.sh 자체 일관 실행과 무경합 격리 재실행 양쪽에서 재현되지 않으며 94 결함이 아니다.
> 2. **Docker 컨테이너 기동 flake**: 15분+ 컨테이너 churn(gate.sh + 형제 레인) 직후 확인용 격리 재실행 2회 모두 `ProfileIconFlushIT`가 `ContainerLaunchException: Container startup failed for image postgres:16-alpine`(근인 `InternalServerErrorException` = Docker 데몬측 오류)로 initializationError를 냈다. 이는 코드 fail이 아니라 CLAUDE.md가 규정한 Docker 기동 불가 범주다. **환경 증거**: (a) macOS 설정상 Ryuk 비활성 → Testcontainers 컨테이너/볼륨이 누수 축적(`docker system df`: 볼륨 54개·8GB, build cache 353개·20GB, 정지 컨테이너 5개) → Docker VM 리소스 압박(호스트 디스크는 78GB 여유로 정상); (b) **동일 `postgres:16-alpine` 이미지가 같은 격리 배치의 V30에서는 2.555s에 정상 기동** → 이미지/설정 문제가 아닌 기동 타이밍 flake; (c) **동일 테스트가 gate.sh 실행에서 이미 2/0/0로 통과**(직접 XML 판독 + 486-스위트 green 집계 포함). 즉 코드 정합성은 gate.sh 실행으로 증명됐고 두 IT 모두 green 실행 기록이 있다. 압박 상태 Docker VM에 재시도를 반복하거나 공유 Docker 상태를 prune하는 것은 read-only 리뷰어 레인 밖이라 중단하고 근거를 기록한다.

### 2.3 전체 백엔드 매트릭스 / gate.sh backend

명령: `tools/parity/gate.sh backend` (`:common :logic :infra :app:game-engine :app:game-api :test`, `--no-daemon --console=plain`, XML failures/errors 집계). gateway-api는 별도 XML(§2.1)로 커버.

**결과(직접 실행, 로그 tail 판독)**:
```
BUILD SUCCESSFUL in 15m 27s
XML gate green: 486 suites, 4423 tests
```
gate.sh의 Python 집계기는 선택 모듈의 모든 `TEST-*.xml`을 파싱해 failures/errors를 합산하며, 0-byte/비-well-formed XML이 하나라도 있으면 파싱 예외로 중단(green 미출력)한다. 따라서 "486 suites, 4423 tests green"은 V30ProfileIconMigrationIT를 포함한 전 스위트가 집계 시점에 깨끗이 파싱·0-fail 상태였음을 증명한다 — §2.2의 경합 오탐이 gate.sh 자체 일관 실행에서는 재현되지 않았다.

> **환경 위험(코드 결함 아님)**: 이 검증은 3+ 병렬 레인 Gradle 데몬이 동일 워크트리에서 동일 모듈을 `--rerun-tasks`로 동시 실행하는 상황에서 수행되었다. 동일 `build/` 디렉터리 경합으로 개별 실행이 굶주리거나 XML이 일시적으로 0-byte로 관측될 수 있다. 위 판정은 각 suite의 채워진 XML 헤더를 직접 판독한 결과다.

---

## 3. 적대 리뷰 — mandatory checks #2–#8

### #2 one-daemon-write (ChangeRecorder delta, EntityManager 금지) — ✅ PASS

- 핸들러는 `world.applyGeneralDirtyFree(general.copy(...))`로 read-state만 갱신(dirty 미표시)하고, 변경은 **오직** `recorder.recordProfileIconUpdate(linkedMapOf("id","user_id","picture","image_server"))` delta로 기록한다. JPA `EntityManager`/dirty-checking 경로 없음.
- 로드-베어링 배선 확인: `TurnRunService.buildFlushPayload()` → `DatabaseHooks.toFlushPayload(world, recorder, dirty)` **3-arg** 경로가 실제로 `profileIconUpdates`를 실어 나른다(2-arg 경로는 미포함이나 daemon은 3-arg만 사용). 이 채널이 `JdbcFlushExecutor.profileIconUpdateMany` JDBC 배치로 flush된다.
- `DaemonNoEntityManagerTest` 1/0/0(root 확인) — 아키텍처 테스트가 daemon 쓰기의 EntityManager 부재를 강제.
- 재사용 컬럼 타당성: `generalUpdate` SET 절이 `picture/image_server`를 방출하지 않으므로(#17류 typed-컬럼 누락) 전용 채널이 정당하며, `general.picture/image_server`는 V1부터 존재. V30은 `users` 컬럼만 추가.

### #3 게이트 시맨틱 `show_img_level >= 1 && grade >= 1` 서버측 — ✅ PASS

- 게이트는 **엔진 핸들러**에 있다: `showImageLevel = intFromMeta(world.getState().meta["show_img_level"]) ?: DEFAULT(3)`, `eligible = showImageLevel >= 1 && command.grade >= 1 && picture.isNotBlank()`.
- 권위 출처: `show_img_level`은 **월드 config**(wire 아님), `grade`는 wire=게이트웨이 계정 authoritative 원값. 클라이언트 불리언 무시.
- 컨트롤러는 eligibility를 하지 **않고** shape만 본다(관심사 분리 정확). 필드명 `show_img_level`은 JoinController / ScenarioStartEventActions / ScenarioImporter와 일치.
- 테스트 증거: `grade below 1 blocks`(grade=0), `show_img_level 0 blocks even with high wire grade`(grade=9 이어도 차단) — wire 불신을 직접 단언.

### #4 predicate 안전성 `owner=userId && npc=0` — ✅ PASS

- `userId`는 서버 공급값(게이트웨이 `user.id`)이지 클라이언트 값이 아니다. 핸들러 루프: `if (general.userId != ownerKey || general.npcState != 0) continue`.
- **이중 방어**: `JdbcFlushExecutor` SQL이 `WHERE id = :id AND user_id = :user_id AND npc_state = 0`으로 owner+npc predicate를 재-단언. 메모리 predicate가 뚫려도 DB가 막는다.
- IT 증거: `where predicate never repaints an NPC row or another owner even with a matching id` — 정확히 겨눈 id(11=같은 user의 NPC, 12=타 owner)에도 row 불변.

### #5 wire 규율 (직렬화 라운드트립 / CommandWireMapper / 아이콘명 검증) — ✅ PASS (note 1건)

- `ProfileIconSync`는 `@Serializable @SerialName("profileIconSync")` sealed 하위형. `publishImmediate` → `WireJson`(하위형 자동 등록) → Redis → dispatcher. **서버-대-서버 즉시 커맨드**이므로 FE-예약 `CommandWireMapper` intakeCode 매핑 불요(정확).
- 컨트롤러 `validShape()`: userId>0, imgsvr∈{0,1}, grade>=0, picture not-blank & length<=128 & no `/`,`\`,`..`. traversal 차단.
- 핸들러 idempotency: picture+imgsvr 무변경이면 skip(테스트 `identical payload is idempotent`).
- **NOTE-1**: 컨트롤러 라운드트립을 캡처 검증하는 명시적 wire 단위 테스트는 없다. 다만 `ProfileIconSyncControllerTest`가 `publishImmediate` 인자를 `TurnDaemonCommand.ProfileIconSync`로 캡처·필드 단언하고, 핸들러 테스트가 동일 타입을 소비하므로 라운드트립은 실효적으로 커버됨. severity: note(비차단).

### #6 패리티 표면 불변 (additive-only, 재정렬 없음) — ✅ PASS

- 8개 수정 파일 전량 additive (109 insertions, 0 deletions). RNG/log/golden 편집 없음.
- `profileIconUpdateMany`는 신규 step(8b, betting과 board 사이)으로 삽입 — 기존 flush step 재정렬 없음.
- `isDirty` OR-체인/`clear()` 추가는 순서 독립적. 패리티 게이트(draw/log) 표면 무접촉.

### #7 테스트 품질 (skip/only/stub 없음) — ✅ PASS

- `test.skip`/`.only`/stub/TODO placeholder 부재. IT의 `Assumptions.assumeTrue(dockerAvailable, ...)`는 CLAUDE.md가 승인한 Docker-미가용 skip 패턴(가짜 skip 아님).
- 핸들러 8종이 게이트 on/off·show_img_level=0·grade=0·NPC 불변·타owner 불변·zero-match·idempotency·delete→default를 memory/dirty/flush-payload **세 층**에서 단언. 컨트롤러가 publish/401/6종 invalid shape. 퍼블리셔가 fan-out 고립/토큰/빈 레지스트리. IT가 실DB persist + WHERE predicate. 포괄적.

### #8 스코프 감사 — ✅ PASS (note 1건)

- 94 전용 파일은 계약 §6 선언대로 wire/game-api/game-engine/gateway-service/infra-flush에 국한. 94 파일이 92/93/97/map 영역으로 새지 않음.
- **NOTE-2**: 워킹 트리에는 형제 레인(92/93/97/map)의 변경(web/**, nginx, compose 등)이 공존하나, 이는 **공유 트리** 특성이지 94 결함이 아니다. 94 diff는 위 표면에 confined. severity: note.

---

## 4. 추가 검증-benign 관찰 (적대 시도 → 무해 확인)

- **재시작 일관성**: 핸들러는 in-memory `meta["picture"/"image_server"/"imgsvr"]` 미러를 갱신하지만 이를 general.meta jsonb에 persist하지 않는다. 얼핏 재시작 후 드리프트 우려. 그러나 `WorldSnapshotLoader`(429–431)가 rehydrate 시 typed 컬럼에서 `generalMeta.putIfAbsent("picture", rs.getString("picture") ?: "default.jpg")`, `...("image_server", rs.getInt(...))`, `...("imgsvr", rs.getInt("image_server"))`로 미러를 **재구성**한다. typed 컬럼이 source-of-truth이므로 미러는 파생값 → 무해.
- **rollback 시 sync 0회(criterion 1)**: 발행은 `ProfileIconSyncCompletion.afterCommit()`에서만, `userRepository.saveAndFlush(user)` **이후** 등록. `afterCommit`은 rollback 시 미호출 → 실패 트랜잭션은 sync 미발행. 3경로(upload/selectShared/delete) 모두 `persist()`를 경유해 동일 보장.
- **grade null → 0 → ineligible**: `grade = user.grade ?: 0` 보수적. grade>=1 게이트에 충실.
- **best-effort 격리**: 퍼블리셔 per-server try/catch + afterCommit try/catch가 예외를 삼켜 이미 commit된 계정 mutation에 영향 없음. warn 로그는 `server.id`/예외 클래스명만(PII 없음).

---

## 5. Findings (severity-tagged)

| # | severity | 위치 | 내용 |
| --- | --- | --- | --- |
| NOTE-1 | note | ProfileIconSyncController/Handler 테스트 | 명시적 wire 직렬화 라운드트립 단위 테스트는 없으나 커맨드 캡처+소비로 실효 커버. 비차단. |
| NOTE-2 | note | 워킹 트리 | 형제 레인 변경 공존은 공유 트리 특성, 94 결함 아님. |
| NOTE-3 | note | ProfileIconSyncController | 내부 엔드포인트 신뢰 모델은 선택적 토큰(`PROFILE_SYNC_TOKEN` 미설정 시 무토큰). 내부망 호출자가 자신이 지정한 userId의 자기 소유 general에 대해 traversal-free picture를 임의 지정할 수 있으나, npc/타-owner는 이중 predicate로 차단되고 nginx `/d_pic/`가 실제 서빙 경계. 계약이 명시한 내부-신뢰 모델과 일치. 운영 시 토큰 설정 권고. |

**fix-required: 0건.**

아이콘명 검증이 게이트웨이 `MANAGED_FILE`(8-hex regex)보다 느슨한 것은 결함이 아니다 — sync 채널은 `default.jpg` + shared canonical 파일명까지 수용해야 하므로 필연적으로 느슨하며, traversal(`/`,`\`,`..`)만 차단하면 서빙 경계로 충분.

---

## 6. Verdict

**CLEARED** — fix-required 0건.

근거 종합:
- **테스트(직접 XML 판독)**: 94 전용 18 테스트 전량 green — engine handler 8/0/0 · game-api controller 4/0/0 · gateway publisher 3/0/0 · infra ProfileIconFlushIT 2/0/0 · infra V30ProfileIconMigrationIT 1/0/0.
- **전체 백엔드 게이트**: `gate.sh backend` BUILD SUCCESSFUL, **XML gate green 486 suites / 4423 tests**, common/logic/infra/game-engine/game-api 0 failures·0 errors.
- **8개 필수 점검 전부 PASS**: one-daemon-write(ChangeRecorder delta·EntityManager 부재·3-arg flush 배선), 서버측 게이트 시맨틱(show_img_level·grade), predicate 이중 방어(메모리 continue + SQL WHERE), wire 규율(sealed @SerialName·publishImmediate·traversal 차단), 패리티 표면 additive-only(109 ins/0 del·재정렬 없음), 테스트 품질(skip/only/stub 없음), 스코프.
- **findings**: fix-required 0, note 3(비차단). §2.2 V30 경합 오탐은 무경합 격리 재실행 1/0/0로 해소.

권고(비차단): 운영 시 `PROFILE_SYNC_TOKEN` 설정(NOTE-3), 동일 워크트리 다중 `--rerun-tasks` 병렬 실행 지양(XML 손상 방지).

---

## 7. 문서 sha256

`sha256 = 9ecda682149ea8fcc1a297fcd4a363bc7c021d8a7a71c5935b571cd2a6b46a69`

(재현: 이 줄의 해시를 리터럴 토큰 `__SHA256__`로 되돌린 뒤 `shasum -a 256`로 검증. 즉 해시는 §7 값 삽입 직전의 문서 본문에 대해 산정됐다.)
