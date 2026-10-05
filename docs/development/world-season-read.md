# 계절 · 통행 · 계절 사건 HTTP 조회

`GET /api/world/season?generalId=<actor>`는 지금 계절과 한 해 안의 순(0–35)을 낸다. 통행과 계절 사건은 생산자가 생길 때 같은 경로에서 채운다. D124에 따라 CEO가 K8에 배정했고, 모양은 C5가 받아들인 K8 소비 답(계약판 「K8 · K3 → C5 소비 답」 K8-08)을 따른다. 구현은 `WorldSeasonController → WorldSeasonQuery → WorldSeasonReader → WorldSeasonProjection`이다.

## 인증과 원천

- 인증을 먼저 본다.
  - 검증된 access JWT가 없으면 HTTP401 `AUTH_REQUIRED`다. generalId가 이상해도 401이 먼저다.
  - generalId가 없거나, 빈 값이거나, 10진 숫자만이 아니거나, Int 범위 밖이거나, 0 이하면 HTTP400 `INVALID_GENERAL_ID`다.
  - `GeneralResolver`가 해석한 살아 있는 본인 장수가 아니면 HTTP403 `FORBIDDEN`이다. 쿼리 userId나 ADMIN 역할로 대신하지 않는다.
  - 오류 본문은 `{error:{code,message}}`이고, 모든 응답이 `Cache-Control: no-store`다.
  - 이 경계는 D124의 다른 GET(K3 셋)과 같은 규칙이다.
- 처리 월드(`world_state`)의 연 · 월 · 순과 확정 달력을 REPEATABLE_READ 읽기 트랜잭션에서 읽는다. actor 월드가 처리 월드와 다르면 UNAVAILABLE이다.
- 달력은 `data/curated/han/world-event-values.json`의 calendar 줄이다. 봄 3 · 여름 6 · 가을 9 · 겨울 12월 시작이고, 모두 CONFIRMED다. 읽기는 logic `SeasonalCatalog.parseCalendar`가 한다.
  - 실행 classpath `season/world-event-values.json`에서 읽는다. 빌드가 이 파일을 싣지 않으면 NOT_SEEDED다.
  - `app/game-api/build.gradle.kts` processResources가 이 파일을 `season/`으로 싣는다. 이 줄은 C5 범위표 12파일 밖의 공통 1줄이고, CEO가 2026-10-06에 승인했다. 그 전에는 game-api 실행 classpath에 달력 원천이 없었다.

## 응답 계약

| 필드 | 원천과 의미 |
| --- | --- |
| `status` | `READY / NOT_SEEDED / UNAVAILABLE`. 달력이 없으면 NOT_SEEDED, 월드 · 날짜 · 달력이 깨졌으면 UNAVAILABLE |
| `reason` | status가 READY가 아닐 때의 까닭. `WORLD_UNAVAILABLE · WORLD_DATE_INVALID · SEASON_CALENDAR_ABSENT · SEASON_CALENDAR_INVALID`. READY면 null |
| `now` | 실제 world_state 연 · 월 · 순 `{year,month,phase}`. 월드 · 날짜를 확인하지 못하면 명시 null |
| `season` | `SPRING / SUMMER / AUTUMN / WINTER`. READY에서만 값이고, 그 밖은 null |
| `phaseOfYear` | `(month-1)*3 + (phase-1)`, 0–35. READY에서만 값이고, 그 밖은 null |
| `passageStatus / closedEdges` | 지금은 늘 `UNAVAILABLE / null`. 계절로 길을 여닫는 생산자가 없다. `LandPassageState.seasonOpen`은 초기값 false를 아무도 쓰지 않는다. 빈 배열을 「다 열림」으로 내지 않는다 |
| `eventsStatus / events` | 지금은 `NOT_SEEDED / null`(월드를 읽지 못하면 `UNAVAILABLE / null`). `SeasonalEvents.decide`를 부르는 실행 경로가 없어서, 여기서 사건을 굴리거나 지난 피드를 다시 틀지 않는다 |

- 생산자가 생긴 뒤의 칸 모양(C5 수용)
  - `closedEdges[]`: `{edgeId, endpoints:[from,to], label|null}`. 실제 traversal edge id만 쓰고 geometry는 만들지 않는다. K2 지도 쪽 소비 확인은 아직 없다.
  - `events[]`: `{countyId, kind, effect:{trust,population,agriculture,displaced,passageClosed}}`. logic `SeasonalOccurrence` 그대로이고, actor 세력이 지금 다스리는 현만 싣는다.
- JSON은 null 칸도 키를 늘 싣는다(`JsonInclude.ALWAYS`).

## 검증 범위

- 고정 응답 `app/game-api/src/test/resources/world/season/`
  - ready: 200년 7월 하순, 여름, 20.
  - boundary: 12월 하순, 겨울, 35.
  - not-seeded: 달력 없음.
  - unavailable: 처리 월드 없음.
  - blocked: 403 본문.
- `WorldSeasonHttpTest`가 위 고정 응답을 실제 Spring · JWT · controller · query · reader 경로에서 비교한다. 그 밖에 다음을 본다.
  - 한 해 36순 전부의 순 · 계절. 기대 계절은 달력 경계에서 따로 적었다.
  - 401 · 400 · 403과 no-store.
  - 다른 월드와 불가능한 날짜.
  - 깨진 · 미확정 달력 8가지. JSON 트리로 calendar 줄만 고친다.
  - 빌드가 실은 classpath 사본이 저장소 파일과 같은지.
- 시험은 저장소 달력 파일을 직접 읽는다. 저장소는 대역이라 실제 JDBC 읽기 · 운영 classpath 실림 · 재기동 검증을 대신하지 않는다.
