# 휘하 인물(가신) · 부곡 수직 절편 (Phase 4X-A) 설계 — v3

- Date: 2026-09-06 (v1 → v2 → v3 같은 날)
- Status: REVISED v3 — 교차 비평 1차(fix-required 6 · should-fix 10 · 질문 7)와 2차(v2 재판정: fix-required N1·N2 · should-fix N3~N8, 같은 파일 `docs/superpowers/reviews/2026-09-06-retinue-spec-critique.md`)를 전부 반영. 통합 재판정 대기(ADR-LITE-049 Phase 4X 규칙: spec → 비평 → 구현 → 게이트).
- Scope: 로드맵 「휘하 인물과 부곡」의 첫 수직 절편 — 장수 개인의 휘하 인물(= ADR-LITE-017 의 **가신**, `origin=RECRUITED` 만)과 부곡(개인 사병)을 만들고·부리고·달마다 정산한다. 07 아트보드 「휘하 인물 · 부곡」 구획을 실제 원천에 연결한다.
- 정합: **ADR-LITE-017(approved, 가신 1트랙)** 을 따른다. 표 이름 `general_retainers`, 속성 `origin/hasOwnBugok/role/releasePolicy/upkeep`, 명령 `가신서약/가신해제/가신임무`. 이 절편은 `origin=RECRUITED` 만 구현하고 `EXISTING`(기존 장수의 서약 — 07 아트보드의 荀彧·樂進·許褚·程昱 자(字) 행)은 **다음 절편**으로 명시한다. ADR 을 대체하지 않으므로 새 ADR 이 필요 없다.
- Tickets: OPENSAM-48(#190) 부곡 foundation → 이 절편으로 닫는다. OPENSAM-61(#203) 가신 → RECRUITED 절편 코멘트만, EXISTING 절편 뒤 닫는다. OPENSAM-20(#162) 에픽 코멘트.
- 밖: EXISTING 가신, 휘하 임무·역할의 명령 효율 효과(기존 명령 경로 접촉), 부곡의 전투 참여(4X-C), 광역 명령 3종, 봉토·봉신, NPC 등용 AI.

## 0. 비평 반영표

| 항목 | 결정 |
|---|---|
| F1 `world.updateGeneral` | 폐기. 주인 장수 변경은 전부 `pre = toLogicGeneral(me)` → `next` → `world.applyGeneralDirtyFree(next)` → `recorder.diffGeneral(pre, toLogicGeneral(next))`. 테스트는 `recorder.generalPatches()` 의 gold/crew/rice 열을 단언한다(§8). |
| F2 DB 원천 행 | 폐기. 가신·부곡은 `InMemoryTurnWorld` 의 세계 상태(`troops` 미러: map + dirty/created/deleted 집합, `WorldSnapshotLoader` 적재). id 는 엔진 할당(identity 아님). 툼스톤 규칙·flush 순서는 §3. |
| F3 복합 FK SET NULL | `ON DELETE SET NULL (commander_retainer_id)`(PG15+ 열 지정, `world_id` 보존) **와** 엔진 측 명시 UPDATE(메모리에서 commander 를 NULL 로 만들고 UPDATE 채널 기록) 둘 다. `RetainerFlushIT` 가 DELETE 뒤 `world_id` 보존을 단언한다. |
| F4 읽기 API 공개 | 두 경로를 `GameApiSecurityConfig` `.authenticated()` 목록에 추가. 계약 401/200/403·재야 규칙 §6. |
| F5 골든 주장 | 정산을 `MonthlyPostUpdateHook.run` 의 **마지막 단계**(L10 안)로 옮겨 L6 조기 반환과 같은 운명을 탄다. 증거는 「전부 녹색」 이 아니라 §8 의 **적색 프로브**(행 0 = 산출물 동일, 행 1 = 달라짐을 같은 테스트가 보인다). repo null 개념 없음(메모리 상태). |
| F6 ADR-017 모순 | ADR-017 정합(위 「정합」). `relation`(막료/부장/문객 = 로드맵·아트보드의 「관계」) 과 `role`(ADR 역할) 과 `task`(임무) 는 서로 다른 속성이다(로드맵: 「관계·충성·역할·임무」). |
| S1 V52/V55 | V55 확정. 계획 표를 V55 로 고치고 V56(4X-B)·V57(4X-C) 예약. |
| S2 V32 등록 | `postV32WorldTables` 에 두 표. world_state FK 무액션, 부모 general FK 만 CASCADE, id 는 identity 가 아니라 `serialIdentityColumns` 미등록. |
| S3 정산 빈칸 | 급여는 전액 아니면 미지급(부분 지급 없음). 군량·급여 부족은 합쳐 −5 한 번. 해산 시 병종 불일치는 거부 「병종이 다릅니다.」. 해산은 crew/rice 만 되돌리고 장수 train/atmos 는 바꾸지 않는다(가중 평균 같은 지어낸 식을 넣지 않는다). |
| S4 로그 채널 | `LogEntryDraft(scope = "general", category = "action", generalId = 주인)`; 조사는 `opensamguk.common.josa.JosaUtil.pick(name, "이")`(Presets.kt 선례). |
| S5 provisionMonths | `provisions / max(1, troops × PROVISION_PER_TROOP_MONTH)`. |
| S6 초상 | RECRUITED 는 초상 원천이 없으므로 `Portrait` 가 **기본 초상**(`/portrait-default.svg`, 기존 자산) 을 `card-44`(148×210 카드 자산과 같은 프레임의 축소 렌더, 새 자산 아님) 로 그린다. 이름만 표시(자 없음). EXISTING(다음 절편) 이 자를 보여준다. |
| S7 게이트 순서 | 6 명령 모두 「장수 없음 → 접속 제한 → 입력 → 상태」 순서를 §4 표에 못박고 `RetainerRulesTest` 가 순서를 핀한다. `AccessLogThrottle` 은 6 명령 모두. |
| S8 이름 | NFC 정규화 → trim → 내부 공백이 있으면 거부 → 2~12 코드포인트 → 같은 주인 안 중복 거부 「같은 이름의 휘하가 있습니다.」. |
| S9 상수 검사 | 상수 값을 단언하지 않는다. 순수 함수 표의 입력으로만 쓴다. |
| S10 결과 타입 | 신규 `RetainerActionResult(type, ok, generalId, reason?, id?)` 를 `TurnDaemonCommandResult` sealed + 직렬화기에 등록. 편성·서약 결과에 새 id 를 싣는다(F2 의 선할당). |
| Q1 정체 | RECRUITED 만(이 절편). EXISTING 다음 절편. |
| Q2 주인 사망 | 부모 FK CASCADE — 부곡 병력은 주인과 함께 사라진다(국가군 crew 도 함께 사라지므로 일관). 메모리는 §3 툼스톤 전파. |
| Q3 부곡 위치 | 이 절편엔 위치 열이 없다(부곡은 주인과 함께 있다). 4X-B 가 필요하면 V56 에서 `city_id` 추가. |
| Q4 crewTypeName | `UnitCatalog.byId(crewTypeId)?.name ?: "-"`(CityDetailController 선례). |
| Q5 rehydrate | `WorldSnapshotLoader` 가 두 표를 적재한다(부팅·rehydrate 동일 경로). `RehydrateLosslessGateIT` 의 bounded 목록에 두 표를 넣는다 — 그 게이트가 표 단위 열거라면; 아니면 §8 의 `WorldSnapshotLoaderRetainerTest` 가 대신한다(구현 시 확인, UNKNOWN 은폐 금지). |
| Q6 기본 초상 | `web/{game,gateway}/public/portrait-default.svg` 존재(확인). |
| Q7 잠정 상수 | 응답 `rules.provisional = true`, UI 가 「잠정」 칩을 붙인다. |
| **N1 같은 틱 해제→같은 이름 서약** | flush 순서를 표마다 **DELETE → CREATE → UPDATE** 로 바꾼다(§3). `UNIQUE (world_id, master_general_id, name)` 위반이 영구 재시도 실패로 이어지는 경로를 제거. |
| **N2 툼스톤 시점** | `consumeDirtyState` 가 아니라 **`InMemoryTurnWorld.removeGeneral` 안에서 즉시** 가신·부곡을 가지치기한다(`generalPosition`·`removeNation`→diplomacy 선례). 정산은 주인이 없는 행을 건너뛰고 주인을 **그 시점에 다시 읽는다**(`run()` 시작 스냅샷 아님). |
| N3 meta 키 영속 | 「미러」 가 아니라 네 지점을 명시: `TurnRunService` worldState 공급 → `JdbcFlushExecutor` world_state meta 병합(두 곳) → `WorldSnapshotLoader` 허용 키 목록. 테스트는 flush→reload 왕복. |
| N4 적색 프로브 구성 | 「같은 세계 두 번」 이 아니라 **새 world+recorder 두 벌**. Q15/Q16 이 벽시계·RNG 를 읽으므로 `MonthlyPostUpdateHookTailWiringTest` 의 `ScriptedRng`/`auctionRepo()` fixture 를 쓴다. |
| N5 골든 수 | 「208 파일」 삭제. 실측: logic 골든 274 json + engine 4 — 회귀로만 돌린다. |
| N6 crewTypeName | `UnitCatalog.byId` 는 id < 1000 에서 던진다 → `crewTypeId >= 1000` 가드(CityDetailController 선례), 아니면 `"-"`. |
| N7 단계 이름·가드 | 8e 는 이미 vote 채널이다 → 새 단계는 **8g**. 모든 채널에 `isNotEmpty()` 가드 → 행 0 세계는 `lastOps` 0건(rehydrate 게이트가 lastOps 를 센다). |
| N8 결과 코드 핀 | `toCommandResultRows` 는 private → `TurnDaemonCommandResultSerializer` 왕복 테스트로 6 코드를 핀(실제 실패점은 `else -> throw`). |
| Q5(해소) | `RehydrateLosslessGateIT` 는 경매 투영 전용(표 열거 아님) → 두 표를 넣지 않는다. `WorldSnapshotLoaderRetainerTest` 가 왕복을 맡는다. |
| ADR-LITE-018(비평자 의견) | v1↔v2 DB 분리 조항과의 관계는 이 사슬이 이미 `v2_city_ledger`·`/api/v2/**` 를 같은 DB 에 둔 선례를 따른다 — **사용자 확인 항목**으로 남긴다(차단 아님). |

## 1. 원칙

1. **국가군과 별도**: 부곡은 장수 개인 소유. `general.crew` 와 `bugok.troops` 의 합은 보존된다 — 편성은 crew 에서 떼어 troops 로, 해산은 되돌린다. 전투·징병 기존 경로는 `general.crew` 만 본다.
2. **엔진만 쓴다**: 모든 변경은 인테이크 명령 → 엔진 핸들러 → 메모리 세계 상태 + `ChangeRecorder` → `DirtyState`/`FlushPayload` → `JdbcFlushExecutor`. game-api 는 읽기만.
3. **골든 불변**: 가신·부곡 행이 없는 세계에서 월 정산은 산출물(`DirtyState`·recorder 패치·로그)을 바이트 하나 바꾸지 않는다 — §8 적색 프로브가 증명한다. 기존 명령 경로는 손대지 않는다. NPC 는 서약하지 않는다(결정성).
4. **수치는 잠정 상수**: `logic` `RetainerRules` 한 곳에 이름 붙여 두고 문서·API·도움말이 같은 값을 읽는다. 실측 기준선이 아니며 게이트 임계값으로 쓰지 않는다(UI 에 「잠정」 표시).

## 2. 도메인 (Flyway `V55__general_retainers_and_bugok.sql`)

### general_retainers (세계 범위 — V32 규약)
| 열 | 뜻 |
|---|---|
| `world_id INTEGER NOT NULL`, `id INTEGER NOT NULL` — `PRIMARY KEY (world_id, id)` | id 는 **엔진 할당**(identity 아님, §3) |
| `master_general_id INTEGER NOT NULL` — `FOREIGN KEY (world_id, master_general_id) REFERENCES general(world_id, id) ON DELETE CASCADE` | 주인 |
| `origin VARCHAR(16) NOT NULL CHECK (origin IN ('EXISTING','RECRUITED'))` | ADR-017. 이 절편은 RECRUITED 만 INSERT |
| `general_id INTEGER NULL` — `FOREIGN KEY (world_id, general_id) REFERENCES general(world_id, id) ON DELETE CASCADE`, `CHECK ((origin = 'EXISTING') = (general_id IS NOT NULL))` | EXISTING 의 장수(다음 절편, 이 절편은 항상 NULL) |
| `name VARCHAR(24) NOT NULL` | RECRUITED 입력명(§4 정규화) |
| `relation VARCHAR(16) NOT NULL CHECK (relation IN ('staff','lieutenant','guest'))` | 관계 — 막료/부장/문객(로드맵·07 아트보드 라벨) |
| `role VARCHAR(16) NOT NULL DEFAULT 'NONE' CHECK (role IN ('STAFF','GUARD','QUARTERMASTER','SCOUT','ENVOY','NONE'))` | ADR-017 역할(참모·호위·군수관·정찰·사신·NONE). 이 절편은 저장·표시만, 효과 없음 |
| `has_own_bugok BOOLEAN NOT NULL DEFAULT false` | ADR-017(RECRUITED 기본 false) |
| `release_policy VARCHAR(16) NOT NULL CHECK (release_policy IN ('MUTUAL','MASTER_ONLY'))` | ADR-017(RECRUITED 는 MASTER_ONLY) |
| `loyalty INTEGER NOT NULL DEFAULT 50 CHECK (loyalty BETWEEN 0 AND 100)` | 충성 |
| `task VARCHAR(16) NOT NULL DEFAULT 'none' CHECK (task IN ('none','domestic','scout','train'))` | 임무(07 아트보드) |
| `created_at`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()` | |
| `UNIQUE (world_id, master_general_id, name)` · `INDEX (world_id, master_general_id)` | 모든 인덱스 world 선행 |

### general_bugok (세계 범위)
| 열 | 뜻 |
|---|---|
| `world_id`, `id INTEGER NOT NULL` — `PRIMARY KEY (world_id, id)` | 엔진 할당 |
| `master_general_id` — `FOREIGN KEY (world_id, master_general_id) REFERENCES general(world_id, id) ON DELETE CASCADE` | 주인 |
| `name VARCHAR(24) NOT NULL` | 「부곡 N」 자동(N = 주인 안 순번) |
| `troops INTEGER NOT NULL CHECK (troops > 0)` | 병력 |
| `crew_type_id INTEGER NOT NULL` | 편성 시 장수 병종 |
| `training`, `morale INTEGER NOT NULL CHECK (0..100)` | 편성 시 장수 훈련·사기 복사 |
| `fatigue INTEGER NOT NULL DEFAULT 0 CHECK (0..100)` | 피로 |
| `provisions INTEGER NOT NULL DEFAULT 0 CHECK (provisions >= 0)` | 군량 |
| `commander_retainer_id INTEGER NULL` — `FOREIGN KEY (world_id, commander_retainer_id) REFERENCES general_retainers(world_id, id) ON DELETE SET NULL (commander_retainer_id)` | 부장(relation=lieutenant)만. PG15+ 열 지정 SET NULL 로 `world_id` 보존 |
| `created_at`, `updated_at` | |
| `INDEX (world_id, master_general_id)` · `INDEX (world_id, commander_retainer_id)` | |

등록: `TruncateContract` 두 표, `V32WorldScopeCompletionMigrationTest.postV32WorldTables` 두 표(world_state FK 무액션 · `serialIdentityColumns` 미등록).

### 잠정 상수 (`opensamguk.logic.retainer.RetainerRules`)
| 이름 | 값 | 쓰임 |
|---|---|---|
| MAX_RETAINERS | 5 | 장수당 가신 상한 |
| MAX_BUGOK | 2 | 장수당 부곡 상한 |
| PLEDGE_COST_GOLD | 500 | 서약(등용) 자금 |
| RETAINER_UPKEEP_GOLD / RETAINER_UPKEEP_RICE | 30 / 30 | RECRUITED 월 유지비(ADR-017 upkeep) |
| MIN_BUGOK_TROOPS | 100 | 편성 최소 병력 |
| COMMANDER_MORALE_BONUS | 6 | 부장 배정 시 사기 +6(상한 100) — 07 아트보드 문구 |
| PROVISION_PER_TROOP_MONTH | 1 | 월 군량 소모 = troops × 값 |
| PAY_GOLD_PER_100_TROOPS | 10 | 월 급여 = ceil(troops/100) × 값 |
| MORALE_LOSS_UNPAID | 5 | 군량·급여 부족(둘 중 하나라도) 시 사기 −5, 한 번 |
| FATIGUE_REST / FATIGUE_TRAIN / TRAINING_GAIN | −5 / +10 / +2 | 월 피로·훈련 변화 |
| LOYALTY_TASKED / LOYALTY_IDLE / LOYALTY_LOSS_UNPAID | +1 / −1 / −5 | 월 충성 변화 |

## 3. 엔진 상태와 flush (F2)

- `TurnWorldModel`: `data class Retainer(id, masterGeneralId, origin, generalId: Int?, name, relation, role, hasOwnBugok, releasePolicy, loyalty, task)`, `data class Bugok(id, masterGeneralId, name, troops, crewTypeId, training, morale, fatigue, provisions, commanderRetainerId: Int?)`.
- `WorldSnapshot.retainers/bugoks`(기본 빈 목록). `WorldSnapshotLoader.loadRetainers()/loadBugoks()` 가 두 표를 읽는다(부팅·rehydrate 같은 경로). 행 0 이면 스냅샷은 기존과 동일하다.
- `InMemoryTurnWorld`: `retainers/bugoks` map + `dirty/created/deleted` 집합 + `createRetainer/updateRetainer/removeRetainer`, `createBugok/updateBugok/removeBugok`. `remove*` 는 `removeTroop` 규칙 그대로 — **같은 틱에 만든 행을 지우면 deleted 에 넣지 않는다**(DB 에 없는 행).
- **id 할당**: `allocateRetainerId()/allocateBugokId()` = `maxRetainerId/maxBugokId + 1`. 시드는 `maxOf(snapshot max id, world_state.meta["maxRetainerId"/"maxBugokId"])`. 영속은 자동이 아니다(N3) — 네 지점을 모두 고친다: ① `TurnRunService.runTick` 이 worldState 에 두 키를 공급(`max_general_id` 옆), ② `JdbcFlushExecutor` 의 world_state meta 병합 두 곳(정규 flush·retained flush)에 두 키 추가, ③ `WorldSnapshotLoader` 의 meta 허용 키 목록에 두 키 추가, ④ `InMemoryTurnWorld` 시드·bump. `WorldSnapshotLoaderRetainerTest` 는 flush → reload **왕복**으로 id 가 이어지는지 본다. 삭제된 최대 id 재사용 없음. 열은 identity 가 아니므로 시퀀스 충돌이 없다.
- **툼스톤 전파(N2)**: `InMemoryTurnWorld.removeGeneral(id)` 가 **그 자리에서** 그 장수를 주인(또는 EXISTING 의 general_id)으로 갖는 가신·부곡을 map 과 dirty/created/deleted 집합에서 제거한다(`generalPosition` 가지치기 `:270`·`removeNation`→diplomacy 선례). 장수 삭제는 인테이크(`InstantActionHandler`)와 예약 턴 드레인(`ReservedTurnHandler`) 양쪽에서 L10 정산·flush 보다 먼저 일어나므로, 정산이 고아 행을 만나지 않는다. DB 는 부모 CASCADE 가 지운다. 방어선: 정산은 `getGeneralById(master) == null` 인 행을 건너뛴다(§5).
- `DirtyState`/`FlushPayload`: `createdRetainers, updatedRetainers, deletedRetainerIds, createdBugoks, updatedBugoks, deletedBugokIds`. 행 단위(열 LWW 아님) — 메모리 최종 상태를 통째로 UPDATE.
- `JdbcFlushExecutor` **8g**(8e vote·8f 뒤; N7): 표마다 **DELETE → CREATE → UPDATE** — `retainerDeleteMany → retainerCreateMany → retainerUpdate → bugokDeleteMany → bugokCreateMany → bugokUpdate`. 이유(N1): 같은 틱 「홍길동 해제 → 홍길동 서약」 이 INSERT 먼저면 `UNIQUE (world_id, master_general_id, name)` 위반으로 같은 payload 가 영구 재시도 실패한다. 가신 DELETE 가 앞서도 부곡의 commander 는 DB `ON DELETE SET NULL (commander_retainer_id)` 가 지키고, 새 가신은 부곡 CREATE/UPDATE 보다 먼저 INSERT 된다. 모든 채널은 `isNotEmpty()` 가드 → 행 0 세계는 `lastOps` 에 0건. INSERT 는 명시 id, `world_id` 는 executor 가 주입(8d 선례). UPDATE 는 `requireExactlyOneAffected`. DELETE 는 `WHERE world_id = :world_id AND id IN (:ids)`.
- **같은 틱 시나리오(전부 메모리에서 해결)**: 편성 → 지휘관 배정(id 즉시 있음) / 배정(+6) → 정산(−5) = 51 유지 / 해제 → 지휘 부곡 commander NULL UPDATE + 가신 DELETE(DB 순서는 DELETE 먼저, SET NULL(col) 이 무해하게 겹침) / **해제 → 같은 이름 서약** 이 한 틱에 성공(DELETE → CREATE) / 상한은 메모리 count 로 두 번째 서약이 거부됨 / 주인 사망 → `removeGeneral` 이 즉시 가지치기, pending 없음.

## 4. 명령(인테이크, 즉시 실행) — 와이어 `common.TurnDaemonCommand` 6종 + `CommandWireMapper` allowlist + 디스패처 → `RetainerHandler`

공통 게이트 순서(board 미러, 6 명령 모두): ① 장수 없음(BoardHandler 와 같은 문자열) → ② 접속 제한 `AccessLogThrottle` 「접속 제한입니다.」(거부돼도 access_log·refresh KV 기록, board 와 동일) → ③ 입력 「올바르지 않은 입력입니다.」 → ④ 상태 게이트(아래 순서대로).

| 코드 (ADR 명) | 인자 | ③ 입력 | ④ 상태 게이트 순서 | 효과 |
|---|---|---|---|---|
| `retainerPledge` (가신서약) | name, relation, role? | 이름 §S8 규칙 / relation ∉ 3종 / role ∉ 6종(생략 = NONE) | 1 상한 「가신이 가득 찼습니다.」(메모리 count ≥ MAX_RETAINERS) → 2 중복 「같은 이름의 휘하가 있습니다.」 → 3 gold < PLEDGE_COST_GOLD 「자금이 부족합니다.」 | 주인 gold −500(F1 경로), `createRetainer(origin=RECRUITED, generalId=null, hasOwnBugok=false, releasePolicy=MASTER_ONLY, loyalty=50, task=none)`; 결과 `id` |
| `retainerRelease` (가신해제) | retainerId | 정수 아님 | 1 내 가신 아님 「휘하 인물이 없습니다.」 | 지휘 중인 부곡마다 `updateBugok(commander=null)` → `removeRetainer` |
| `retainerTask` (가신임무) | retainerId, task | task ∉ 4종 | 1 내 가신 아님 | `updateRetainer(task)` |
| `bugokForm` | troops, rice | 정수 아님 / troops < MIN_BUGOK_TROOPS / rice < 0 | 1 상한 「부곡이 가득 찼습니다.」 → 2 crew < troops 「병력이 부족합니다.」 → 3 general.rice < rice 「군량이 부족합니다.」 | crew −troops, rice −rice(F1 경로), `createBugok(name=「부곡 N」, crewTypeId·training·morale = 장수 값, fatigue 0, provisions=rice)`; 결과 `id` |
| `bugokDisband` | bugokId | 정수 아님 | 1 내 부곡 아님 「부곡이 없습니다.」 → 2 병종 ≠ 장수 crewTypeId 「병종이 다릅니다.」 | crew +troops, rice +provisions(F1 경로; train/atmos 불변), `removeBugok` |
| `bugokAssignCommander` | bugokId, retainerId(null 허용) | 정수 아님 | 1 내 부곡 아님 → 2 retainerId 있으면 내 가신 아님 「휘하 인물이 없습니다.」 → 3 relation ≠ lieutenant 「부장만 배정할 수 있습니다.」 | `updateBugok(commander)`; 새로 배정할 때만 morale +COMMANDER_MORALE_BONUS(상한 100) |

결과: `RetainerActionResult(type = 코드, ok, generalId, reason?, id?)` — sealed 등록 + `TurnDaemonCommandResultSerializer` 분기. 6 코드는 **직렬화기 왕복 테스트**로 핀한다(`toCommandResultRows` 는 private, 실제 실패점은 직렬화기의 `else -> throw` — N8).

## 5. 월 정산 (`opensamguk.engine.retainer.RetainerMonthlyService.settle(world, recorder)`)

- **배치**: `MonthlyPostUpdateHook` 생성자에 `retainerMonthly: RetainerMonthlyService? = null` 을 추가하고 `run()` 의 **맨 끝**(`postUpdateMonthlyTail` 뒤)에서 `retainerMonthly?.settle(world, recorder)`. `DaemonLoopConfig` 가 인스턴스를 넘긴다. null 은 「미배선」 이며 테스트 전용(문서화). L6 조기 반환이면 L10 자체가 안 돌므로 정산도 없다.
- **행 0 이면 즉시 반환** — `listBugoks().isEmpty() && listRetainers().isEmpty()`. 산출물 무접촉(§8 적색 프로브).
- 행마다 주인을 **그 시점에** `world.getGeneralById(master)` 로 읽는다(`run()` 시작에 잡은 장수 스냅샷을 쓰지 않는다 — N2). null 이면 그 행은 건너뛴다(방어선; 정상 경로는 `removeGeneral` 가지치기로 이미 없다).
- **부곡**(id 오름차순): `consumption = troops × PROVISION_PER_TROOP_MONTH`, `pay = ceil(troops / 100) × PAY_GOLD_PER_100_TROOPS`. `shortProv = provisions < consumption`; `provisions' = max(0, provisions − consumption)`. `shortPay = master.gold < pay`; shortPay 가 아니면 gold −= pay(F1 경로), 맞으면 미지급(부분 지급 없음). `morale' = max(0, morale − (shortProv || shortPay ? MORALE_LOSS_UNPAID : 0))`(한 번). 지휘 부장이 있고 그 task = train 이면 `fatigue' = min(100, fatigue + FATIGUE_TRAIN)`, `training' = min(100, training + TRAINING_GAIN)`; 아니면 `fatigue' = max(0, fatigue − FATIGUE_REST 절대값)`. 값이 하나도 안 바뀌면 dirty 로 표시하지 않는다.
- **가신**(id 오름차순, RECRUITED): 정산 시작 시 `loyalty == 0` 이면 **떠난다** — 지휘 부곡 commander NULL UPDATE → `removeRetainer` → 로그 `LogEntryDraft(scope="general", category="action", generalId=주인, text="<Y>{name}</>{JosaUtil.pick(name,"이")} 떠났습니다.")`. 아니면 `upkeepPaid = master.gold ≥ RETAINER_UPKEEP_GOLD && master.rice ≥ RETAINER_UPKEEP_RICE`; 지불하면 gold/rice 차감(F1 경로), `loyalty' = clamp(0..100, loyalty + (task == none ? LOYALTY_IDLE : LOYALTY_TASKED) + (upkeepPaid ? 0 : LOYALTY_LOSS_UNPAID))`.
- 같은 달 두 번 정산하지 않는다(L10 은 월 경계마다 1회).
- 순수 함수: `RetainerRules.settleBugok(input): BugokSettlement`, `RetainerRules.settleRetainer(input): RetainerSettlement` — 엔진 서비스는 이 결과를 메모리에 적용만 한다.

## 6. 읽기 API (game-api)

- `GameApiSecurityConfig`: `.requestMatchers("/api/my-retinue", "/api/generals/*/retinue").authenticated()` 추가(스펙 변경 항목).
- `GET /api/my-retinue`: principal 없음 401 · 이 세계에 내 장수 없음 → `MyController` idiom 그대로 · 200 `{generalId, retainers:[{id,name,origin,relation,role,loyalty,task,hasOwnBugok}], bugoks:[{id,name,troops,crewTypeId,crewTypeName,training,morale,fatigue,provisions,provisionMonths,commanderRetainerId}], rules:{maxRetainers,maxBugok,pledgeCostGold,minBugokTroops,retainerUpkeepGold,retainerUpkeepRice,payGoldPer100Troops,provisionPerTroopMonth,provisional:true}}`. `provisionMonths = provisions / max(1, troops × provisionPerTroopMonth)`(정수). `crewTypeName = if (crewTypeId >= 1000) UnitCatalog.byId(crewTypeId)?.name ?: "-" else "-"`(`byId` 는 1000 미만에서 던진다, `general.crew_type_id DEFAULT 0` — N6).
- `GET /api/generals/{id}/retinue`: 401 익명 · 200 본인 · 200 같은 국가(둘 다 nationId ≠ 0 이고 같음) · 403 그 외 · 대상이 재야(nationId 0)면 본인만 200.
- 원천: 읽기 엔티티 `RetainerReadEntity/BugokReadEntity`(`gameapi/read`, world-scoped repo). 상수는 `RetainerRules` 를 그대로 노출.

## 7. UI (07 아트보드, `/game/my` + 작전실)

- `/game/my` 「휘하 인물」 패널: 행마다 `Portrait size="card-44"`(기본 초상), 이름, 관계 칩(막료/부장/문객), 역할, 충성 `Gauge`, 임무 select(→ `retainerTask`), 「해제」 `Button`(→ `retainerRelease`). 「서약」 폼: 이름·관계·역할·비용 `rules.pledgeCostGold` 표시. 상한 도달이면 버튼 disabled + 사유 「가신이 가득 찼습니다」.
- 「부곡」 패널: 표 편제·병종·지휘(부장 select → `bugokAssignCommander`)·병력·훈련·사기·피로·군량(`n개월`)·조치(해산). 「편성」 폼: 병력·군량(현재 crew/rice 표시, 최소 `rules.minBugokTroops`). 안내 「부곡은 장수 개인의 사병입니다…」(07 문구) + 「잠정」 칩(`rules.provisional`).
- 명령은 모두 `CommandModal` pinnedCommand + extraArgs(회의실과 같은 경로, 202 ≠ 성공 → 터미널 결과까지 폴링). 성공 후 `/api/my-retinue` 재조회.
- 작전실 조작 대상 바 「휘하」 슬롯(D3-17): 가신 수·부곡 수 배지 + `/game/my#retinue` 링크. 없으면 「휘하 없음」(점선·사유 「서약하면 여기 나옵니다」).
- 상한·비용은 응답 `rules` 로만 표시(하드코딩 금지).

## 8. 테스트·게이트

- logic `RetainerRulesTest`: (a) 정산 순수 함수 입력→출력 표 — 정상, 군량 부족, 급여 부족, 둘 다(−5 한 번), 훈련 부장, 충성 0 이탈, 유지비 미지급; (b) 6 명령의 게이트 **순서** 표(두 조건을 동시에 위반시켜 먼저 나오는 문자열을 핀); (c) 이름 정규화(NFC·trim·내부 공백·길이·중복). 상수 값 단언 없음.
- engine `RetainerIntakeTest`: 명령마다 `recorder.generalPatches()` 의 gold/crew/rice 열(메모리 값이 아니라 패치), `consumeDirtyState()` 의 created/updated/deleted; §3 같은 틱 시나리오 5종; 만들고 같은 틱에 지우면 DB 작업 0.
- engine `RetainerMonthlyNoopGateTest` (**적색 프로브**, N4): 같은 fixture 로 **world+recorder 를 두 벌 새로** 만들어 각각 `MonthlyPostUpdateHook` 을 한 번씩 돌린다 — 한 벌은 `retainerMonthly = null`, 한 벌은 배선. Q15/Q16 이 벽시계·RNG 를 읽으므로 `MonthlyPostUpdateHookTailWiringTest` 의 `ScriptedRng`/`auctionRepo()` fixture 를 그대로 쓴다. 행 0: `consumeDirtyState()`·recorder 패치·로그가 deep-equal. 같은 테스트가 부곡 1행을 넣은 두 벌로 다시 돌려 **달라짐**을 단언한다(게이트가 경로를 보는 증거). 기존 골든(logic 274 json · engine 4, `LongSimReplayGateTest` 등)은 회귀로만 돌리고 증거로 세지 않는다.
- engine `WorldSnapshotLoaderRetainerTest`: 두 표 적재 + id 시드 flush→reload 왕복(N3). `RehydrateLosslessGateIT` 는 경매 투영 전용이라 손대지 않는다(Q5 해소).
- infra `RetainerFlushIT`(Testcontainers PG16): V55 DDL; 8g DELETE→CREATE→UPDATE 순서 + 「해제→같은 이름 서약」 한 payload 성공(N1); 지휘 부장 DELETE 뒤 `bugok.world_id` 보존·commander NULL(F3); 주인 general DELETE 의 CASCADE; V32 인벤토리 테스트 두 표 통과.
- game-api `RetainerReadControllerTest`: 401 익명 / 200 본인 / 200 같은 국가 / 403 타국 / 403 재야-타인 / `rules.provisional`.
- vitest: `/game/my` 두 패널(라벨·폼 → 모달 extraArgs·disabled 사유), 작전실 휘하 배지, 「잠정」 칩.
- 경로 없는 세계 바이트 동일: 위 적색 프로브 + 기존 골든(logic 274 · engine 4) 회귀 + 행 0 세계 `lastOps` 0건(N7).

## 9. 마이그레이션 번호

V55 = 4X-A(이 절편). V56 = 4X-B 작전(부곡 `city_id` 포함 가능). V57 = 4X-C 봉인·리플레이. 계획 표에 못박는다(과거 V45 충돌 재발 방지).

## 10. 남는 UNKNOWN

- EXISTING 가신(기존 장수 서약)의 동의 흐름·`has_own_bugok=true` 의 병력 의미 — 다음 절편 스펙.
- `role` 과 `task` 의 실제 효과(명령 효율 +8% 등) — 기존 명령 경로를 건드리므로 골든과 함께 별도 절편.
- ADR-LITE-018 의 v1↔v2 DB 분리 조항과 이 절편의 관계 — 선례(`v2_city_ledger`·`/api/v2/**` 가 같은 DB)를 따르되 사용자 확인 항목.
