# 입력 6종 registry 계약

> 작성일: 2026-09-17
> 상태: **초안(제안).** 교차 비평·사용자 승인 전. 이슈 #779 / OPENSAM-259.
> 상위: [장수·휘하 캠페인 재설계](./2026-09-17-general-and-retinue-campaign-redesign.md) §4·§5·§12, ADR-LITE-057, ADR-LITE-049 개정(2026-09-17 사용자 결정, `.ai/decisions.md`에 반영됨)
> 범위: 계약만 정한다. 구현·수치는 이 문서에 없다.

## 2026-09-20 기반 결정

사용자의 게임 기획 위임에 따라 `ruleProfile` 저장 자리는 현행 구현대로 **`world_state.config["ruleProfile"]`** 하나로 확정한다. `ScenarioImporter`가 시나리오 선언을 적고 엔진은 `TurnWorldState.ruleProfile`로 읽는다. 누락만 SAMMO 기본값이며 알 수 없는 값과 문자열 아닌 값은 거절한다. meta에 복제하거나 런타임 프로필 전환 경로를 추가하지 않는다.

원장·핸들러의 실제 상태는 여전히7행 PLANNED다. 이 결정은 입력 기능 완료나 계약 전체의 승격이 아니다. actor·권한·효과 봉투는 첫 실제 출사 소비자와 함께 확정하고, 아래 WORK·UI·이전 관련 미결을 기반 구현 완료로 포장하지 않는다.

## 1. 왜 필요한가 — 2026-09-17 SAMMO 경로 관측

| 당시 SAMMO 경로 | 근거 | 새 게임에서의 문제 |
|---|---|---|
| 모르는 명령 코드는 휴식으로 떨어진다 | `CommandRegistry.resolve` 마지막 분기 `else -> RestAction` (`logic/.../actions/CommandRegistry.kt`) | 로드맵 「registry에 없는 입력은 휴식이나 성공으로 떨어지지 않고 명시적으로 실패」와 반대다 |
| 명령은 문자열 코드 → 클래스의 `when` 표 | 같은 파일, 문자열 분기 92개(`che_` 80 + 그 밖 12) + `else` | 입력 종류(행동·배치·방침·공사·계책·조정 결정)를 구분하는 축이 없다 |
| 새 결과 타입을 직렬화기 집합에 안 넣으면 턴 루프 전체가 멈춘다 | `TurnDaemonCommandResultSerializer` `else -> throw` 전례(2026-09-06 boardRead) | 입력이 늘수록 같은 사고가 난다 — 등록을 한 곳에서 강제해야 한다 |
| 제품 범위 정본이 알파 카탈로그 124행(기존 별칭 70) | `data/commands/public-alpha-command-catalog.json`, `PublicCommandCatalogIndex` | ADR-LITE-057 이 정본 지위를 거뒀다. 새 원장이 필요하다 |

현재는 `HwihaInputRegistry`와7행 PLANNED 원장이 구현돼 있다. 아래 관측은 기존 경로의 출발점이며, 남은 작업은 새 입력의 실제 소비자 연결이다.

## 2. 식별과 종류

```text
InputKind   = GENERAL_ACTION | PLACEMENT | POLICY | WORK | STRATAGEM | COURT_DECISION
inputId     = "<kind>.<name>"        예: action.enlist, placement.assign, policy.set,
                                        work.start, stratagem.play, court.dispatch
ruleProfile = SAMMO | HWIHA          월드마다 하나. 삼모 월드는 기존 CommandRegistry 를 그대로 쓴다
```

- 같은 라우트·같은 인테이크를 쓰되 **월드의 ruleProfile** 로 갈린다(ADR-LITE-049 개정: 기존 라우트를 바로 교체하되 pep 전환 전까지 기존 명령 입력 경로 유지).
- `SAMMO` 월드에서 `HWIHA` 입력을, `HWIHA` 월드에서 `che_*` 코드를 받으면 **명시적 거절**(`reason = WRONG_RULE_PROFILE`)이다. 휴식으로 떨어지지 않는다.
- **ruleProfile 의 자리(2026-09-20 확정):** 시나리오 JSON 이 선언하고, 시드 때 `ScenarioImporter`가 `world_state.config["ruleProfile"]`에 적는다. 런타임은 저장된 config의 같은 값을 사용한다. game-api의 새 입력 분기는 이 계약을 소비해야 하며 미구현 배선을 완료로 취급하지 않는다. 값이 없으면 `SAMMO` 다(기존 월드·시나리오 무변경). 월드가 살아 있는 동안 바뀌지 않고, 바꾸는 길은 초기화(재시드)뿐이다 — pep 전환(재설계 §15.2)이 곧 이 재시드다.
- 기존 명령 70개의 대응은 재설계 §12 표가 정본이고, 원장 행마다 `legacyCommands[]` 로 역참조를 단다.
- `legacyCommands[]` 는 **기존 명령 역참조**다. 「대체」가 아니다 — 직접 행동은 기존 이름을 그대로 잇고(ADR-LITE-062), 같은 기존 명령이 위임 형태(방침·배치·공사)로도 간다. 그래서 **기존 명령 하나를 여러 행이 가리켜도 된다(다대일).** 대응 검사는 「기존 명령마다 가리키는 행이 하나 이상」으로 세고 「정확히 하나」를 요구하지 않는다. 금지는 둘뿐이다: 한 행 안의 같은 이름 중복, 실제 삼모 명령이 아닌 이름(`CommandRegistry.resolve` 가 `RestAction` 으로 떨어지는 이름). (#837, 옛 필드 이름 `replacesLegacy` 는 원장 파서가 거절한다.)
- 직접 행동 행은 기존 표시 이름을 쓰되 `inputId` 는 새 꼴(`action.<name>`)이다 — `che_…` 꼴은 registry 가 `WRONG_RULE_PROFILE` 로 거절한다.

## 3. 원장 행

```text
inputId, kind, layer(1|2|3), actor(GENERAL|LORD|RULER|OFFICE_HOLDER),
authorityRule, targetSchema, costSchema(전·곡·철·목재·말), timing, effectScope,
failureReasons[], resultType, replayContract, aiPolicyId, helpTopicId,
tutorialObjectiveId|N/A, legacyCommands[], deliveryState
```

- (구현 PR #815 에서 추가한 어휘) `deliveryState` 맨 앞에 **`PLANNED`** 를 둔다 — 원장에 올랐지만 핸들러가 없는 입력이다. registry 는 이런 입력을 `NOT_DELIVERED` 로 거절한다. 거절 사유는 4종이다: `MALFORMED_INPUT_ID` · `WRONG_RULE_PROFILE` · `UNKNOWN_INPUT` · `NOT_DELIVERED`. 핸들러 유무는 `HANDLER_READY` 이상과 정확히 일치해야 하고, 어긋나면 registry 생성이 실패한다.
- `PLANNED` 뒤는 기존 파이프라인을 그대로 쓴다: `DOMAIN_READY → HANDLER_READY → UI_READY → AI_READY → HELP_READY → TUTORIAL_READY → REPLAY_READY → VERIFIED`(재기준선 §3 보존).
- 원장 파일: `data/commands/hwiha-input-catalog.json`(현행 파일). 알파 카탈로그 파일과 `PublicCommandCatalogIndex` 는 `SAMMO` 월드용으로 남는다.

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

- ruleProfile 저장 자리: **해결** — 위2026-09-20 결정과 현행 구현대로 `world_state.config`.
- 원장 `actor` 열거(GENERAL|LORD|RULER|OFFICE_HOLDER)는 상위 설계에 없는 새 어휘다 — 재설계 용어(장수·주공·군주·관직)와 맞출지.
- WORK 중단, 무효 전 비용 비환불 — 위에 「새 제안」으로 표시한 두 규칙의 승인.
- `effects[]` 의 표준 어휘(자원 증감·카드 이동·상태 변화).
- 12순 목록의 효력 시작 표식이 담을 정보량.
- 기존 작전(V56)·출병 계획(V57)·휘하(V55) 인테이크를 새 봉투로 옮길 시점.

## 2026-09-20 출사 도메인 결정과 구현 경계

사용자 기획 위임에 따라 첫 출사 소비자의 순수 규칙을 다음처럼 정한다. 상위 §12.1의 임관·랜덤임관·장수대상임관 세 직접 행동은 유지하며, 공통 입력 `action.enlist`의 `NATION`·`RANDOM`·`GENERAL` 선택으로 구분한다. 화면에서는 각 기존 이름을 유지한다. 원장 역참조3개가 행동3개의 폐지를 뜻하지 않는다.

- NATION은 명시된 세력의 군주에게, GENERAL은 지정 장수가 섬기는 가장 가까운 명시적 주공에게 출사한다. 지정 대상 자체가 주공이면 그를 섬긴다. RANDOM은 유효한 세력의 군주들을 세력ID 순으로 나열한 뒤 개인 턴의 결정론 RNG로 균등 선택한다. 옛 랜덤임관의 삼모 가중치·NPC 분기는 HWIHA에 복제하지 않는다.
- 주공은 사건으로 얻고 잃는 지위다. `officerLevel == 12`, 보유 縣 수, 휘하 크기를 새 정본 판정으로 쓰지 않는다. 도메인은 명시적 지위·군주 대응·출사 허용·남은 명망을 입력받는다. 런타임의 지위 저장과 역사 NPC 주공 시드는 아직 연결해야 한다.
- 재야 장수와 방랑 주공의 출사를 먼저 구현한다. 이미 다른 세력을 섬기는 경우에는 `ALREADY_SERVING`, 이미 주인이 있으면 `ALREADY_BOUND`로 거절한다. 세력을 가진 주공의 출사·국가 정리는 이 입력의 완료 범위에 포함하지 않는다.
- 성공 의도에는 새 주공·새 소속·동반 전향할 개인 휘하 장수ID·주공지위 상실 여부를 담는다. NPC 주공이 사람 장수를 받는 것을 금지하지 않는다. 기존 위치와 기준 城, 개인 부곡·부장·보물의 소유는 옮기지 않는다. 부임 이동은 뒤 발령 수락과 행군에서 처리한다.
- 대상 주공은 출사자 카드 한 장의 명망만 부담한다. 개인 휘하 비용은 출사자에게 남는다. 카드 가격·가용 명망은 명시적 양의 비용/비음수 잔량으로 받으며 누락한 주공 예산은 부족으로 거절한다. 곡물·금의 추가 출사 비용은0으로 선택했다. 직접 행동1회를 쓰는 시점은 개인 턴 정치 단계다.
- 방랑 주공은 출사 후 주공 지위를 잃는다. 그가 사람 장수를 직접 거느렸다면 `HUMAN_RETAINER_REQUIRES_LORD`로 거절하며 자동 재배속하지 않는다. 누락 장수·이중 주인·순환·소속 불일치·개인 휘하 내부 비주공의 사람 장수 지배는 `INVALID_RETINUE`다.
- `HwihaEnlistmentRules.assess`를 사전검사와 실행 재검사에서 공유한다. 실행은 최신 스냅샷으로 다시 평가해야 한다. 랜덤 선택은 재검사 뒤 하며 후보 하나일 때 RNG를 소비하지 않는다. 반환 계획은 저장 완료 결과가 아니다.

현재 구현은 순수 도메인과 반례 테스트다. 원장7행은 PLANNED 그대로이며, 주공지위 시드·명망 산식·실제 API/예약·턴 실행·휘하 생성·전향 집계·flush/재로드·결과 조회·화면은 이후 연결한다. 기존 `JoinCommand`의 도시 즉시이동이나 `RetainerHandler`의 NPC 주공 금지를 새 입력에 그대로 적용하지 않는다. 새 범용 입력 프레임워크나 별도 저장 경로는 만들지 않는다.
