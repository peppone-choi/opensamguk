# 휘하 인물 · 부곡 수직 절편(Phase 4X-A) 스펙 교차 비평

- Date: 2026-09-06
- Target: `docs/superpowers/specs/2026-09-06-retinue-buqu-vertical-slice.md` (DRAFT)
- Plan: `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md` §Phase 4X-A (320-332행)
- Verdict: **fix-required 6건** — 이대로 구현하면 crew/rice/gold 보존이 DB에 도달하지 않고(F1), 같은 틱의 명령·정산이 서로를 덮어쓰거나 flush 를 터뜨리며(F2), 부장 해임이 FK 위반으로 실패하고(F3), 읽기 API 가 공개되며(F4), 「골든 불변」이 바뀐 경로를 타지 않는 게이트로 주장되고(F5), 승인된 ADR-LITE-017 과 모순된다(F6). 코드 근거는 전부 이 워크트리에서 직접 읽은 파일:줄이다.

---

## 0. 스펙 주장 중 코드로 확인한 것(맞음)

| 스펙 주장 | 확인한 근거 |
|---|---|
| 인테이크 명령 → 엔진 핸들러 → `ChangeRecorder` 채널 → `JdbcFlushExecutor` (board 패턴 미러) | `BoardHandler.kt:28-60` → `ChangeRecorder.kt:816-829` → `DatabaseHooks.kt:703-705` → `JdbcFlushExecutor.kt:212-224` (8d), `1627-1700` (`world_id` 는 executor 가 `worldId.value` 로 주입, 핸들러 columns 에 없음: `1638`) |
| 결과는 `BoardActionResult` 와 같은 꼴 `type/ok/generalId/reason` | `common/.../TurnDaemonCommandResult.kt:105-110` |
| 와이어: `TurnDaemonCommand` + `CommandWireMapper` + 디스패처 | `CommandWireMapper.kt:81-83`(allowlist) · `332-335`(매핑) · `TurnDaemonCommandDispatcher.kt:415-417` |
| `TruncateContract` 에 두 표 등록 | `TruncateContract.kt:46-75` (`board_post_read` 선례 `:69`) |
| V32 인벤토리 테스트에 두 표 등록 | 테스트는 V31 까지 → **최신까지** migrate 하므로(`V32WorldScopeCompletionMigrationTest.kt:371-388`) 새 표는 `postV32WorldTables`(`:654-662`) 에 넣어야 하고, world_id NOT NULL·world_state FK·PK world 선행·모든 인덱스 world 선행을 검사한다(`:71-79`) |
| `general(world_id,id)` 복합 FK 대상 존재 | `V32__*.sql:190-192` `PRIMARY KEY (world_id, id)` |
| 월 경계는 상순(phase 1) 1회 | `TurnRunService.kt:358` `runMonthWhen = phase == 1`, 드라이버가 경계마다 1회 |
| 인테이크 202 ≠ 성공, `CommandModal` pinnedCommand + extraArgs 는 회의실과 같은 경로 | `CommandModal.tsx:28,61,73,501-520` (`submitCommandAndAwaitResult` 의 terminal status 로 분기) · `web/game/app/game/board/page.tsx` 가 같은 프롭 사용 · `/game/my/page.tsx` 존재 |
| 07 아트보드 문구 「사기 +6」「내정 명령 효율 +8%」「부곡은 장수 개인의 사병입니다…」 | `docs/design/ui-redesign-2026-09/src/General.body.html` (텍스트 추출) |
| `TurnGeneral` 에 crew/rice/gold/crewTypeId/train/atmos 가 있고 diff 대상이다 | `TurnWorldModel.kt:65-70` · `ChangeRecorder.diffGeneral` `:345`(gold) `:350`(rice) `:356-359`(crew/train/atmos/crewTypeId) |
| 「접속 제한입니다.」 게이트 | `AccessLogThrottle.kt:12-28` (board 핸들러와 동일 호출) |
| 로드맵 「휘하 인물과 부곡」 4원칙(부장은 지휘관이 될 수 있으나 부곡이 아니다, 국가군과 별도) | `docs/design/roadmap.md:39-46` — 스펙 §1.1·§2 가 이를 따른다 |
| PostgreSQL 16 | `docker-compose.yml:118` `postgres:16-alpine`, Testcontainers 4곳 동일 |

---

## 1. fix-required

### F1. `world.updateGeneral` 은 데몬 쓰기 경로가 아니다 — crew/rice/gold 변경이 DB에 도달하지 않는다

- 스펙: §3 「gold −500(메모리 general)」「general.crew −troops, general.rice −rice(메모리)」, §4.2 「주인 장수 gold 에서(메모리 `world.updateGeneral`) 차감」.
- 코드: `InMemoryTurnWorld.updateGeneral` 은 `dirtyGeneralIds` 에 넣을 뿐이고(`InMemoryTurnWorld.kt:245-250`), 바로 아래 문서가 **「the world's own dirty set is never the daemon write path」** 라고 못박는다(`:288-294`). `ChangeRecorder` 헤더도 「resolver NEVER calls the world's updateGeneral … RowPatches are the ONLY thing that marks a row dirty」(`ChangeRecorder.kt:60-64`). 실제 인테이크 핸들러는 전부 `world.applyGeneralDirtyFree(next)` + `recorder.diffGeneral(pre, next)` 짝이다 — `TroopHandler.kt:141-148`, gold 차감·지급은 `VoteHandler.kt:207-221`.
- 결과: 스펙대로 짜면 부곡 행은 INSERT 되는데 `general.crew/rice/gold` 의 감소는 `FlushPayload.updatedGenerals` 에 들어가지 않는다 → 재기동·rehydrate 후 병력이 국가군과 부곡에 **이중으로** 존재한다. 스펙 §1.1 「합은 보존된다」 가 메모리에서만 성립한다. CLAUDE.md 「daemon writes go only through ChangeRecorder → JdbcFlushExecutor」 위반.
- 고침: §3·§4 의 「메모리 general」「`world.updateGeneral`」 을 모두 「`pre = toLogicGeneral(me)`; `next = me.copy(...)`; `world.applyGeneralDirtyFree(next)`; `recorder.diffGeneral(pre, toLogicGeneral(next))`」 로 바꾸고, §7 `RetinueIntakeTest` 의 「메모리 general 변화」 를 「`recorder.generalPatches()` 에 gold/crew/rice 열이 기록된다」 로 바꿔라(메모리만 보는 검사는 F1 을 통과시킨다 — 「검사가 버그를 공유한다」).

### F2. DB 원천 행을 같은 틱 안에서 명령(1단계)과 월 정산(2단계)이 따로 읽고 쓴다 — 유실·flush 실패

- 스펙: 휘하·부곡 행은 메모리에 두지 않고 DB 원천(§2, §4.1 「infra `BuquRepository` 로 전부 읽기」), 명령은 INSERT/UPDATE/DELETE 채널만 기록(§3).
- 코드: 한 틱은 ① 인테이크 디스패치(`TurnRunService.kt:299-300`) → ② 월 경계 드라이버(`:302-364`) → ③ **단일** flush(`:384-414`) 순서다. ②가 DB 를 읽는 시점에 ①의 채널 기록은 아직 flush 되지 않았다. 기존 DB-only UPDATE 채널은 **열 단위 last-write-wins 절대값**이다(`ChangeRecorder.kt:620-629` `recordVotePollUpdate`), flush 는 `check(affected == 1)` 로 행 부재를 예외로 만든다(`JdbcFlushExecutor.kt:1804-1824`). general 삭제(5단계, `:149`)는 8x 채널보다 먼저 실행된다.
- 깨지는 경우(전부 같은 뿌리):
  1. `buquAssignCommander`(morale 50→56 UPDATE) 뒤 같은 틱 월 정산이 DB morale 50 을 읽어 −5 → 45 UPDATE. 같은 열 LWW → **+6 유실**.
  2. `retinueDismiss`(DELETE) 뒤 같은 틱 정산이 DB 에서 그 사람을 읽어 loyalty UPDATE → 삭제된 행 UPDATE → `affected == 1` 위반 → **틱 전체 flush 실패**(FLUSH_RETRY 진입).
  3. 주인 장수 사망(`deletedGeneralIds`) → 5단계 general DELETE 가 CASCADE 로 buqu 를 지운 뒤 8x 에서 pending buqu UPDATE → 동일.
  4. `MAX_PERSONS/MAX_BUQU` 상한을 DB count 로 재면 같은 틱 두 번 등용이 둘 다 통과(4 → 6).
  5. `buqu.id` 가 identity 라 flush 전엔 id 가 없다 → 「편성 → 지휘관 배정」 을 같은 틱에 못 한다(「부곡이 없습니다.」). 결과(`BoardActionResult` 꼴)에도 id 를 실을 수 없다.
- 고침(하나로 해결): 휘하·부곡을 `InMemoryTurnWorld` 의 세계 상태로 올려라(`troops`·`accessLogs` 처럼 부팅/rehydrate 시 적재). 명령·정산은 메모리를 읽고, 리코더 채널은 **행 단위 병합 + 툼스톤 우선**(DELETE 기록된 id 는 pending UPDATE 에서 제외 — `diffGeneral` 의 `deletedGeneralIds` 단락 `ChangeRecorder.kt:342` 와 같은 규칙, 주인 장수 툼스톤도 전파), flush 순서는 INSERT → UPDATE → DELETE 로 고정하고 스펙에 적어라. id 는 `diplomacyLetterIdAllocator` 처럼 DB-seed `max(id)+1` 선할당(`ChangeRecorder.kt:82-88`) 후 명시 INSERT(`GENERATED BY DEFAULT` 라 허용). 최소한 이 규칙들이 스펙에 없으면 구현자가 매번 다르게 짠다.

### F3. 복합 FK `ON DELETE SET NULL` 은 `world_id` 까지 NULL 로 만든다 — 부장 해임이 실패한다

- 스펙: §2 `buqu.commander_person_id (FK retinue_person(world_id,id) SET NULL)`, §3 `retinueDismiss` 「지휘 중이던 부곡은 commander NULL — FK」.
- 코드: 세계 범위 규약대로 부모 FK 는 `(world_id, commander_person_id) REFERENCES retinue_person(world_id, id)` 복합이어야 한다(V53 `board_post_read_world_post_fkey` 선례 `V53__board_post_kind.sql:18`). PostgreSQL 의 `ON DELETE SET NULL` 은 **참조 열 전부**를 NULL 로 만든다 → `world_id`(PK 구성, NOT NULL) 위반 → 지휘 중인 부장을 DELETE 하는 순간 예외 → flush 실패. PG 16 이므로(`docker-compose.yml:118`) PG15+ 의 열 지정 `ON DELETE SET NULL (commander_person_id)` 가 가능하다.
- 고침: DDL 을 `ON DELETE SET NULL (commander_person_id)` 로 명시하고, `RetinueFlushIT` 에 「지휘 중인 부장 DELETE 후 buqu.world_id 보존·commander NULL」 케이스를 넣어라. 또는 FK 액션을 두지 말고 엔진이 DELETE 전에 commander UPDATE 를 명시 기록(F2 채택 시 메모리에서 자연히 됨). 둘 중 하나를 스펙이 골라야 한다.

### F4. 읽기 API 권한 — `GameApiSecurityConfig` 에 등록하지 않으면 두 경로가 공개다

- 스펙: §5 「권한: 본인 또는 같은 국가. 아니면 403」.
- 코드: `GameApiSecurityConfig.kt:41-49` 는 나열된 경로만 `.authenticated()` 이고 나머지는 `.anyRequest().permitAll()`. `/api/my-retinue`·`/api/generals/{id}/retinue` 는 목록에 없다 → 익명 요청이 컨트롤러까지 온다. 기존 idiom 은 `@AuthenticationPrincipal userId: Long?` null → 401(`MyController.kt:113-116`). 스펙엔 401 도, 익명 케이스도, 재야(nationId 0) 케이스도 없다. `/api/generals/{id}/retinue` 가 공개되면 적국 장수의 개인 사병·군량이 그대로 새는 정보 누출이다.
- 고침: 두 경로를 `GameApiSecurityConfig` 의 `.authenticated()` 목록에 추가(스펙 변경 항목으로 명시), 계약을 「principal 없음 401 / 본인 또는 같은 국가(nationId ≠ 0) 200 / 그 외 403 / 재야는 본인만」 으로 쓰고, `RetinueReadControllerTest` 에 익명 401 과 재야-타인 403 을 넣어라.

### F5. 「골든 불변」 이 바뀐 경로를 타지 않는 게이트로 주장된다 + 정산 배치가 `runMonth` 조기 반환을 못 본다

- 스펙: §1.3·§7 「기존 engine/logic 골든 전부 녹색(행 0 경로)」 를 불변의 증거로 삼고, §4 「`TurnRunService` 의 월 경계 드라이버가 `runMonth` 뒤에 `settle(world, recorder, repo)` 를 부른다」.
- 코드: (a) 유일한 엔진 리플레이 골든 `LongSimReplayGateTest` 는 `lifecycle.runTick` 을 직접 돌린다(`LongSimReplayGateTest.kt:1606`) — `TurnRunService.runTick` 의 월 드라이버(`:302-364`)를 타지 않는다. `TurnRunServiceIT` 는 `pipeline` 을 배선하지 않는다(일치 0). 즉 settle 훅이 들어갈 자리를 핀하는 골든이 **없다**. 「전부 녹색」 은 F1·F2 를 포함해 아무것도 증명하지 않는다(메모리: exit 0 은 게이트의 증거가 아니다). (b) `MonthlyPipeline.runMonth` 는 L6 에서 `preUpdateMonthly.run()` 이 false 면 달력을 올리지 않고 조용히 return 한다(`MonthlyPipeline.kt:107`) — 반환값이 없어 호출자는 모른다 → 「runMonth 뒤에 settle」 은 중단된 달에도 정산한다. (c) `TurnRunService` 의 read repo 는 전부 nullable(`:118` `boardPostRepository: BoardPostRepository? = null`) — `BuquRepository` 가 null 인 테스트/부팅 경로에서 settle 이 뭘 하는지 스펙에 없다.
- 고침: settle 을 드라이버 람다가 아니라 파이프라인 안(MONTH 이벤트 leaf 또는 `PostUpdateMonthly` 마지막 단계)에 두어 조기 반환과 같은 운명을 타게 하고, 골든 증거는 **적색 프로브**로 바꿔라: pipeline 을 배선한 `TurnRunService` 테스트에서 행 0 세계의 `FlushPayload`(updatedGenerals·logEntries·`lastOps`) 가 훅 유무와 바이트 동일함을 단언하고, 같은 테스트의 행 1 변형이 빨개지는 것을 함께 보여라. repo null → 정산 생략을 명문화.

### F6. 승인된 ADR-LITE-017 과 모순된다 — OPENSAM-61 을 이 모델로 닫을 수 없다

- 스펙: §2 `retinue_person`(자유 입력 이름, role `staff/lieutenant/guest`, task `none/domestic/scout/train`), §3 `retinueRecruit/Dismiss/AssignTask`, 티켓 「OPENSAM-61(#203) 가신」 닫기.
- 기록: `.ai/decisions.md:166-173` ADR-LITE-017(approved 2026-07-25, 이후 supersede 기록 없음)은 가신을 `origin(EXISTING|RECRUITED)`(기존 장수 풀에서도 온다), `hasOwnBugok`, `role(참모|호위|군수관|정찰|사신|NONE)`, `releasePolicy`, `upkeep(RECRUITED 만)`, 표 `general_retainers`, 명령 `가신서약/가신해제/가신임무` 로 정의했다. 백로그도 OPENSAM-61 을 「가신 (ADR-LITE-017로 1트랙 병합)」 으로 적는다(`2026-07-17-v2-ticket-backlog:96`). 07 아트보드 07 도 휘하를 **기존 장수**로 그린다 — 「문약·막료 / 문겸·부장 / 중강·부장 / 중덕·문객」 은 荀彧·樂進·許褚·程昱의 자(字)이고 「허저를 지휘관으로 배정하면 사기 +6」(`General.body.html`). 스펙은 RECRUITED 자유 이름만 허용하고 EXISTING 을 아예 뺐다.
- 고침: 둘 중 하나. (i) ADR-LITE-017 을 명시적으로 supersede 하는 새 ADR(사용자 승인) 을 이 스펙과 함께 발행하고 그 안에 「EXISTING 은 다음 절편」 을 적는다. (ii) 도메인을 ADR-017 에 맞춘다 — `origin` 열, EXISTING 은 `general_id` 참조(이름 자유 입력 아님), role enum 은 ADR 값, 명령명은 `가신서약/가신해제/가신임무`. 어느 쪽이든 하지 않으면 OPENSAM-61 은 닫지 말고 코멘트만 남겨라.

---

## 2. should-fix

- **S1. 마이그레이션 번호·계획 불일치.** 계획 328행은 `V52__retinue_and_buqu.sql` 에 `assigned_buqu_id`·`formation`·`commander_retinue_id` 를 적었고 스펙은 V55 에 다른 열(`commander_person_id`, formation 없음). 이 브랜치엔 V51·V53·V54 가 있고 V52 는 비었으며 main 은 V50 까지다. Flyway 기본 `outOfOrder=false` 라 V53/54 적용 후 V52 를 넣으면 거부된다 → V55 가 맞다. 계획 328행을 스펙에 맞춰 고치고, 4X-B/4X-C 가 V55 를 선점하지 않도록 번호를 계획 표에 못박아라(`flyway-v45-merge-collision-hotfix` 브랜치가 그 사고의 흔적이다).
- **S2. V32 인벤토리 등록 세부.** `world_id → world_state(id)` FK 는 delete action 이 없어야 한다(`V32WorldScopeCompletionMigrationTest.kt:533-534`, CASCADE 허용 표 3개 고정). identity 열은 `column_default` 가 NULL 이라 `serialIdentityColumns`(`:684-712`) 에 넣으면 실패한다(`board_post_read` 가 빠져 있는 이유). 스펙에 「world_state FK 무액션, 부모 general FK 만 CASCADE, serialIdentityColumns 미등록」 을 적어라.
- **S3. 정산 규칙의 빈칸.** (a) gold 부족 시 급여를 일부라도 깎는지, 0 으로 만드는지, 안 깎는지 미정. (b) 군량 부족과 급여 부족이 동시에 나면 −5 한 번인지 −10 인지 미정(§4.2 「provisions < 0 이거나 gold 부족이면 morale −5」 는 「이거나」 라 한 번으로 읽히지만 표엔 「급여·군량 부족 시」). (c) `buquDisband` 때 장수 `crewTypeId` 가 편성 시와 다르면 병종이 섞인다 — 거부(「병종이 다릅니다」)인지 병합인지, train/atmos 는 가중 평균인지 미정. 순수 함수 표(§7)에 이 세 행을 넣어라.
- **S4. 개인 기록 로그의 정확한 채널.** 「장수 개인 기록」 은 `world.pushLog(LogEntryDraft(scope = "general", category = "action", generalId = 주인))` 이어야 `/api/general-log`(scope=GENERAL, category 필터 `GeneralLogController.kt:28,91-93`) 에 뜬다. 스펙에 scope/category 를 적고, 「{이름}이(가)」 조사 처리 방식(기존 josa 헬퍼 유무)을 확인해라.
- **S5. `provisionMonths = provisions / max(1, troops)`** 는 `PROVISION_PER_TROOP_MONTH = 1` 을 하드코딩한 식이다. §1.4 「한 곳」 원칙대로 `provisions / max(1, troops × PROVISION_PER_TROOP_MONTH)` 로 써라.
- **S6. 초상 3종 규칙.** ADR-LITE-049 (4) 는 초상을 「원본 히어로 / 148×210 카드 / 96 아이콘」 3종만 허용한다. 「카드 44×62 = Portrait card-44」 가 148×210 카드 자산을 축소 렌더한 것이면 그렇게 적고, 새 자산이면 위반이다. 또 아트보드 행은 「이름」 이 아니라 **자(字)** 를 보여준다(S/F6 참조) — §6 「이름·자(없음)」 은 아트보드와 반대다.
- **S7. 게이트 순서의 명시.** board 핸들러는 「장수 없음 → 접속 제한 → 입력 → 권한」 순서를 PHP 줄 번호까지 적어 고정한다(`BoardHandler.kt:72-73`). 스펙 §3 표는 거부 문자열만 나열하고 순서가 없다. 6 명령 각각의 게이트 순서를 적고 `RetinueRulesTest` 가 순서를 핀하게 해라(문자열이 같아도 순서가 다르면 유저에게 다른 사유가 보인다). 또 `AccessLogThrottle` 은 거부돼도 access_log·`game_env.refresh` KV 를 쓴다(`AccessLogThrottle.kt:21-26`) — 어느 명령에 적용할지 6종 모두 표기(지금은 등용에만 있다).
- **S8. `retinueRecruit` 이름 정규화.** 「공백 제거 2~12자」 — trim 인지 내부 공백 제거인지, NFC 정규화, 같은 장수 안 중복 허용 여부를 정해라. varchar(24) 는 12자 한글에 충분하다.
- **S9. `RetinueRulesTest` 의 「상수」 검사.** `MAX_PERSONS == 5` 를 단언하는 테스트는 동어반복이다. 상수는 순수 함수 표의 입력으로만 쓰고, 상수 자체를 단언하지 마라(값을 바꿀 때 테스트도 같이 바뀌는 「지어낸 수치가 스펙이 된다」 경로).
- **S10. 결과 타입.** 「`BoardActionResult` 와 같은 꼴」 이 재사용인지 신규 `RetinueActionResult` 인지 정해라. 신규면 `TurnDaemonCommandResult` sealed 등록·직렬화·`toCommandResultRows` 영향을 적어야 한다. 편성 결과엔 새 `buquId` 를 실어야 UI 가 바로 배정할 수 있다(F2 의 id 선할당 전제).

---

## 3. 질문 / UNKNOWN

- **Q1. 휘하 인물의 정체.** 아트보드(기존 장수의 자)와 ADR-017(EXISTING|RECRUITED)은 기존 장수를 포함하고, 스펙은 자유 이름 신규 인물만이다. 사용자가 원하는 쪽이 어느 것인지 UNKNOWN — F6 의 (i)/(ii) 선택이 여기에 달렸다.
- **Q2. 주인 장수 사망·삭제 시.** CASCADE 로 부곡·휘하가 사라진다(병력 소멸). 국가군 crew 도 같이 사라지므로 일관되지만 의도인지, 아니면 부곡이 국가군으로 환원되는지 UNKNOWN.
- **Q3. 부곡의 위치.** 스펙 buqu 에 city 가 없다. 계획 §Phase 4X 서두는 「작전이 부곡을 부대로 쓴다」 고 한다 — 4X-B 가 부곡 위치를 전제하면 V56 이 또 필요하다. 4X-B 스펙과 열 합의가 필요한지 UNKNOWN.
- **Q4. `crewTypeName` 원천.** `GameConst` 의 어느 표에서 읽는지 확인하지 않았다(UNKNOWN).
- **Q5. rehydrate 범위.** F2 대로 메모리에 올리면 재기동 시 적재가 필요하다. `RehydrateLosslessGateIT` 류 bounded gate 에 두 표를 넣어야 하는지(CLAUDE.md 133행: all-channel lossless 는 격리 운영 범위) UNKNOWN.
- **Q6. 기본 초상 자산.** §6 「이름 이니셜 타일이 아니라 기본 초상」 — 그 자산이 `image-manifest.md` 에 있는지 확인하지 않았다(UNKNOWN).
- **Q7. 잠정 상수의 규모.** `ceil(troops/100)×10` 이면 1,000명 부곡이 월 100금이다. 스펙이 「실측 기준선이 아니다, 게이트 임계값으로 쓰지 않는다」 고 밝혔으므로 판단하지 않는다 — 다만 플레이테스트 전엔 UI 도움말이 「잠정」 임을 표시해야 한다.

---

## 4. 읽은 파일(근거 경로)

`app/game-engine/src/main/kotlin/opensamguk/engine/intake/{BoardHandler,TroopHandler,VoteHandler,AccessLogThrottle}.kt` · `engine/turn/{ChangeRecorder,InMemoryTurnWorld,TurnWorldModel}.kt` · `engine/flush/{DatabaseHooks,TruncateContract}.kt` · `engine/run/{TurnRunService,MonthlyPreUpdateHook,TurnDaemonCommandDispatcher}.kt` · `logic/src/main/kotlin/opensamguk/logic/tick/MonthlyPipeline.kt` · `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt` · `infra/src/main/kotlin/opensamguk/infra/read/BoardPostRepository.kt` · `infra/src/main/resources/db/migration/{V32,V53}__*.sql` + 디렉터리 목록 · `infra/src/test/kotlin/opensamguk/infra/persistence/V32WorldScopeCompletionMigrationTest.kt` · `app/game-engine/src/test/kotlin/opensamguk/engine/golden/LongSimReplayGateTest.kt` · `app/game-api/src/main/kotlin/opensamguk/gameapi/{security/GameApiSecurityConfig,controller/MyController,controller/GeneralLogController,reserve/CommandWireMapper}.kt` · `common/src/main/kotlin/opensamguk/common/wire/{TurnDaemonCommand,TurnDaemonCommandResult}.kt` · `web/game/components/CommandModal.tsx` · `web/game/app/game/{board,my}/page.tsx` · `docs/design/ui-redesign-2026-09/src/General.body.html` · `docs/design/roadmap.md:39-46` · `.ai/decisions.md` ADR-LITE-017/049 · `CLAUDE.md` · `docker-compose.yml`.
