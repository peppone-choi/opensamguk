# OPENSAM-152 (v2 R3) — 도시병사 감소·공백지화: 자기 검토 + 선언 기록

- 대상: `app/game-engine/.../v2/V2CityGarrisonAttrition.kt`(신규) + 경계 수정 3파일
- 브랜치: `feat-opensam-152-v2-garrison-attrition` (base = `feat-opensam-151-v2-city-income`)
- 일자: 2026-08-17

Scope: OPENSAM-152 v2 도시병사 감소·공백지화 leaf·등록·디스패치 배선(app/) + v2 시나리오 4행 append(infra/)
Verdict: cleared

## 0. 선행 조건에서 벗어난 점 — 먼저 적는다

티켓 DoD에 **"R2 머지 이후에 착수"** 가 있고, R2(PR #424)는 **아직 머지되지 않았다**. main 머지는
사람 승인이 필요한 행위라 이 세션 권한 밖이다. 그래서 **머지 대신 스택**으로 갔다 — 이 브랜치의 base 는
`origin/main` 이 아니라 R2 브랜치이고, PR 도 R2 브랜치를 향한다(CLAUDE.md "PR은 stacked, base = parent").

DoD 문구가 요구한 실질은 *생산자→소비자 순차*(§9.2 개정 4차)이고, 스택은 그 순서를 그대로 지킨다 —
R2의 시나리오 12행·`WorldActionContext` v2 컨텍스트·`V2WorldActions` 체인이 전부 부모 커밋에 이미 있고
R3는 그 위에만 얹는다. **다만 "머지 이후"라는 문자 그대로의 조건은 충족되지 않았다.** R2가 리뷰에서
바뀌면 이 브랜치는 리베이스가 필요하다.

## 1. 무엇을 만들었나

v1 `RaiseDisaster`가 1·4·7·10월에 `city.state`에 써 둔 재난 코드를 **읽기만** 해서, 그 도시의
v2 원장 `garrison`을 줄이고 0이 되면 같은 반복 안에서 `city.nationId = 0`으로 공백지화한다.
RNG draw 0 — `attritionLoss`·`v2CityGarrisonAttrition`·`V2CityGarrisonAttritionAction` 어디에도
`RandUtil` 파라미터가 없다. **draw 0은 테스트가 아니라 타입이 보장한다.**

## 2. 선언된 divergence — 포팅한 값이 아니다

1. **트리거가 "도적"이 아니라 "재난"이다.** devsam PHP에 도적 침입 월드 이벤트가 없다
   (`che_도적`은 국가 성향 `ActionNationType.kt:107-108`). 묘섭식 독립 도적 이벤트는 오픈 후(§2.4 (a) 경로).
   부수 효과로 `RaiseDisaster`가 `startYear + 3` 이전을 통째로 건너뛰므로(`RaiseDisaster.kt:26,151`)
   **개막 3년간 공백지화가 원천적으로 0**이다 — §2.6이 원한 유예와 같다.
2. **감소 기본율 12.5%** (`ATTRITION_BASE_RATE`) — 묘섭 미명시. v1 재난이 스탯을
   `affectRatio = 0.8 + fit*0.15`(`RaiseDisaster.kt:199`)로 깎는 **손실 5~20% 구간의 중앙값**을 썼다.
   v2 leaf는 §2.4가 정한 두 인자만 받으므로 `secu/secu_max`를 읽지 않아 구간이 아닌 한 점이다.
3. **감소 하한 100명** (`ATTRITION_MIN_LOSS`) — 묘섭 미명시. **정률만으로는 `garrison`이 0에
   도달하지 못해 공백지화가 영영 일어나지 않는다.** 묘섭 원문의 "도시 병사가 **일정수** 감소"도 정량을
   가리킨다(§2.4 인용). 하한을 장수 스케일 **안쪽**에 둬서 장수가 적으면 하한도 함께 준다.
4. **300명↔1/6 사이 보간은 선형** — 두 끝값(기준 300명·최저 1/6)은 묘섭 원문 값
   (`help__start__other__etcetera.md:64`)이고, **곡선만 오픈삼국 결정**이다(§2.6이 UNKNOWN으로 남긴 자리).

## 3. divergence가 아닌 것

- `BAD_STATE_CODES = {3,4,5,6,7,8,9}` — 지어낸 집합이 아니라 `RaiseDisaster.kt:104-127`
  `DISASTER_TEXT`가 실제로 쓰는 stateCode 전수다. `BOOMING_TEXT`(`:130-133`)의 호황 2·풍작 1과 0은 제외.
- 월 게이트 `{1,4,7,10}` — `EventStore.kt:171,180,190,197`의 `a("RaiseDisaster")` 4행.
- 순회 `city_id ASC`, 공백지화는 감소 **직후 같은 반복 안**(독립 스캔 아님) — §2.4 의사코드 그대로.
- 공백지화는 `city.nationId = 0` **한 줄만** 쓴다. `ConquerCity` 등 v1 정복 경로는 **부르지 않는다** —
  부르면 그 경로의 로그·draw를 끌어들인다.
- v2 로그는 재난 **종류를 표시하지 않는다.** stateCode는 이름과 1:1이 아니라(3에 셋, 5에 넷, 8에 둘)
  정수만으로 복원이 불가능하기 때문이다(§2.4). 대신 before/after를 싣는다 — 의도된 한계.

## 4. 알려진 천장 — 숨기지 않는다

`v2_city_ledger.garrison`의 DB 기본값은 0이고(`V901__v2_city_ledger.sql:14`), 이를 채우는 유일한 경로는
**아직 없는 R4(병사보충)** 다. 묘섭 원문이 *"도시병사가 **없거나**, 너무 적은 상태에서"* 를 공백지 조건으로
못박으므로(§2.4 인용) 이 leaf는 `before == 0`도 공백지화 대상으로 삼는다. 결과적으로 **R4가 붙기 전에는
개막 3년이 지난 시점부터 재난을 맞은 도시가 곧바로 공백지가 된다.**

이것을 "구현이 이상하다"가 아니라 **설계가 이미 알고 있는 마감**으로 기록한다 — §2.6의
`v2 NPC 도시 정책`은 오픈 후 티켓 중 **유일하게 "게임 내 3년 이내"라는 시한**을 가진 항목이고, 그 시한의
실체가 정확히 이 상태다. 조건을 임의로 `before > 0`으로 좁혀 숨길 수도 있었지만 그러면 묘섭 규정과
어긋나고 마감이 보이지 않게 된다.

## 5. 격리 게이트 ③ — 경계 수정

| 파일 | 수정 | 티켓 T2 표 |
| --- | --- | --- |
| `app/game-engine/.../world/WorldActionContext.kt` | `V2CityGarrisonAttritionContext` 구현 4메서드 + import 3 | 표의 **7번 행 그대로** |
| `app/game-engine/.../v2/V2WorldActions.kt` | 등록 체인에 leaf 1개 추가 | **R2 산출물**(v2 소유 파일) |
| `infra/src/main/resources/scenario/scenario_9200.json` | 1·4·7·10월 행에 leaf append | **R2 산출물**(v2 전용 데이터) |

신규 파일 2(leaf, 테스트) + 테스트 수정 1(`V2ScenarioSeedTest`)은 게이트 ③의 `--diff-filter=MD` 대상이
아니다. 티켓이 "T2 diff에 새 파일 이름이 나오면 정상, 표 밖 파일이 **수정·삭제**로 나오면 위반"이라고
규정한 그대로다.

가드 제약 준수: v2 타입명에 `Repository`/`Reader` 없음 · `JdbcTemplate`/`NamedParameterJdbcTemplate`/
`Connection`/`DataSource` 선언 없음 · 기존 네 수신자(`auctionRepository`·`auctionBidRepository`·
`archiveHistoryReader`·`statisticSnapshotReader`) 호출 0. 새 bean 0(R2의 `v2CityLedgerStore` 재사용).

## 6. fail-closed (무음 no-op 금지)

컨텍스트가 `V2CityGarrisonAttritionContext`가 아니면 leaf가 **죽는다.** 무음 no-op이면 "재난이 나도
병사가 안 줄어드는 월드"가 테스트 그린으로 보인다 — R2가 같은 이유로 같은 선택을 했다.

## 7. 시드 검증

`V2ScenarioSeedTest`의 전사 대조는 이제 변형 **둘**을 적용한 기대값과 겨룬다 —
(1) R2의 `ProcessIncome → V2ProcessCityIncome` 치환, (2) R3의 `RaiseDisaster` **직후** leaf 삽입.
기본 이벤트가 바뀌면 여전히 여기서 먼저 깨진다.

추가로 못박은 것: 네 행 전부에서 leaf의 **바로 앞이 `RaiseDisaster`**, 전체 개수 정확히 4개, 등록기가
그 이름을 안다. 순서를 못박는 이유는 실패가 조용하기 때문이다 — leaf가 `RaiseDisaster` **앞**에 오면
아직 이번 달 재난 코드가 안 써진 `city.state`(전월 리셋값 0)를 읽어 감소가 통째로 사라진다.

## 7-1. 검증 결과 — 플레이키 1건을 숨기지 않는다

`:common :logic :infra :app:game-engine :app:game-api --rerun-tasks` 1회차에서 game-api의
`ReadConsistencyBarrierIT > primary minVersion waits until concurrent version commit becomes visible`가
`expected:<200> but was:<409>`(VERSION_NOT_VISIBLE)로 떨어졌다. 이 티켓은 game-api를 한 줄도 고치지
않았고 read barrier와 접점이 없다. 단독 재실행 green, game-api 전체 재실행 green(510/0/0)으로 **타이밍
플레이키**로 판정한다. "무관하니까 무시"가 아니라 재실행 두 번으로 확인한 결과를 적는다.

최종 XML: common 232 / logic 3230 / infra 239 / game-engine 877(skipped 1, Docker IT) / game-api 510 —
failures 0, errors 0.

## 8. 남은 것 (이 티켓 밖)

- 실제 9200 월드를 돌려 원장이 이렇게 변한다는 end-to-end 리플레이는 여전히 없다(R2와 동일한 잔여).
- `attritionLoss`의 세 상수(12.5% · 100명 · 선형)는 밸런싱 값이라 실플레이 데이터가 생기면 재조정 대상이다.
  묘섭 원문 값(300·1/6)과 오픈삼국 결정값을 상수 이름·KDoc에서 분리해 둔 이유가 그것이다.
