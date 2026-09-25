# 사건 읽기 API 권한 경계 조사

기준: ADR-LITE-069, `docs/superpowers/specs/2026-09-25-game-log-model.md`, #343 시야 계약. 현재는 조사 문서만 작성한다. `app/game-api` 제품 파일, 특히 세계 형식 가드가 소유한 `WorldStateReadRepository.kt`는 편집하지 않는다.

## 현행 소비자와 교체 경계

| 현재 파일/경로 | 확인된 동작 | 새 계약 |
| --- | --- | --- |
| `RecordReadRepository` + `LastTurnsReader` + `LastTurnsController` (`/api/last-turns`) | `log_entry.event_kind IS NOT NULL` 행을 본인 GENERAL 범위와 nation/world 종류 allowlist로 최근 최대 36순 조회. text와 `meta.refs`를 그대로 DTO로 보낸다. | 새 `/api/events`는 kind/refs/facts와 수신 등급을 서버에서 재검사하고 1–50건 커서 페이지. 기존 12순 창은 새 피드의 유일한 진입점이 될 수 없다. |
| `WorldLogReadRepository` + `WorldLogController` (`/api/world-log`) | 인증 없이 `SYSTEM/HISTORY|SUMMARY` 최신 30개 text를 반환한다. 종류별 공개 심사는 없다. | `/api/world-events`는 `publication_state=PUBLISHED`, `audience=PUBLIC`, `section=WORLD`와 중앙 공개 kind 목록만. 비로그인 포함 같은 결과. |
| `AdminGeneralLogReadRepository` + `GeneralLogController` (`/api/general-log`) | 같은 세력/사관·수뇌 권한이면 남의 개인 기록도 조회 가능. history는 무제한, action/전투는 id 커서 30건. | SELF는 소유 계정만. 타인 열람은 별도 승인된 COURT/NATION 사건과 현재 권한으로 제한. 기존 history 무제한 조회 폐기. |
| `NationLogReadRepository`, `LogFeedReadRepository` | nation history 및 global 월별 조회 일부 무제한. 범용 scope/category 피드도 존재한다. | 임의 enum scope가 공개를 결정하지 못한다. 살아 있는 호출자를 추적한 뒤 새 사건 저장소로 전환/삭제한다. |
| `HistoryReadRepository` + `HistoryController` (`/api/history`) | 월별 `yearbook_history` 전체 목록과 text 배열을 공개한다. | 연말의 published 사건·공개 판도 스냅샷만으로 연감 구성. 연도 선택/사건 페이지, 내부 값 0건. 연감은 다섯 피드 탭과 별도. |
| `BattlePlanController` (`/battles/replays/{id}`) | 현재 공격 장수 본인 또는 공격/방어 세력 일치면 상세 replay를 반환한다. | 사건의 replay ref를 볼 수 있어도 상세 replay 자체의 현재 권한을 다시 검사. #343 봉인 배치·전력 공개 조건과 현 endpoint 권한을 교차 테스트한다. |

## 새 응답 권한

읽기 진입점은 JWT의 `userId`에서 `GeneralResolver.resolve`로 **현재 소유 장수**를 확정한다. 파라미터로 전달된 장수 ID나 옛 로그의 `general_id`만 믿지 않는다. 처리 월드 범위는 `GameApiProcessWorld`/가드의 최신 세계 형식 판정 뒤 `world_id`로 모든 SQL에 적용한다. 비로그인 요청은 공개 `/api/world-events`만 허용한다.

| audience | DB 후보 조건 | 반환 직전 재검사 |
| --- | --- | --- |
| SELF | `audience_general_id=actor.id` | resolver가 현재 그 장수의 소유 계정을 확인. 같은 세력 수뇌라는 이유로 개인 행을 보여 주지 않음. |
| RETINUE | `actor.id = ANY(recipient_general_ids)`와 사건 당시 `audience_general_id=ownerId` | 사건 당시 허용 수신자 snapshot은 봉인한다. 관계가 바뀌어도 당시 수신자를 새로 추가하지 않으며, 반환 시 **현재 비밀 권한**을 다시 검사한다. 관계 종료만으로 과거 수신자를 자동 삭제하지 않는 것이 확정 정책이다. 주인 본인과 휘하 당사자의 최소 권한 문턱은 writer/읽기 구현에서 kind별로 고정해야 한다. |
| NATION | `audience_nation_id=actor.nationId`, `nationId>0` | 현재 세력·`SecretPermissionReader`의 허용 등급을 kind별로 재검사. 다른 세력에 옮긴 장수에게 이전 세력 수입/전술 정보를 남기지 않음. |
| COURT | `actor.id = ANY(recipient_general_ids)`, 세력 ID 일치 | 사건 당시 발신/수신자 봉인과 현재 세력·관직/비밀 권한을 재검사. 일반 수신자가 자기 발령을 읽을 최소 권한과 사관/수뇌 열람 범위는 별도 규칙으로 명시해야 한다. |
| PUBLIC | `audience=PUBLIC AND publication_state=PUBLISHED AND section=WORLD` | 중앙 `EventKind.publicKinds`와 refs/facts allowlist를 다시 검사. `publish_after_*`는 v1에서 모두 NULL이며 자동 승격 없음. |

`SecretPermissionReader`는 관직·사관년도·penalty를 반영한 현재 권한 값을 반환한다. 이 값을 과거 사건 당시의 관직으로 대신하지 않는다. `RetainerReadRepository`는 현재 관계를 읽을 수 있지만 RETINUE 과거 수신자 판정의 원천은 사건에 봉인한 snapshot이다. 현재 관계를 새 수신자 허용 근거로 쓰지 않는다. 접근 거절·손상 refs는 raw JSON이나 비밀 ID를 DTO로 내보내지 않으며, 승인된 안전 투영이 없으면 사건 전체를 숨긴다.

## 페이지·인덱스·성능

`GET /api/events?section=&before=&limit=`은 인증 필요, WORLD 외 네 분류만 받는다. `GET /api/world-events?before=&limit=`은 공개 WORLD만 받는다. 기본 30, 범위 1..50, `limit+1`로 다음 커서를 계산한다. 역순 `(occurred_year,occurred_month,occurred_phase,occurred_ordinal,id)`의 **모든** 컬럼에 같은 `<` 커서 튜플을 적용한다. 커서는 버전·월드·분류·튜플을 담은 불투명 base64url 값으로 만들고 길이/타입/월드/분류를 검사한다. 임의 변조가 있더라도 SQL의 audience/월드 조건은 절대 제거되지 않는다. 같은 순 50건 초과, 빈 페이지, 월/연 경계, 재실행 중복 0건을 검증한다.

V65의 공개/SELF/NATION/RETINUE·COURT 부분 인덱스는 모두 `world_id` 선두다. 수신자 GIN은 세계 범위 불변식 때문에 넣지 않았고, 공유 audience는 월드별 최근 후보에서 수신자 배열을 필터한다. reader PR에서 실제 쿼리와 대표 월드 분포로 `EXPLAIN (ANALYZE, BUFFERS)`·p95를 기록해 부족하면 world-leading 추가 인덱스/수신자 조인 테이블을 **전진 마이그레이션**으로 검토한다. 권한 없는 후보를 먼저 페이지로 잘라 빈/짧은 페이지가 되지 않도록, SQL 후보 선택 단계와 반환 단계의 두 권한 검사를 일치시킨다.

## 사건 당시 지명·replay

현재 `ActiveWorldArtifactResolver.cityNames()`는 **현재** 지도 판의 이름만 읽는다. 행정 오버레이 스펙의 `baseArtifactId`, 적용 delta 순서/해시, `projectionSha256`을 사건 당시와 결속하는 조회 경로는 아직 없다. 190 許縣 / 196 허도 표시 / 220 許縣 / 221 許昌縣 및 다른 월드 투영 캐시 격리 테스트는 오버레이 저장 핀 계약이 정해진 뒤 작성해야 한다. 저장 사건에는 표시 이름을 넣지 않고, 과거 핀이 없거나 검증 실패하면 일반 장소 표기 또는 사건 숨김과 진단을 택한다. 현재 이름으로 과거 사건을 다시 쓰지 않는다.

`REPLAY` ref가 있는 전장 사건도 상세 replay를 내장하지 않는다. `BattlePlanController.replay`의 현재 nation/attacker 검사는 별도 경계이며, #343의 적 병력·봉인 계획 fixture로 사건 피드와 replay endpoint를 함께 검증한다. 링크가 공개 사건에 있더라도 replay를 자동 공개하지 않는다.

## 구현 파일 후보와 시험 관문

- 새 파일 후보: `read/EventFeedReadRepository.kt`(JDBC world+audience+cursor), `read/EventFeedReader.kt`(현 소유·관계·비밀 권한과 DTO 투영), `controller/EventFeedController.kt`, `dto/GameEventDto.kt`, 읽기 전용 권한/커서 테스트.
- 기존 파일 후보: `BattlePlanController.kt` replay 링크 재검증 테스트. `WorldStateReadRepository.kt`는 세계 형식 가드 소유이므로 #956 main 병합 전 편집하지 않는다. `WorldLogController`, `LastTurnsController`, `GeneralLogController`, `HistoryController`, `NationLogReadRepository`, `LogFeedReadRepository`의 구 text 경로 정리는 새 피드/화면 검증 뒤 별도 제거 PR에서 한다.
- 적색 fixture: 타 세력 월 수입·정찰·군단 병력/목적지·발령·조우 봉인 정보를 PUBLIC/타국/비로그인/연감/replay에서 0건, 소유자·정해진 수신자에는 예상 건수. 관계 종료 전/후 RETINUE에서 당시 수신자 snapshot은 유지하되 현재 비밀 권한 변화만 반영, 세력 이동 전/후 NATION, 수신자 탈퇴 COURT, 공개 점령 1건, 손상 JSON·알 수 없는 kind, 세계 간 같은 ID 충돌을 포함한다.
