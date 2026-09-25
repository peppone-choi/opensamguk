# 게임 로그 모델 재설계

상태: 설계 승인 범위(2026-09-25), 제품 구현 전. [ADR-LITE-068](../../../.ai/decisions.md) 결정의 구현 계약이다. 근거의 `경로:줄`은 `origin/main` 04fa428a 기준이며, 개명·삼모 삭제 작업 후 `rename-map.md`로 경로를 다시 해석해야 한다. 제품 코드·DB·웹은 이 PR에서 바꾸지 않는다.

## 1. 확정 범위와 현재 사실

사용자 확정: 다섯 분류(개인 행적 / 휘하·세력 / 조정 공문 / 전장 보고 / 천하 정세), 연감 별도, 사건 종류·구조화 refs·시각 저장, 화면 문장 생성, 담담한 알림체, 공개 사건 즉시 천하 정세, 타 세력 내부 사건 비공개, 지연 공개는 확장 필드만, 옛 로그 이행·보관 없음, 리셋 뒤 새 월드부터 적용, 해마다 공개 사건과 판도 자동 요약.

현행 사실은 설계안과 다르다. `TurnWorldModel.kt:267-282`의 draft는 `text` 필수, `eventKind` 선택이다. `InMemoryTurnWorld.kt:604-611`이 누락 시 연·월·순을 찍고, `DatabaseHooks.kt:879-904`가 scope/category를 PG 리터럴로 바꾸며, `JdbcFlushExecutor.kt:1512-1539`가 text·event_kind·meta를 한 `log_entry`에 저장한다. `HwihaRecords.kt:15-51`은 `meta.refs`와 text를 동시에 저장한다. `RecordKind.kt:9-14,57-61`의 세력 요약 allowlist는 공개 사건 세 종류와 세계 발표 한 종류뿐이다. `HwihaRecordReadRepository.kt:42-75`는 개인·세력 요약을 순 창으로 읽으며, `HwihaLastTurnsReader.kt:33-69`은 최대 36순·기본 12순이다. 반면 공개 `/api/world-log`는 `WorldLogReadRepository.kt:34-44`의 `SYSTEM/HISTORY|SUMMARY` 최신 30행을 text로 반환한다(`WorldLogController.kt:9-15,23-46`). `HistoryController.kt:15-27,75-85`와 `HistoryReadRepository.kt:41-84`는 월별 지도·세력·문장 배열 연감이다.

### 현재 스키마·인덱스

| 근거 | 현재 계약 | 설계 영향 |
|---|---|---|
| `infra/.../V1__baseline.sql:3-4,249-266` | `log_scope` SYSTEM/NATION/GENERAL/USER, `log_category` HISTORY/SUMMARY/ACTION/BATTLE_BRIEF/BATTLE_DETAIL/USER, text 필수. 처음 인덱스는 scope/general/nation/user+category+id. | scope/category가 공개 등급을 뜻하지 않는다. |
| `infra/.../V22__log_entry_current_phase.sql:1-5` | phase 1..3. | 새 사건 시각에도 유지. |
| `infra/.../V29__log_entry_year_month_index.sql:6-16`, `V32__complete_world_scope_expand.sql:448-457` | 연월 인덱스 도입 후 world 선두 인덱스로 재작성. | 연감의 연도·공개 질의에 별도 인덱스가 필요. |
| `infra/.../V60__log_entry_event_kind.sql:1-23` | event_kind nullable; 개인 `(world,general,year,month,phase,id)`와 세력 `(world,nation,year,month,phase,id)` 부분 인덱스. | 공개 범위·다섯 피드·연간 수집에는 해당 인덱스만으로 부족. |
| `infra/.../V1__baseline.sql:227-236`, `V19__yearbook_global_logs.sql:1-10` | 월별 `yearbook_history` map/nations, V19 `global_history`/`global_action` text JSON 배열. | 연간 공개 사건·판도 스냅샷으로 새 계약 필요. |

기존 Flyway는 수정하지 않는다(ADR-LITE-066). `world_id` 범위는 기존 인덱스와 모든 읽기 질의에서 필수다. 새 스키마는 후속 PR에서 전진 마이그레이션으로 만들고, 구 로그가 들어 있는 월드를 새 API가 읽지 못하도록 세계 형식 가드를 둔다. 리셋은 별도 운영 승인 대상이다.

## 2. 새 저장 계약

`GameEvent` 초안: `{id, worldId, kind, category, audience, audienceGeneralId?, audienceNationId?, occurredAt:{year,month,phase,ordinal}, refs, facts?, publication:{state,publishAfter?}, eventKey}`. `kind`는 중앙 `EventKind` 목록의 안정 문자열, `category`는 다섯 분류 중 하나, `audience`는 `SELF|RETINUE|NATION|COURT|PUBLIC`이다. 한 사건을 여러 화면에서 보여도 저장은 한 번 한다. `id`/`eventKey`는 중복 입력·재시도 방지와 커서에 쓰며, `ordinal`은 같은 순 안의 결정론적 순서다. 시각은 현재 턴에서 찍되 역사 사건을 소급할 때는 명시한 발생 시각과 기록 시각을 구분한다. `refs`의 객체 식별자는 정수 `generalId`, `cityId`, `nationId`, 안정 `corpsId`, `requestId` 또는 해당 도메인 안정 키로 타입 검증한다. 값 하나의 의미는 kind별 스키마로 고정한다. null, 빈 문자열, 이름만 있는 ref는 식별자가 아니다. 이름·색·태그·문장·임의 JSON 덤프는 저장하지 않는다.

`facts`는 `amount`, `troopsBand`, `outcome`처럼 kind별로 허용된 작은 구조화 값만 쓴다. 경제량·적 병력·내부 계획은 audience가 허용할 때만 둔다. 서버의 문장 요약 필드는 **불필요**하다. 연감 집계용 수치도 구조화 facts로 저장/계산한다. 전투 상세는 replay id를 참조하고 별도 전투 데이터 읽기 권한을 적용한다. 로그 본문에 전투 데이터를 복제하지 않는다.

`publication.state=PRIVATE|PUBLISHED`와 nullable `publishAfter`를 두되 v1은 `publishAfter=null`만 쓴다. 향후 지연 공개는 별도 승인 규칙과 재검증된 안전 refs, 원본 사건과 공개 투영의 연결 키가 필요하다. 지금은 지연 공개 스케줄러·자동 승격·기본 지연 시간을 만들지 않는다. `PUBLIC`은 즉시 PUBLISHED이고 읽는 쪽에서 다시 scope를 검증한다. 다른 audience는 PRIVATE이며 `publishAfter`가 있더라도 v1은 공개하지 않는다.

권한 행렬: `SELF`는 해당 장수 소유 계정, `RETINUE`는 해당 휘하 관계의 허용된 인물(관계 종료 뒤 열람 정책은 구현 전에 고정), `NATION`은 **현재** 동일 세력의 허용된 장수, `COURT`는 발령·포상 등 공문 수신자/발신자와 정해진 관직, `PUBLIC`은 비로그인 포함 모두다. 개인 사건이 세력에 유용하다는 이유만으로 세력 전체에 올리지 않는다. 공개 점령·멸망·월단평 발표는 공개될 사실만 별도 kind/schema에서 허용한다. 타 세력의 세금, 창고, 행군 목적, 전력, 명령 실패와 조우 봉인 정보는 PUBLIC에 넣지 않는다. #343 시야 계약은 `docs/superpowers/specs/2026-09-23-hwiha-vision-contract.md:89-110`을 따른다. 권한은 저장 시와 반환 시 모두 검사하고, 링크가 붙은 전투 replay·지도 투영도 별도 권한을 다시 검사한다.

### API·조회

`GET /api/events?section=&before=&limit=`은 본인 인증 범위의 개인/휘하/조정/전장 피드를, 공개 `GET /api/world-events?before=&limit=`은 천하 정세만 반환한다. `limit` 1..50, 기본 30; 커서는 `(occurredAt,ordinal,id)`의 불투명 인코딩이고 동일 시각의 누락·중복 없이 역순으로 간다. 서버가 audience에 따라 필터한 뒤 `kind,category,occurredAt,refs,facts`만 반환한다. 화면은 `kind`별 템플릿으로 문장을 만든다. 알 수 없는 kind는 안전한 일반 문구("기록을 표시할 수 없습니다.")와 id만 보이고 refs 덤프를 금한다. 필수 ref가 삭제·권한 변경으로 해석 불가하면 역할 대명사("어느 인물", "어느 城") 또는 공개 안전한 placeholder를 쓰되 사실을 추측하지 않는다. 비공개 ref는 문장 일부가 아니라 해당 사건 전체를 숨기거나 승인된 공개 투영으로 바꾼다. 구조 검증 실패는 서버에서 기록하고 API의 원시 JSON은 노출하지 않는다.

새 읽기 저장소 인덱스 초안: `(world_id, audience, audience_general_id, occurred_at DESC, ordinal DESC, id DESC)`와 nation/court용 대응 인덱스, `PUBLISHED` 부분 인덱스 `(world_id, occurred_at DESC, ordinal DESC, id DESC)`, 연감용 `(world_id, published_year, id)` 또는 연도 파티션. 실제 쿼리 계획과 분포 측정 후 최소 집합만 남긴다. 현행 `NationLogReadRepository.kt:10-19`와 `AdminGeneralLogReadRepository.kt:13-47`의 무제한 history 조회는 새 API에서 커서 페이지로 대체한다. 현행 개인 action은 30행 id 커서(`GeneralLogController.kt:70-103`), 월드 공개는 최신 30행만(`WorldLogController.kt:23-46`)이라는 차이를 해결한다.

### 화면 문장과 당시 지명

문체는 모든 화면에서 담담한 알림체다. `kind`별 고정 템플릿과 날짜 배지를 쓰며 연대기체 서술·색 태그·`<1>` 날짜 접미사를 쓰지 않는다. 예: `march.assignment` "장수 {general}이 {city} 부임을 시작했습니다."; `court.dispatchReceived` "{issuer}의 발령이 도착했습니다."; `encounter.disbanded` "{city} 인근 조우가 전투 없이 종료됐습니다."; `county.captured` "{city}의 소유 세력이 {fromNation}에서 {toNation}으로 바뀌었습니다."; `income.monthly` "이번 달 세력 수입이 집계됐습니다."; `yuedan.announced` "{year}년 {month}월 월단평 결과가 발표됐습니다." 숫자 없는 문장에 임의 수치를 보태지 않는다.

이름은 현 월드의 행정 투영을 통해 **사건 발생 시점**의 도시·郡 표시 이름을 해석한다. `2026-09-25-administrative-overlay.md:18-25,35-54,76-93`의 안정 city id, delta 순서·해시, 190 許縣/196 허도 표시/220 許縣/221 許昌縣 규칙을 따른다. 사건에 name 문자열을 넣지 않는다. 연감이 과거 사건을 재렌더할 때는 당시 투영 핀 또는 당시 적용 delta 위치가 필요하며, 현재 이름으로 과거 사건을 일괄 바꾸지 않는다. 핀이 없거나 검증 실패하면 fail-closed/일반 장소 표기와 진단을 택한다. 어느 방식을 쓸지는 행정 오버레이 구현에서 결정되는 저장 계약에 결속한다.

## 3. 기존 RecordKind 전수 배정

정본 `logic/src/main/kotlin/opensamguk/logic/input/RecordKind.kt:19-61`의 31개 사건 상수를 빠짐없이 배정한다. 아래 분류는 화면 자리이며, audience는 별도다. 전장 보고라도 내 군단의 이동 경로는 공개가 아니다. 기존 kind 문자열은 새 모델에서 유지하거나 명확한 이름으로 개명할 수 있지만 대응표와 템플릿 테스트를 둔다.

| 분류 | 기존 kind | 기본 audience·판정 규칙 | 알림체 예 |
|---|---|---|---|
| 개인 행적 | `personal.applied`, `input.rejected`, `renown.event`, `yuedan.assessed` | SELF. 명망 원인에 타인의 숨은 정보가 있으면 사유를 안전하게 투영. | "이번 순 행동이 반영됐습니다." / "명망이 변경됐습니다." |
| 개인 행적 | `enlist.joined` | SELF; 출사한 사실을 천하에 알릴지는 별도 공개 kind가 있을 때만. | "{nation}에 출사했습니다." |
| 개인 행적 | `march.assignment` | SELF. 부임·직위 이동은 개인 행적이며 군단 행군과 구분. | "{city} 부임을 시작했습니다." |
| 휘하·세력 | `enlist.retainerJoined`, `people.searched`, `people.joined`, `people.resisted`, `retinue.departureJudged`, `retinue.departed` | SELF 또는 RETINUE. 거절 대상·이탈 인물은 당사자에게만. | "{person}이 휘하에 합류했습니다." / "{person}의 이탈 판정이 있었습니다." |
| 휘하·세력 | `field.applied`, `income.monthly` | 행동자는 SELF, 세력 결산은 NATION. 자원량은 타 세력 비공개. | "{city}의 현장 행동이 반영됐습니다." / "월 수입이 집계됐습니다." |
| 조정 공문 | `court.dispatchIssued`, `court.dispatchReceived`, `court.dispatchAccepted`, `court.dispatchRefused`, `court.dispatchCancelled` | COURT의 발신/수신 대상 각각. 같은 요청 ID를 참조하지만 수신자별 안전 투영을 허용. 세력 전체 공개 금지. | "{person}에게 발령을 보냈습니다." / "발령이 취소됐습니다." |
| 전장 보고 | `march.corps`, `march.direct`, `military.musterOrdered`, `deploy.started` | SELF/RETINUE/NATION 중 전술 권한에 맞게. 행군 목적을 적에게 공개 금지. | "{corps}가 {city}로 이동했습니다." / "부대가 출병했습니다." |
| 전장 보고 | `encounter.personal`, `encounter.pending`, `encounter.disbanded`, `roadFort.siege` | 참가 당사자/허용 세력만. 적 편제·병력·봉인 계획은 refs/facts에서도 제거. | "{city} 인근에서 조우가 발생했습니다." / "보루 공성이 시작됐습니다." |
| 천하 정세 | `county.captured`, `county.lost`, `roadFort.captured`, `yuedan.announced` | PUBLIC으로 승인된 사실만. `county.captured`와 `county.lost`는 같은 점령 전이를 가리키므로 새 모델에서는 **한** 소유권 변경 사건으로 정규화; 양측 개인/세력 결과는 별도 비공개 사건일 수 있다. 월단평은 공개 발표 요약만. | "{city}의 소유 세력이 {fromNation}에서 {toNation}으로 바뀌었습니다." |

모호성 규칙: `field.applied`가 전쟁 준비를 수행해도 화면은 휘하·세력에 둔다(효과는 별도 전장 사건). `renown.event`가 공개 점령으로 발생해도 명망 변화 자체는 개인 행적이고 공개 점령은 한 세계 사건이다. `march.assignment`는 부임이므로 개인 행적이며 군단 출정은 `march.corps`/`deploy.started`로 구분한다. `court.dispatch*`는 수신자별 공문이며 내부 명령 내용을 천하 정세에 옮기지 않는다. `yuedan.assessed`는 개인 점수, `yuedan.announced`는 공개 발표로 분리한다. `county.lost`를 별도 공개 행으로 쓰지 않아 공개 피드 이중 기록을 막는다.

## 4. 연감

연감은 피드 탭이 아닌 독립 화면이다. 해 말 마지막 순을 확정한 뒤 같은 월드 쓰기 트랜잭션/결정론 순서에서 `publication=PUBLISHED`이고 그해 발생·공개된 사건 ID를 수집한다. 사건 id 오름차순을 안정 기준으로 사용하되 실제 표시 순서는 발생 시각·순서다. 공개 변동(領地 소유권, 공개 건국·멸망, 공식 발표, 주요 전투 결과)을 kind별로 집계한다. 그 시점의 공개 지도 투영에서 세력별 소유 縣 수·공개 판도와 수도를 스냅샷으로 고정한다. 내부 금·쌀·장수 병력은 넣지 않는다. "주요" 선정 기준(예: 영토 변화/공식 발표/전투 결과의 kind allowlist, 같은 사건 중복 제거)을 버전 고정한다. 줄 수 상한을 넘으면 정렬 기준으로 자르되 전체 사건 ID는 페이지로 조회할 수 있어야 한다.

현재 연감은 `MonthlyPreUpdateHook.kt:125-190`가 월별 map/nations와 `currentGlobalLogs("history"|"action")` 문장 배열을 만들고, `WorldActionContext.kt:2129-2145`도 같은 형식의 insert를 만든다. `HistoryController.kt:36-98`은 월별 선택과 배열 반환, `HistoryReadRepository.kt:83-99`는 전체 월 목록 무제한 조회다. 새 연감 구현은 이 둘의 호출 소유권과 중복 archive 가능성을 먼저 확인하고, 연말 1회 스냅샷 + 연도별 조회·페이지를 정의한다. 월별 조회가 필요한 지도 기능은 별도 월별 지도 스냅샷으로 계속 둘 수 있으나, 옛 문장 배열을 새 연감의 근거로 삼지 않는다.

## 5. 이행·삭제 순서와 관문

코드 정리 중에는 이 스펙만 작성한다. `docs/development/sammo-dependency-audit.md`와 `sammo-deletion-candidates.md:20-32`가 밝힌 월 경계·기본 사건·월말 tail의 공유 동작은 삭제 목록 확정 전까지 보존한다. 순서는 동결 해제 후 새 기록 모델/전진 마이그레이션 → 살아 있는 쓰기 경로 모두 이동(예약 행동, 입장, 월말 수입·월단평·방랑, 공성·조우·전투, 국가 변동 포함) → 읽기 API와 권한 → 다섯 화면·연감 → 구 text 로그 쓰기/읽기와 `log_scope`/`log_category` 및 `sub_type` 사용 제거다. 옛 Flyway는 건드리지 않는다. 테이블 drop·enum drop은 모든 새 월드 생산자와 소비자 제거, 세계 형식 가드 확인, 참조 검색 0건 뒤 별도 전진 마이그레이션에서 한다. 과거 로그 변환·보관·호환 엔드포인트는 만들지 않는다.

검증 관문: (1) 입력 원장 활성 행동·결과 목록과 실제 사건 kind 목록을 전수 대조하고 성공/실패/지연 처리/월 경계/전투 이탈의 빠진 행을 보고한다. (2) 타 세력 내부 수입·정찰·군단 병력·발령을 담은 적색 fixture에서 PUBLIC·타국·비로그인·연감·replay 모두 0건, 소유자와 허용 세력에는 기대 건수. (3) 새로운 사건 작성 파일의 `<[A-Z0-9]+>` 색 태그, `<1>` 날짜, `text=` 문장 또는 문자열 합성을 차단하는 lint; 한 줄을 일부러 주입했을 때 lint 실패. (4) 점령 한 건의 공개 feed 1건·양측 개인 결과 권한, 재실행/중단 후 eventKey 중복 0건. (5) 190/196/220/221 사건·연감의 당시 지명, 다른 월드 투영 캐시 격리. (6) 36순 창과 50건 커서 경계, 같은 순 다량 사건 누락·중복 0건, 대표 월드 데이터의 `EXPLAIN`·p95를 기록한다. 결과는 구현 PR의 실제 로그·쿼리 계획으로 증명한다. 이 문서 PR은 설계와 정적 근거만 검증한다.

## 부록 A. 현행 기록 원장

경로 약칭 `E/`=`app/game-engine/src/main/kotlin/opensamguk/engine/`, `L/`=`logic/src/main/kotlin/opensamguk/logic/`, `C/`=`common/src/main/kotlin/opensamguk/common/`, `A/`=`app/game-api/src/main/kotlin/opensamguk/gameapi/`. 표의 `P`는 `/api/hwiha/last-turns` 본인 행→`LastTurnPanel`, `S`는 같은 API 본인 세력 요약→`LastTurnPanel`, `G`는 `/api/general-log`→`MyInfoLogPanel`/`battle-center`, `W`는 공개 `/api/world-log`→`world-log`/gateway `ServerLog`, `Y`는 공개 `/api/history`→연감, `—`는 확인된 직접 화면 소비자 없음이다. `G/W/Y`의 문장들은 화면에서 `LogText`로 렌더된다(`web/game/components/game/MyInfoLogPanel.tsx:60-69`, `web/game/app/game/world-log/page.tsx:86-99`, `web/gateway/components/ServerLog.tsx:64-75`). `S`의 nation 필터는 본인 세력 id와 allowlist에 한정된다(`HwihaRecordReadRepository.kt:54-75`). 표에서 `필요`는 새 사건으로 재작성해야 한다는 설계 판정, `삭제`는 삼모 전용임이 의존 감사에서 증명될 때만 삭제한다는 뜻, `미정`은 코드 정리 담당 감사의 결론이 필요하다는 뜻이다.

| 쓰기 근거(파일:줄) | 계열 · 현행 scope/category/sub_type/event_kind | 저장값 | 실제 읽기 권한·API/화면 | 삼모 삭제 후 |
|---|---|---|---|---|
| `E/hwiha/HwihaRecords.kt:15-29,50-51` | 휘하 개인 `GENERAL/ACTION/∅/kind` | 평문 + `meta.refs`(빈 맵도 가능) | 본인 소유 인증 P; 같은 세력 수뇌는 `G`에서도 일반 action 문장 조회 가능(`GeneralLogController.kt:53-76`) | 공통 `EventRecorder`로 필요 |
| `E/hwiha/HwihaRecords.kt:31-47` | 휘하 세력 `NATION/HISTORY`(allowlist) 또는 `NATION/SUMMARY`(내부), 세계 `SYSTEM/HISTORY`; `kind` | 평문 + refs | S는 자기 세력 allowlist+세계 kind; 세계 행은 W에도 보임. nation history 저장소는 호출부 없음(`NationLogReadRepository.kt:10-30`) | audience 분리 필요 |
| `E/hwiha/HwihaAssignmentMarchTurn.kt:88`, `HwihaCorpsMarchTurn.kt:75`, `HwihaTravelHandler.kt:63` | 휘하 개인 `GENERAL/ACTION/∅/march.*` | 평문+행군 refs | P, G; 다른 세력 직접 조회는 G 국가 검증 | 필요 |
| `E/hwiha/HwihaPersonalEncounter.kt:29,45,92-99`, `HwihaCorpsEncounterRecorder.kt:100`, `HwihaEncounterResolver.kt:88` | 휘하 개인 `GENERAL/ACTION/∅/encounter.*|march.direct|input.rejected` | 평문+refs | P, G; 참가자별 기록 | 전장 사건으로 필요 |
| `E/hwiha/HwihaMusterHandler.kt:66`, `HwihaDeployHandler.kt:48`, `E/siege/RoadFortSiegeService.kt:34,48,54-56` | 휘하 개인/세력 `GENERAL/ACTION`·`NATION/HISTORY`, `military.*|deploy.*|roadFort.*` | 평문+refs | P/G, 세력 `S`는 보루 점령만 | 필요 |
| `E/hwiha/HwihaDispatchExecutor.kt:40-42,74,104-114`, `HwihaCourtHandler.kt:298,312` | 휘하 개인 `GENERAL/ACTION/∅/court.dispatch*|input.rejected` | 평문+refs | 발신자/수신자 P, 일반 G의 수뇌 권한 주의 | 조정 공문 필요 |
| `E/hwiha/HwihaEnlistmentExecutor.kt:77-79`, `HwihaPeopleHandler.kt:56,115-137` | 휘하 개인 `GENERAL/ACTION/∅/enlist.*|people.*` | 평문+refs | 당사자 P/G | 필요 |
| `E/hwiha/HwihaFieldHandler.kt:108`, `HwihaCityMilitaryHandler.kt:93`, `HwihaTransferHandler.kt:62`, `HwihaLegacyDirectHandler.kt:103` | 휘하 개인 `GENERAL/ACTION/∅/field.applied` | 평문+refs | P/G | 공유 기능 여부 감사 뒤 필요 |
| `E/hwiha/HwihaPersonalHandler.kt:83`, `HwihaPoliticalHandler.kt:154`, `HwihaRetireHandler.kt:88`, `HwihaLegacyCourtExecutor.kt:112`, `HwihaLegacyStratagemExecutor.kt:112` | 휘하 개인 `GENERAL/ACTION/∅/personal.applied` | 평문+refs | P/G | 살아 있는 입력이면 필요, legacy executor는 미정 |
| `E/hwiha/HwihaTravelTurn.kt:22,30,53`, `HwihaUnitResupply.kt:38` | 휘하 개인 `GENERAL/ACTION/∅/input.rejected` | 평문+refs | P/G | 실패 이유 사건 필요 |
| `E/hwiha/HwihaRenownEventRecorder.kt:72-74,113` | 휘하 개인 명망 및 양 세력 점령/상실 `GENERAL/ACTION`·`NATION/HISTORY`, `renown.event|county.captured|county.lost` | 평문+refs | 명망 P/G; 양 세력 중 자기 세력 사건만 S. 공개 W에는 현행 두 점령 행이 직접 실리지 않음 | 공개 소유권 사건 1건 + 개인 결과 필요 |
| `E/hwiha/HwihaMonthlyCountyIncome.kt:90`, `HwihaMonthlyAssessment.kt:115,122,131,154` | 세력 `NATION/SUMMARY/income.monthly`, 개인 `GENERAL/ACTION/yuedan.assessed|retinue.*`, 세계 `SYSTEM/HISTORY/yuedan.announced` | 평문+refs | 수입 S에서도 제외; 개인 P/G; 발표 S/W | 필요 |

위 휘하 호출부는 `RecordKind.kt:19-61`의 어휘와 `HwihaRecords` 공통 쓰기를 사용한다. `ReservedTurnHandler.kt:454`에서도 휘하 개인 기록을 직접 호출한다. kind를 갖지 않는 아래 휘하 생산자는 `event_kind IS NOT NULL` 조건(`HwihaRecordReadRepository.kt:42-70`) 때문에 P/S에 **나타나지 않는다**. 이는 쓰기가 없다는 뜻이 아니다.

| 쓰기 근거(파일:줄) | 계열 · 현행 scope/category/sub_type/event_kind | 저장값 | 실제 읽기 권한·API/화면 | 삼모 삭제 후 |
|---|---|---|---|---|
| `E/hwiha/HwihaScoutHandler.kt:67-68`, `HwihaRewardExecutor.kt:40-41`, `HwihaMonthlySalary.kt:41-42` | 휘하 평문 `GENERAL/ACTION/∅/∅` | 정찰·포상·녹봉 문장, refs 없음 | G만; P 누락. 정찰 메타의 비밀 사실은 별도 저장(`HwihaScoutHandler.kt:61-66`) | 입력/결과 사건 필요 |
| `E/hwiha/HwihaDomesticTurn.kt:140`, `HwihaDomesticBoundary.kt:249`, `HwihaPlacementMarch.kt:122` | 휘하 평문 `GENERAL/ACTION/∅/∅` | 행동 문장 | G만; P 누락 | 필요 |
| `E/hwiha/HwihaSiegeService.kt:348-350,406-407`, `HwihaEncounterResolver.kt:219`, `E/war/BattlefieldTurnHandler.kt:94` | 휘하 전장 평문 `GENERAL/ACTION/∅/∅` | 점령·공성·조우·전장 문장 | G만; P 누락. 같은 점령의 `HwihaRenownEventRecorder` 기록과 의미 중복 가능 | 전장/공개 경계로 재작성 필요 |
| `E/hwiha/HwihaCapitalAfterCapture.kt:25`, `E/operation/OperationMonthlyService.kt:75,85`, `E/intake/OperationHandler.kt:45`, `BattlePlanHandler.kt:78` | 휘하·공유 평문 `NATION/HISTORY` 또는 `GENERAL/ACTION`, kind 없음 | 작전/수도/계획 문장 | 개인 G; nation history 직접 소비자 미확인; P/S 누락 | 실행 기능 감사 후 필요 |
| `E/retainer/RetainerMonthlyService.kt:54-60` | 휘하 월말 `GENERAL/ACTION/∅/∅` | 태그 문장, refs 없음 | G만; P 누락 | 휘하 이탈 중복 여부 확인 후 필요 |

다음은 `event_kind=null`인 기존/공유 작성 경로다. `C/log/ActionLogger.kt:6-19`의 `pushGeneralHistoryLog`, `pushGeneralActionLog`, `pushGeneralBattleResultLog`, `pushGeneralBattleDetailLog`, `pushNationHistoryLog`, `pushGlobalHistoryLog`, `pushGlobalActionLog`는 각각 `GENERAL/HISTORY`, `GENERAL/ACTION`, `GENERAL/BATTLE_BRIEF`, `GENERAL/BATTLE_DETAIL`, `NATION/HISTORY`, `SYSTEM/HISTORY`, `SYSTEM/SUMMARY` 초안을 만든다. `LogFormatter.kt`의 서식·색 태그가 text에 반영된다. 액션 로직의 `pushGeneral|Global|Nation` 호출·버퍼는 `L/actions/GeneralActionResolveContext.kt:145-247`와 `E/turn/ProcessNationCommand.kt:809-816,837-858`, `E/turn/ReservedTurnHandler.kt:816-818,2249-2265`에서 엔진 초안으로 변환된다. 아래 표의 여러 호출은 같은 sink의 변종이므로 줄 범위로 묶었다. `sub_type`은 표시 text 대신 일부 세계 action 경로의 `type.toString()`에만 들어간다(`WorldActionContext.kt:2309-2318`). `format` 숫자는 `LogEntryDraft`에 있지만 `DatabaseHooks.kt:885-904`의 LogRow 매핑에는 없다.

| 쓰기 근거(파일:줄) | 계열 · 현행 scope/category/sub_type/event_kind | 저장값 | 실제 읽기 권한·API/화면 | 삼모 삭제 후 |
|---|---|---|---|---|
| `E/turn/ProcessNationCommand.kt:809-816,837-858`, `E/turn/ReservedTurnHandler.kt:503-562,816-818,974-1416,2223-2265` | 예약·국가 명령, `GENERAL/ACTION|HISTORY`, `NATION/HISTORY`, `SYSTEM/ACTION|HISTORY`; 대부분 ∅/∅ | 태그/평문 혼합, refs 없음 | G, W(글로벌 행), nation history 직접 소비 미확인; P/S에는 누락 | 휘하 살아 있는 명령은 사건 필요; 삼모 전용 명령은 감사 후 삭제 |
| `E/world/WorldActionContext.kt:209-224,410-500,725-759,1028-1030,1105-1153,1423-1452,2309-2318` | 세계 월/사건/조우 공통, `GENERAL/ACTION|HISTORY`, `NATION/HISTORY`, `SYSTEM/ACTION|HISTORY`; `sub_type` 일부 type 숫자 | 태그 문장, refs 없음 | G/W, Y에 text archive 경유 | 월 경계·공개 사건 살아 있으면 재작성 필요; 나머지 미정 |
| `E/run/MonthlyPostUpdateHook.kt:171-183,295-320`, `E/turn/RulerSuccessionHandler.kt:96-177` | 월말·방랑·승계, `SYSTEM/HISTORY` 또는 `GENERAL/ACTION|HISTORY` | 태그/평문, refs 없음 | W/G, Y 후보 | 공유 월말 유지 여부 감사 후 재작성/삭제 |
| `E/intake/MakeGeneralHandler.kt:285-356`, `ClaimNpcHandler.kt:98-107`, `SelectPoolHandler.kt:123-132`, `InstantActionHandler.kt:47-64` | 가입·장수 생성·선택·즉시 행동, `GENERAL/ACTION|HISTORY`, `SYSTEM/ACTION|HISTORY` | 태그/평문, refs 없음 | G/W; P/S 누락 | 제품 입장·즉시 행동은 필요; 옛 분기는 미정 |
| `E/intake/OperationHandler.kt:45`, `BattlePlanHandler.kt:78`, `E/operation/OperationMonthlyService.kt:75-85` | 작전·전투 계획, `NATION/HISTORY`, `GENERAL/ACTION` | 태그/평문, refs 없음 | G, nation history 직접 소비 미확인 | 살아 있는 작전은 필요 |
| `E/war/BattlefieldTurnHandler.kt:94`, `E/hwiha/HwihaSiegeService.kt:348-350,406-407`, `E/hwiha/HwihaEncounterResolver.kt:219` | 전투·공성·조우, `GENERAL/ACTION` | 평문, refs 없음 | G만, P 누락 | 전장 결과 필요 |
| `E/auction/AuctionOpenHandler.kt:323-329`, `E/tournament/TournamentDaemon.kt:152-158`, `ProductionTournamentBettingPort.kt:113,226` | 경매·토너먼트·베팅, `SYSTEM/HISTORY` 또는 `GENERAL/ACTION` | 태그 문장, refs 없음 | W/G; 공개 W는 현재 전부 노출 | 삼모 전용 판정이면 삭제; 제품에 남는 제도면 사건 재설계 |
| `E/turn/AiTurnAdapter.kt:599-606` | AI 명령 실패 `GENERAL/ACTION` | 태그 문장, refs 없음 | G; NPC 소유·수뇌 허용 범위 | 삼모 AI 삭제 시 삭제 |
| `L/event/EventAction.kt:158-159,200`, `L/event/FinishNationBetting.kt:84`, `OpenNationBetting.kt:86`, `L/betting/BettingEngine.kt:213` | 논리 이벤트/베팅의 `pushGlobalAction|HistoryLog`, `pushGeneralHistory|ActionLog`; 엔진 `WorldActionContext.kt:1028-1030,2309-2318` 등으로 전달 | 태그 문장, refs 없음; world action의 `sub_type`은 숫자 type | G/W/Y 경유 | 연도 알림은 필요, 베팅은 제품 존속 감사 뒤 삭제/재작성 |
| `L/world/InvaderEndingAction.kt:69-74`, `CheckEmperior.kt:51`, `ScenarioStartEventActions.kt:307-313,331,434`, `E/world/WorldActionContext.kt:1394-1396,1423-1452` | 시나리오·이민족·통일의 `pushGlobalAction|HistoryLog`/`pushNationalHistoryLog`; `SYSTEM/ACTION|HISTORY`, `NATION/HISTORY` | 태그 문장, refs 없음 | W/Y 또는 nation history 직접 소비 미확인 | 살아 있는 공개 사건은 새 kind 필요, 옛 전용 규칙은 삭제 감사 |

모든 `world.pushLog` 호출 파일은 위 행과 앞 휘하 행으로 망라했다(`rg -l 'world\.pushLog\(' app/game-engine/src/main/kotlin` 기준 28개 파일). 그러나 `pushLog`를 다른 변수·메서드로 간접 호출하는 경로, 액션 로직의 버퍼 생산 지점은 `C/log/ActionLogger.kt:6-19`와 `ProcessNationCommand.kt:809-816` 경계로 묶었다. 삭제/필요 판정은 정적 검색만으로 실행 가능 여부가 확인되지 않는 곳을 `미정`으로 남겼다. `rename-map.md:342,554`는 옛 `HwihaRecordKind.kt`가 지금 `RecordKind.kt`로 바뀌었음을 명시한다.

### 읽기 소비자·공개 경계 요약

| 읽기/화면 근거 | 실제 조건·제한 | 새 계약상 처리 |
|---|---|
| `A/read/HwihaRecordReadRepository.kt:42-75`, `A/read/HwihaLastTurnsReader.kt:33-69`, `A/web/HwihaLastTurnsController.kt:18-23`, `web/game/components/hwiha/LastTurnPanel.tsx:47-79` | 본인 소유 장수의 최근 12순(최대 36) 개인 기록 및 자기 세력 allowlist+세계 발표만. 화면은 비어 있지 않은 앞 6순과 요약 앞 4건만 표시. | 각 분류/권한을 조회 레벨에서 분리하고 12순 창이 버린 오래된 기록은 커서로 접근. |
| `A/controller/GeneralLogController.kt:53-103`, `A/read/AdminGeneralLogReadRepository.kt:13-47`, `web/game/components/game/MyInfoLogPanel.tsx:114-160`, `web/game/app/game/battle-center/page.tsx:56-62` | 같은 세력, 사관/수뇌 권한. 개인 action은 유저 장수 타인을 수뇌만; action/전투는 30건 id 페이지, history는 무제한. | 새 audience 권한과 전투 replay 권한을 명시. |
| `A/read/LogFeedReadRepository.kt:17-148` | 세계 history/action은 `SYSTEM` category로, 개인 action은 general id로 검색. 연월 조회 일부 무제한. | 공개 kind + publication 필터, 개인 소유 검증이 있는 서비스만 노출. |
| `A/read/NationLogReadRepository.kt:10-30` | `NATION/HISTORY` 전체 무제한; 이 저장소를 호출하는 컨트롤러는 정적 검색에서 확인되지 않음. | 사장 API 여부 확인 후 제거, 세력 피드 별도 권한. |
| `A/read/WorldLogReadRepository.kt:34-44`, `A/controller/WorldLogController.kt:23-46`, `web/game/app/game/world-log/page.tsx:39-99`, `web/gateway/components/ServerLog.tsx:24-75` | 비로그인 공개, `SYSTEM/HISTORY|SUMMARY` 최근 30건; 텍스트만. | `PUBLISHED` 사건만 즉시 공개, 구조화 refs와 안전한 템플릿. |
| `A/controller/HistoryController.kt:15-27,36-98`, `A/read/HistoryReadRepository.kt:41-99` | 공개 월별 map/nations와 `global_history`/`global_action` text 배열, 월 목록 전체 로딩. | 연말 공개 사건+판도 스냅샷, 연도 목록·사건 페이지. |

### 조사로 확인한 공백·이중 기록·공개 위험 후보

1. `HwihaScoutHandler.kt:67-68`, `HwihaMonthlySalary.kt:41-42`, `HwihaRewardExecutor.kt:40-41`, `HwihaDomesticTurn.kt:140`, `HwihaSiegeService.kt:348-350` 등 휘하 실행 결과는 kind 없는 평문이다. `HwihaRecordReadRepository.kt:45-47`의 `event_kind IS NOT NULL` 때문에 지난 순에는 빠진다. 이는 새 사건 전수 대조의 우선 대상이다.
2. `HwihaRenownEventRecorder.kt:72-74`는 한 점령에 포획·상실 두 국가 행을 쓴다. `HwihaSiegeService.kt:347-350`은 같은 전이 뒤 장수 개인 평문도 쓴다. 개인 결과는 필요할 수 있으나 공개 영토 전이는 한 사건만 필요하다. 현재 공개 W에는 nation 행이 안 나오므로 단순히 SYSTEM으로 옮기면 중복 공개 위험이 있다.
3. `WorldLogReadRepository.kt:37-40`의 `SYSTEM/SUMMARY` 전체 공개는 `ActionLogger.kt:17`의 global action과 `ProcessNationCommand.kt:815-816`의 전역 action을 내용별 심사 없이 노출한다. 실제 비밀 누출을 재현한 증거는 아직 없으므로 **위반 후보**다. 새 모델은 공개 가능 kind/refs allowlist로 닫는다. `GeneralLogController.kt:61-76`의 수뇌 타인 action 열람도 새 SELF 계약과 다르므로 정책 충돌로 검토한다. `2026-09-23-hwiha-vision-contract.md:110`은 지난 순 로그 시야 필터를 미완 범위로 적는다.
4. 쓰기량 실측은 없다. 코드로 확인되는 기준은 1년 36순(`HwihaTurnStamp.kt` 해당 타입은 `HwihaRecordReadRepository.kt:19-25`)과 운영 기본 1시간 1턴(`TurnDaemonHealthIndicator.kt:51-57`)이므로 평시 **하루 24턴**이다. 추정식은 `24 × (턴당 활성 장수 성공·실패 사건 수 + 턴당 조우/공개 사건 수) + 그날 월말/연말 집계 행`이다. 예를 들어 활성 장수 100명이 매 턴 한 건씩 쓴다면 개인 행만 하루 약 2,400건이라는 용량 시나리오이며, 관측값이 아니다. 구현 전에 실제 24시간 `world_id,kind,audience`별 count와 p95 최대 순 건수를 측정해 페이지·인덱스를 보정한다.
