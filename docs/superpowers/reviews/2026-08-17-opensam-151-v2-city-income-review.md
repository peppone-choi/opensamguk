# OPENSAM-151 (v2 R2) — 도시 단위 수입 정산: 자기 검토 + 선언 기록

- 대상: `app/game-engine/.../v2/V2ProcessCityIncome.kt`(신규), `V2WorldActions.kt`(신규),
  `infra/src/main/resources/scenario/scenario_9200.json`(신규) + 경계 수정 5파일
- 브랜치: `feat-opensam-151-v2-city-income`
- 일자: 2026-08-17

Scope: OPENSAM-151 v2 도시 수입 leaf·등록기·디스패치 배선(app/) + v2 시나리오 시드(infra/)
Verdict: cleared

증거: `:common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test --rerun-tasks`
(아래 §5). `scripts/agent/v2-isolation-gate.sh` ② PASS · ⑤ PASS · C1 PASS, ③ 목록은 §4와 일치.

## 1. 무엇을 만들었나

v1 `ProcessIncome`은 국가 단위로 수입을 걷어 `nation.gold`/`nation.rice`에 넣는다. v2는 금·쌀·수비병을
**도시가 소유**하므로(`docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md` §2.2) 같은
계산을 도시 단위로 재조립한 leaf `V2ProcessCityIncome(gold|rice)`을 만들었다. RNG draw는 0개다.

## 2. 선언된 divergence 3건 — 포팅한 값이 아니다

정치·매력 5스탯과 같은 성격의 **오픈삼국 독자 결정**이다. PHP 오라클이 존재하지 않으므로 골든으로
고정할 수 없고, 그래서 코드 주석·커밋·이 문서 세 곳에 같은 문구로 남긴다.

1. **도시별 `base` = 0 (금·쌀 둘 다).** v1의 `GameConst.baserice`(2000)는 *국가* 하한선이다. 도시마다
   적용하면 도시 수만큼 곱해진 하한이 되어 국가 총 하한이 도시 수에 비례해 커진다. v2는 국고
   (`nation.gold`)가 별도 계정이므로 도시 원장에는 하한을 두지 않는다.
2. **봉록 귀속 도시 = 장수의 소속 도시.** 그 도시가 이 국가 도시가 아니면(타국 도시에 있는 장수) 수도로
   되돌리고, 국가가 도시를 하나도 안 가지면 지급하지 않는다(지급할 원장이 없다).
3. **잔차(residual) 규칙 없음.** 도시별 3분기 정산이 각자 끝나고, 못 준 봉록을 다른 도시가 대신 메우지
   않는다. v1의 국가 단일 계정에서는 존재할 수 없던 상황이라 대응하는 PHP 동작이 없다.

`prev_income_{gold,rice}`는 설계 §2.3 판정 (a)대로 **국가 단위로 유지**하고 값은 그 국가 도시들의 수입
합이다. 소비처(랭킹·내정 화면)가 국가 단위라 도시별로 쪼개면 소비처가 전부 깨진다.

## 3. divergence가 아닌 것 — v1과 같아야 하는 것

- 도시별 수입은 v1과 **같은 함수**를 부른다(`calcCityGoldIncome` / `calcCityRiceIncome` +
  `calcCityWallRiceIncome`). 3분기 정산 분기 형태·`getOutcome`·`getBill(dedication) * ratio`·
  로그 토큰(`HistoryTokens`)도 v1 그대로다.
- 세율 스케일 `* (taxRate/20)`을 도시별로 걸되 **Double을 유지**한다. 도시별로 반올림하면 국가 합계가
  v1에서 실수 단위로 벌어진다. 반올림 지점은 원장 기록 1회뿐이다.
- **다만 비트 동일은 주장하지 않는다.** v1은 `(Σ cityInt_i) * s`로 한 번 곱하고 v2는 `Σ (cityInt_i * s)`로
  나눠 곱한다. 실수 산술로는 같지만 IEEE754에서는 마지막 자리(ulp) 차이가 날 수 있다. 정수 원장에
  들어갈 때 어차피 반올림되므로 실질 차이는 없고, 테스트도 그래서 상대오차(1e-12)로 대조한다.
  "같다"고 단언하고 지나갔다면 그게 사실이 아닌 주장이 됐을 자리다.

## 4. 격리 게이트 ③ — 경계 수정 5파일 (사전선언 4 + 1)

| 파일 | 수정 |
| --- | --- |
| `app/game-engine/.../config/EngineEventConfig.kt` | 팩토리 체인에 `V2WorldActions.register` 1줄 + import |
| `app/game-engine/.../world/WorldActionContext.kt` | `V2CityIncomeContext` 구현 + 생성자 nullable 원장 파라미터 |
| `app/game-engine/.../world/WorldEventContextFactory.kt` | nullable 원장 파라미터 통과 |
| `app/game-engine/.../config/DaemonLoopConfig.kt` | `ObjectProvider<V2CityLedgerStore>` 주입 |
| `app/game-engine/.../v2/V2SandboxConfiguration.kt` | **티켓 T2 표에 없던 5번째** — `v2CityLedgerStore` 빈 |

5번째 파일은 v2 소유 파일이고, 그 파일 자신의 주석이 "Future concrete v2 beans, **including ledger
stores** … belong here as `@Bean` methods, and each bean name must be added to
`APPROVED_V2_BEAN_NAMES`"라고 자리를 미리 규정해 둔 곳이다. 사전선언을 넘긴 사실 자체는 숨기지 않고
여기와 Jira 코멘트에 남긴다.

게이트 ② 제외 대상(v2 테스트) 수정 3건:
- `V2ProductionContextBeanGateIT` — 허용 목록에 `v2CityLedgerStore` 추가 + 프로덕션 0개 타입 단언 추가.
  격리 증명을 **좁히는 게 아니라 넓힌다**.
- `V2SandboxConfigurationTest` / `V2ContentCatalogBeanTest` — 두 슬라이스가 DB 없이 조건 평가만
  재는데 새 빈이 `NamedParameterJdbcTemplate`을 요구해 컨텍스트가 안 떴다. DataSource 없는 껍데기를
  주입해 띄웠고 **기존 단언은 하나도 건드리지 않았다**.

`@ConditionalOnBean(NamedParameterJdbcTemplate)`으로 조건부 등록하는 길은 일부러 피했다 — 사용자
`@Configuration`은 auto-configuration보다 **먼저** 평가되므로 그 조건은 프로덕션에서 조용히 false가
되어 빈이 영영 안 생겼을 것이다(무음 실패). 대신 빈은 무조건 등록하고 소비 측을 `ObjectProvider`로
받아 게이트 밖에서는 null이 되게 했다.

## 5. fail-closed 설계 (무음 no-op 금지)

v2 샌드박스 게이트가 꺼진 v1 프로덕션에는 `V2CityLedgerStore` 빈이 없다. 그 상태에서 (시나리오 실수로)
`V2ProcessCityIncome`이 디스패치되면 `V2ProcessCityIncomeAction`이 **죽는다**. 무음 no-op이면
"수입이 통째로 사라진 월드"가 테스트 그린으로 보인다 — 이 프로젝트가 반복해서 데인 실패 모드다.

## 6. 시드 검증 (m-new-1)

`infra/src/main/resources/scenario/scenario_9200.json` — `ignoreDefaultEvents: true` + 12행 전사.
`V2ScenarioSeedTest`가 세 가지를 못박는다:
- (a) 12행이 `EventStore.defaultWireRows()`의 1:1 전사이고 치환 외에는 target·priority·조건·액션
  순서까지 완전히 같다. 기본 이벤트가 나중에 바뀌면 이 테스트가 먼저 깨진다(전사본이 조용히 낡지 않는다).
- (b) `ProcessIncome` **0건** — 하나라도 남으면 v2 월드가 수입을 두 번 걷는다. `ProcessWarIncome`은
  별개 leaf라 1건 유지가 정상임도 같이 고정.
- (c) `V2ProcessCityIncome` 정확히 2건 — 1월 gold / 7월 rice.
- 추가: 등록기가 시나리오가 부르는 이름을 실제로 안다(이름 어긋나면 디스패치에서 죽는 것을 사전 차단).

## 7. 남은 것 (이 티켓 밖, 숨기지 않음)

- **실제 v2 월드 end-to-end 리플레이는 아직 없다.** 이 티켓이 증명한 것은 순수 정산 계약 + 배선 + 시드
  구성이고, "9200 월드를 실제로 한 해 돌려 원장이 이렇게 된다"는 회귀는 R3 이후 작업이다.
- `scenario_9200.json`의 시나리오 코드 9200은 기존 코드 대역(0~2 / 900번대 / 1010~1120 / 3190 test)과
  겹치지 않게 고른 것이고, 문서화된 대역 규약은 없다.
- 도시 수입 표시줄은 v1 토큰(`이번 수입은 금 N입니다.`)을 그대로 쓰되 값이 **그 도시의** 수입이다.
  v2 전용 문안이 필요해지면 그때 토큰을 새로 판다 — 지금 없는 소비처를 위해 문안을 만들지 않았다.
