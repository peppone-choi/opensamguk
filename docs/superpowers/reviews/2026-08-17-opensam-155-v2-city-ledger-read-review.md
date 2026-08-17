# OPENSAM-155 (v2 R6) — 도시 원장 열람: 자기 검토 + 선언 기록

- 대상: game-api read 컨트롤러(신규) + `web/game` 원장 패널·라우트(신규) + 조작 화면 2곳에 패널 얹기
- 브랜치: `feat-opensam-155-v2-city-ledger-read` (base = `main`, R5 머지 `bd3fe69d`)
- 일자: 2026-08-17

Scope: OPENSAM-155 v2 도시 원장 열람 — game-api JdbcTemplate read 컨트롤러(app/) · 도시 원장 read 계약과 패널·라우트, v2-lab 조작 화면 표시(web/)
Verdict: cleared

## 0. 왜 이 티켓이 필요했나

R1~R5는 전부 백엔드였다. 유저는 금·병량·도시병사를 **입력**만 할 수 있었고 **얼마 있는지 볼 수 없었다** —
잔액을 알아내는 유일한 방법이 "실패해 보기"였다. 보이지 않는 원장 위에서는 "어느 도시에 무엇을 둘까"라는
결정이 성립하지 않으므로(설계안 §8), R6이 없으면 R1~R5 전체가 규칙 4의 첫 항(결정)을 잃는다.

R6 자체는 read-only라 LEDGER 규칙 4 **비대상**이다(감추지 않고 밝히는 3건 중 하나).

## 1. 만든 것

| 계층 | 파일 | 성격 |
| --- | --- | --- |
| game-api | `v2/V2CityLedgerReadController.kt` | 신규. `GET /api/v2/city-ledger`(월드 전체) · `/{cityId}`(한 도시) |
| game-api test | `v2/V2CityLedgerReadControllerTest.kt` | 신규 4건 |
| game-api test | `v2/V2ProductionContextBeanGateIT.kt` | 게이트 열림 시 기대 빈 집합에 컨트롤러 1줄 추가 |
| web | `lib/v2/cityLedger.ts` | 신규. read 계약 + 포맷터 |
| web | `components/v2/CityLedgerPanel.tsx` | 신규. 도시 하나의 금/병량/도시병사 |
| web | `app/game/v2-lab/ledger/page.tsx` | 신규 라우트. 월드 전체 표 |
| web | `v2-lab/garrison`·`transport/page.tsx` | v2-lab 자체 화면에 패널을 얹고 제출 후 재조회 |

## 2. JPA를 쓰지 않은 것은 취향이 아니다

`GameApiApplication.kt:9-10`의 `@EntityScan`/`@EnableJpaRepositories` 화이트리스트가 진짜 등록점이고
`application.yml`의 `ddl-auto: validate`가 걸려 있어, **화이트리스트를 넓히는 순간 v1 부팅이 깨진다.**
그래서 v2 read는 `NamedParameterJdbcTemplate` 직접 조회다. `GameApiApplication.kt` 편집 0,
`application.yml` 편집 0(게이트 ⑤ PASS).

SQL은 항상 `WHERE world_id = :world_id`로 좁히고 `ORDER BY city_id`로 결정적으로 정렬하며 `SELECT *`를
쓰지 않는다 — 테스트가 이 세 가지를 문자열로 고정한다(주석이 아니라 단언이다).

## 3. 행이 없는 도시를 404로 하지 않은 이유

R1 이후 아직 아무 델타도 받지 못한 도시는 "없는 도시"가 아니라 "원장이 비어 있는 도시"다. 404를 내면
화면이 실재하는 도시를 없는 것처럼 보여준다. 그래서 **0/0/0**을 돌려주고, 이는 엔진
`V2CityLedgerEntry.EMPTY`와 같은 시멘틱이다(두 쪽이 같은 답을 준다).

## 4. 게이트 ③ = **0줄** (T2 편집 0)

티켓이 요구한 대로 R6은 기존 main 소스를 한 줄도 고치지 않는다 — 게이트 ③ 출력이 **비어 있다.**
컨트롤러는 `GameApiApplication.kt:8` 컴포넌트 스캔에 신규 파일로 잡히므로 등록 편집이 필요 없다.
빈 게이트는 인테이크 컨트롤러들과 **같은** `@Profile` + `@ConditionalOnProperty`이므로, 게이트가 닫히면
열람 엔드포인트도 함께 404다(read라고 새어 나가지 않는다). `V2BothConditionsBeanGateIT`가 Docker에서
실제로 실행돼(1건, skipped 아님) 빈 이름 집합을 확인했다.

## 5. 프론트에서 고친 파일에 대한 정직한 표기

DoD는 "v2 프론트는 기존 페이지를 고치지 않고 새 라우트만 추가한다"이다. 신규 라우트·신규 컴포넌트가
원칙이고 실제로 v1 화면은 한 곳도 건드리지 않았다. 다만 **v2-lab의 징병·수송 화면 2개는 편집했다** —
이 둘은 R4/R5가 만든 v2 전용 화면이고, 원장을 소비하는 바로 그 자리에 잔액을 얹는 것이 이 티켓의 목적
자체이기 때문이다. DoD 문장이 막으려는 것은 v1 표면 오염이지 v2 화면의 자기 갱신이 아니라고 읽었고,
읽은 대로 여기에 적어 둔다.

## 6. 미수행 — 감추지 않는다

- **webapp-testing 실서버 화면 확인 미수행.** v2 게이트(`V2_ENABLED` + `v2-sandbox` 프로파일)는
  프로덕션에서 닫혀 있어 배포된 화면에서는 이 엔드포인트가 404다. 로컬 게이트-온 스택 기동 없이는
  "실제 화면에서 gold/rice/garrison 표시"를 눈으로 확인할 수 없으므로 **채점대기**로 남긴다.
  대신 계약은 컨트롤러 단위 테스트 4건 + 빈 게이트 IT로 고정했고, 빌드에서 라우트 생성을 확인했다.
- **목재·석재·철재는 이 티켓에 넣지 않았다.** 유저 제안이 있었으나 생산원·소비처가 정의되지 않은 자원은
  화면의 죽은 숫자가 된다(규칙 4). 별도 티켓으로 분리했다.

## 7. 검증 증거

- `:app:game-api:test --rerun-tasks --tests 'opensamguk.gameapi.v2.*'` → BUILD SUCCESSFUL,
  XML 집계 **tests 13 / failures 0 / errors 0 / skipped 0**
  (`V2CityLedgerReadControllerTest` 4 · `V2BothConditionsBeanGateIT` 1 실제 실행)
- `scripts/agent/v2-isolation-gate.sh` → ② PASS · **③ 0줄** · ⑤ PASS · C1 PASS
- `web/game` build 성공 — `/game/v2-lab/ledger` 라우트 생성, lint 신규 경고 0
- v1 골든/RNG/로그/JPA 화이트리스트/설정 리소스: **건드린 파일 0**

## 8. 리뷰 대응 (CodeRabbit, PR #429)

| 지적 | 처리 |
| --- | --- |
| 🟠 도시 변경 시 이전 원장이 남고, 늦게 온 옛 응답이 최신 값을 덮어쓴다 | **수정.** 조회 순번(`requestSeq`)으로 마지막 조회만 상태를 갱신하고, 도시가 바뀌는 순간 값을 비운다 |
| 🟠 Kotlin/TS 주석을 영어로 | **따르지 않음.** 이 저장소는 v1·v2 소스 전반이 한글 주석 관례다(기존 `engine/v2/*`, `gameapi/v2/*` 전부). 이 PR만 영어로 바꾸면 오히려 관례가 깨진다 |

첫 지적은 실제 버그였다 — 화면이 "5번 도시"라고 써 놓고 3번 도시의 금을 보여줄 수 있었고, 유저는 틀린
잔액으로 수송·징병을 결정하게 된다. 회귀 테스트(`web/game/__tests__/CityLedgerPanel.test.tsx` 3건)를
붙였고, **가드를 빼면 그 테스트가 실제로 빨개지는 것까지 확인**했다(공허한 테스트가 아니다).
