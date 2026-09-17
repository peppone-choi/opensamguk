# 입력 6종 registry 계약

> 작성일: 2026-09-17
> 상태: **초안(제안).** 교차 비평·사용자 승인 전. 이슈 #779 / OPENSAM-259.
> 상위: [장수·휘하 캠페인 재설계](./2026-09-17-general-and-retinue-campaign-redesign.md) §4·§5·§12, ADR-LITE-057, ADR-LITE-049 개정(2026-09-17, **PR #799 — 아직 main 에 없다. 이 문서는 #799 머지를 전제로 한다**)
> 범위: 계약만 정한다. 구현·수치는 이 문서에 없다.

## 1. 왜 필요한가 — 현행 실측

| 현행 | 근거 | 새 게임에서의 문제 |
|---|---|---|
| 모르는 명령 코드는 휴식으로 떨어진다 | `CommandRegistry.resolve` 마지막 분기 `else -> RestAction` (`logic/.../actions/CommandRegistry.kt`) | 로드맵 「registry에 없는 입력은 휴식이나 성공으로 떨어지지 않고 명시적으로 실패」와 반대다 |
| 명령은 문자열 코드 → 클래스의 `when` 표 | 같은 파일, 문자열 분기 92개(`che_` 80 + 그 밖 12) + `else` | 입력 종류(행동·배치·방침·공사·계책·조정 결정)를 구분하는 축이 없다 |
| 새 결과 타입을 직렬화기 집합에 안 넣으면 턴 루프 전체가 멈춘다 | `TurnDaemonCommandResultSerializer` `else -> throw` 전례(2026-09-06 boardRead) | 입력이 늘수록 같은 사고가 난다 — 등록을 한 곳에서 강제해야 한다 |
| 제품 범위 정본이 알파 카탈로그 124행(기존 별칭 70) | `data/commands/public-alpha-command-catalog.json`, `PublicCommandCatalogIndex` | ADR-LITE-057 이 정본 지위를 거뒀다. 새 원장이 필요하다 |

## 2. 식별과 종류

```text
InputKind   = GENERAL_ACTION | PLACEMENT | POLICY | WORK | STRATAGEM | COURT_DECISION
inputId     = "<kind>.<name>"        예: action.enlist, placement.assign, policy.set,
                                        work.start, stratagem.play, court.dispatch
ruleProfile = SAMMO | HWIHA          월드마다 하나. 삼모 월드는 기존 CommandRegistry 를 그대로 쓴다
```

- 같은 라우트·같은 인테이크를 쓰되 **월드의 ruleProfile** 로 갈린다(ADR-LITE-049 개정: 기존 라우트를 바로 교체하되 pep 전환 전까지 기존 명령 입력 경로 유지).
- `SAMMO` 월드에서 `HWIHA` 입력을, `HWIHA` 월드에서 `che_*` 코드를 받으면 **명시적 거절**(`reason = WRONG_RULE_PROFILE`)이다. 휴식으로 떨어지지 않는다.
- **ruleProfile 의 자리(제안):** 시나리오 JSON 이 선언하고, 시드 때 `ScenarioImporter` 가 `world_state` 에 적고, 런타임(엔진·game-api)은 `world_state` 만 읽는다. 지도가 이미 이 길을 쓴다 — 시나리오 `map.mapName` → `ScenarioImporter.kt` 가 `world_state.meta["map"]`·`config` 에 기록. 값이 없으면 `SAMMO` 다(기존 월드·시나리오 무변경). 월드가 살아 있는 동안 바뀌지 않고, 바꾸는 길은 초기화(재시드)뿐이다 — pep 전환(재설계 §15.2)이 곧 이 재시드다.
- 기존 명령 70개의 대응은 재설계 §12 표가 정본이고, 원장 행마다 `replacesLegacy[]` 로 역참조를 단다.

## 3. 원장 행

```text
inputId, kind, layer(1|2|3), actor(GENERAL|LORD|RULER|OFFICE_HOLDER),
authorityRule, targetSchema, costSchema(전·곡·철·목재·말), timing, effectScope,
failureReasons[], resultType, replayContract, aiPolicyId, helpTopicId,
tutorialObjectiveId|N/A, replacesLegacy[], deliveryState
```

- (구현 PR #815 에서 추가한 어휘) `deliveryState` 맨 앞에 **`PLANNED`** 를 둔다 — 원장에 올랐지만 핸들러가 없는 입력이다. registry 는 이런 입력을 `NOT_DELIVERED` 로 거절한다. 거절 사유는 4종이다: `MALFORMED_INPUT_ID` · `WRONG_RULE_PROFILE` · `UNKNOWN_INPUT` · `NOT_DELIVERED`. 핸들러 유무는 `HANDLER_READY` 이상과 정확히 일치해야 하고, 어긋나면 registry 생성이 실패한다.
- `PLANNED` 뒤는 기존 파이프라인을 그대로 쓴다: `DOMAIN_READY → HANDLER_READY → UI_READY → AI_READY → HELP_READY → TUTORIAL_READY → REPLAY_READY → VERIFIED`(재기준선 §3 보존).
- 원장 파일: `data/commands/hwiha-input-catalog.json`(신규). 알파 카탈로그 파일과 `PublicCommandCatalogIndex` 는 `SAMMO` 월드용으로 남는다.

## 4. 시점(timing)

| kind | 예약 | 효력·실행 시점 | 근거 |
|---|---|---|---|
| GENERAL_ACTION | 명령 목록 12순 슬롯 | 그 장수의 턴 시각, 한 순 1개 | 재설계 §4·§5.1 |
| PLACEMENT · POLICY | 언제든 수정 | 해당 카드의 **다음 턴**부터. 12순 목록에 효력 시작 표식 | §4, ADR-049 개정 |
| WORK | 언제든 착수(중단은 이 문서의 새 제안) | 다음 **순 경계**부터 진척 | §4·§5.2 |
| STRATAGEM | 손패에서 선택 | 즉시 = 자기 턴 3단계 / 설치 = 비공개 저장 후 상대 턴에 발동 / 대응 = 방어 칸에 저장 후 피격 시 공개 | §5.1, §6.4 |
| COURT_DECISION | 결정권자의 턴 | 발령은 대상 장수에 보류 상태로 도착, 응답은 행동 소모 없음, 기한 뒤 수락 | §2.4 |

## 5. 실패 계약

- 모든 입력은 (a) 접수 시 사전검사, (b) 실행 직전 재검사를 거친다. 둘은 **같은 failureReasons 집합**을 쓴다(화면 사유 = API 사전검사 = 엔진 거절).
- 실행 시점에 조건이 안 맞으면 **비용 없이 무효 + 사유 기록**(재설계 §4).
- (이 문서의 새 제안 — 상위 설계에 없음) 무효 판정 전에 이미 수행된 이동·전투의 비용은 환불하지 않는다.
- registry 에 없는 inputId, 다른 ruleProfile 의 입력, 스키마 위반은 `ok=false` 결과로 끝난다. **어떤 경로도 휴식·성공으로 떨어지지 않는다.**
- 인테이크 202 는 성공이 아니다 — 기존 result-poll 규약(OPENSAM-13/135)을 그대로 쓴다.

## 6. 결과 wire

- 결과 타입은 kind 마다 하나의 봉투로 접는다: `InputResolved { inputId, kind, ok, reason?, effects[] }`. 입력이 늘어도 `TurnDaemonCommandResultSerializer` 분기는 늘지 않는다.
- 새 봉투 타입은 wire 왕복 테스트로 고정한다. 직렬화기에 등록되지 않은 타입이 턴 루프를 멈추는 전례를 구조로 막는다.

## 7. 결정론·저장

- 같은 시각의 장수 턴은 안정 ID 순, 시드는 (월드, 장수, 순)(재설계 §5.2).
- 설치 계책·대응 카드·보류 발령은 **비공개 상태**다. 읽기 API 는 시야·소유자 기준으로 투영하고(이슈 #785), 저장은 기존 `ChangeRecorder → JdbcFlushExecutor` 단일 경로만 쓴다.
- 새 읽기·쓰기 표는 `HotColdCatalog` 등록과 컨텍스트 IT 를 함께 넣는다(`HotColdWorldCatalogGuardTest` 가 등록 누락을 막는다).

## 8. 게이트(적색 프로브 필수)

1. 미등록 inputId → `ok=false`·`UNKNOWN_INPUT`. `else -> Rest` 로 되돌리면 빨개지는 테스트.
2. ruleProfile 교차 입력 거절.
3. 원장 ↔ 코드 등록 일치(원장에 있고 핸들러가 없거나 그 반대면 실패).
4. 사전검사·실행 거절 사유 집합 일치.
5. wire 왕복.
6. `SAMMO` 월드의 기존 골든·회귀는 바이트 불변.

## 9. 첫 구현 묶음(3단계 슬라이스)

`action.enlist`(출사) · `court.dispatch`(발령)와 응답 · `placement.assign` · `policy.set` · `work.start` · `stratagem.play`(즉시 1장·설치 1장·대응 1장) — 한 州 슬라이스의 통과 조건(출사 → 발령 → 장수 턴 → 순 경계)을 덮는 최소 집합이다.

## 10. 미결

- ruleProfile 을 `world_state.meta` 와 `config` 중 어느 쪽에 적을지(지도는 둘 다 쓴다).
- 원장 `actor` 열거(GENERAL|LORD|RULER|OFFICE_HOLDER)는 상위 설계에 없는 새 어휘다 — 재설계 용어(장수·주공·군주·관직)와 맞출지.
- WORK 중단, 무효 전 비용 비환불 — 위에 「새 제안」으로 표시한 두 규칙의 승인.
- `effects[]` 의 표준 어휘(자원 증감·카드 이동·상태 변화).
- 12순 목록의 효력 시작 표식이 담을 정보량.
- 기존 작전(V56)·출병 계획(V57)·휘하(V55) 인테이크를 새 봉투로 옮길 시점.
