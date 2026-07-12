# 오픈삼국 v2 커맨드 카탈로그와 이행 계획

> 작성일: 2026-07-12
> 상태: proposed command source of truth
> 상위 문서: `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md`
> 레거시 원천: `common/src/main/kotlin/opensamguk/common/constants/GameConst.kt`, `legacy/devsam-core/hwe/sammo/TurnExecutionHelper.php`

## 1. 한 줄 규칙

- 개인턴은 장수 관련 명령이다.
- 사령턴은 국가 관련 명령이다.
- 전략 명령은 작전·국가·도시·외교 상태를 바꾸는 도메인 명령이며 개인턴 또는 사령턴의 권한으로 제출된다. 별도 예약 링을 만들지 않는다.
- 실시간 전술 명령은 열린 `BattleSession` 안에서 부대와 대형을 조종하며, 개인턴·사령턴 링을 소비하지 않는다.

```text
general_turn  ── 개인턴: 장수/retinue 행동
nation_turn   ── 사령턴: 국가/직책 행동
operation.*   ── 전략 도메인: 전선/작전/건설/정책
battle.*      ── 실시간 전술: formation/지형/사격/돌격
```

`general_turn`과 `nation_turn`은 v1의 정본 저장 구조와 실행 순서를 유지한다. 한 장수의 due 시점에는 레거시처럼 사령턴을 먼저 처리하고 개인턴을 다음에 처리한 뒤 두 링을 회전한다. 단, 개인턴의 `che_출병`은 사령턴 승인을 기다리지 않고 침공과 `Operation/BattleSession`을 만든다.

## 2. 공통 커맨드 계약

모든 신규 command catalog entry는 다음 메타데이터를 가진다.

```text
commandId       # canonical id, e.g. personal.sortie
legacyCode      # e.g. che_출병, null for v2-only
layer           # PERSONAL | CHIEF | STRATEGIC | TACTICAL
sourceRing      # GENERAL_TURN | NATION_TURN | NONE
subjectType     # GENERAL | RETINUE | OPERATION | BATTLE | CITY | NATION
authority       # owner | officer level | operation role
payloadVersion
adapter
parityStatus    # LOCKED | ADAPTED | NEW | DEPRECATED
```

실행 결과는 공통 상태를 사용한다.

```text
DRAFT → ACCEPTED → EXECUTING → RESOLVED
                      ├──────→ REJECTED
                      ├──────→ EXPIRED
                      └──────→ CANCELLED
```

- 개인턴·사령턴: `requestId`, `turnIdx`, `actorId`, `commandCode`, `arg`, `reason`, `logEntryId`를 남기고 JDBC flush 이후 결과를 공개한다.
- 전략 명령: `operationId` 또는 `projectId`를 만들거나 갱신하고, 권한·선행 조건·영향 범위를 함께 저장한다.
- 전술 명령: `battleId`, `formationId`, `sequence`, `issuedAtTick`, `expiresAtTick`, `clientCommandId`를 가진다. 서버 ack와 simulation event를 분리한다.

## 3. 개인턴 카탈로그

원천은 `GameConst.availableGeneralCommand`다. 아래 legacy code는 제거하지 않고 `personal.*` canonical id로 adapter를 붙인다.

| v1 카테고리 | 보존 명령 |
|---|---|
| 개인 | `휴식`, `che_요양`, `che_단련`, `che_숙련전환`, `che_견문`, `che_은퇴`, `che_장비매매`, `che_군량매매`, `che_내정특기초기화`, `che_전투특기초기화` |
| 내정 | `che_농지개간`, `che_상업투자`, `che_기술연구`, `che_수비강화`, `che_성벽보수`, `che_치안강화`, `che_정착장려`, `che_주민선정` |
| 군사 | `che_징병`, `che_모병`, `che_훈련`, `che_사기진작`, `che_출병`, `che_집합`, `che_소집해제`, `che_첩보` |
| 인사 | `che_이동`, `che_강행`, `che_인재탐색`, `che_등용`, `che_귀환`, `che_임관`, `che_랜덤임관`, `che_장수대상임관` |
| 계략 | `che_선동`, `che_탈취`, `che_파괴`, `che_화계` |
| 국가 | `che_증여`, `che_헌납`, `che_물자조달`, `che_하야`, `che_거병`, `che_건국`, `che_선양`, `che_해산` |

### v2 adapter 규칙

- `che_출병` → `personal.sortie` adapter. 기존 개인턴 판정·로그·RNG를 먼저 통과한 뒤, 성공 시 `Operation`과 `BattleSession`을 생성한다.
- `che_징병`, `che_모병`, `che_훈련`, `che_사기진작` → `personal.retinue.prepare`. v1에서는 기존 수치·로그를 그대로 쓰고, v2 sandbox에서만 retinue readiness와 연결한다.
- `che_집합`, `che_소집해제`, `che_이동`, `che_강행` → `personal.formation.organize` 또는 `personal.retinue.relocate`로 연결한다.
- 내정 커맨드 → `personal.cityAction` adapter. v2 건물은 새 `CityProject`를 사용하되 v1 내정 커맨드를 대체하지 않는다.
- `che_거병`, `che_건국`, `che_선양`, `che_해산` → 국가 상태를 바꾸지만 개인턴 원천을 유지한다. v2에서 국가 명령으로 재분류하지 않는다.

## 4. 사령턴 카탈로그

원천은 `GameConst.availableChiefCommand`와 `nation_turn(nation_id, officer_level, turn_idx)`다. 사령턴은 국가 외교·인사·전략·특수 명령이며, 전쟁을 시작하는 개인 출병을 승인하는 슬롯이 아니다.

| v1 카테고리 | 보존 명령 | v2 해석 |
|---|---|---|
| 휴식 | `휴식` | 국가 정책 없음 |
| 인사 | `che_발령`, `che_포상`, `che_몰수`, `che_부대탈퇴지시` | 직책·보상·자원 회수·부대 정책 |
| 외교 | `che_물자원조`, `che_불가침제의`, `che_선전포고`, `che_종전제의`, `che_불가침파기제의` | 국가 간 외교와 전선 상태 |
| 특수 | `che_초토화`, `che_천도`, `che_증축`, `che_감축` | 국가 비상정책·수도·국가 규모 |
| 전략 | `che_필사즉생`, `che_백성동원`, `che_수몰`, `che_허보`, `che_의병모집`, `che_이호경식`, `che_급습`, `che_피장파장` | 국가 전략·전쟁 지원·적국 교란 |
| 기타 | `che_국기변경`, `che_국호변경` | 국가 정체성 변경 |

### v2에서 추가할 사령턴 명령

| canonical id | 목적 | 전투를 시작하는가 |
|---|---|---|
| `chief.diplomacy.propose` | 동맹·불가침·통행·원조 제안 | 아니오 |
| `chief.diplomacy.declare` | 국가 외교 상태와 전쟁 명분 설정 | 아니오. 개인 출병 또는 기존 전선이 필요 |
| `chief.operation.policy` | 이미 열린 전선의 목표 우선순위·교전 규칙·퇴각선 | 아니오 |
| `chief.operation.reinforcement` | 원군·예비대·보급 우선순위 지정 | 아니오 |
| `chief.operation.withdraw` | 국가 차원의 철수·방어 전환 지시 | 아니오 |
| `chief.build.plan` | 도시 건설 계획과 국가 예산 배정 | 아니오 |
| `chief.build.assign` | 도시 프로젝트의 건설권한·담당 장수 지정 | 아니오 |
| `chief.research.start` | 국가 연구·병종 해금·개혁 시작 | 아니오 |

사령턴에서 `chief.operation.*`을 예약해도 열린 `Operation`이 없으면 정책만 저장한다. 이 규칙으로 국가 수뇌부의 외교·지원 권한과 개인 장수의 출병 권한을 분리한다.

## 5. 전략 명령 카탈로그

전략 명령은 별도 턴이 아니다. 개인턴 또는 사령턴이 권한에 따라 제출하는 `Operation`, `CityProject`, 외교, 캠페인 상태 변경의 canonical domain command다.

| canonical id | 제출 주체 | 핵심 payload | 연결되는 v1/v2 상태 |
|---|---|---|---|
| `operation.create` | 개인턴 adapter | targetCity, arrivalWindow, route, objective | `che_출병` → Operation/BattleSession |
| `operation.join` | 개인턴 | operationId, role, retinueId | 개인 장수·부곡 참여 |
| `operation.support` | 개인턴 | operationId, supportType, amount | 정찰·보급·원군 지원 |
| `operation.changeObjective` | 개인턴/사령턴 권한 | objective, priority, deadline | 작전 목표와 전쟁 명분 |
| `operation.setRetreat` | 개인턴/사령턴 권한 | line, trigger, priority | 개인 퇴각선 또는 국가 철수선 |
| `operation.reinforce` | 개인턴/사령턴 | operationId, formationId, arrivalWindow | 원군 합류 |
| `operation.cancel` | 개설자/국가 권한 | reason | 출병 취소·철수 정산 |
| `campaign.cityProject.create` | 사령턴 | cityId, templateId, budget, priority | CityProject 생성 |
| `campaign.cityProject.execute` | 개인턴 | projectId, workforce, supply | 장수 현장 실행 |
| `campaign.cityProject.pause` | 개인턴/사령턴 | projectId, reason | 건설 중단 |
| `campaign.diplomacy.resolve` | 사령턴 | targetNation, proposal, terms | 외교 상태 확정 |
| `campaign.appoint` | 사령턴 | generalId, office, cityId | 국가 인사·지휘권 |

전략 명령은 전술 화면에서 직접 실행하지 않는다. 전투 종료 시 `BattleResultAdapter`가 손실·포로·점령·보급·민심을 전략 상태 변경안으로 변환한다.

## 6. 실시간 전술 명령 카탈로그

전술 명령은 `BattleSession`이 열린 동안에만 유효하다. 병사 하나가 아니라 formation 단위를 대상으로 한다.

| canonical id | 목적 | 주요 조건 |
|---|---|---|
| `battle.formation.move` | 목표 지점·경로로 이동 | 이동 권한, 지형, 명령 만료 |
| `battle.formation.face` | 전면 방향·공격축 전환 | formation cohesion |
| `battle.formation.change` | 선형·종대·방진·개방 대형 전환 | 훈련·공간·피로 |
| `battle.formation.hold` | 현 위치 사수·대기 | morale·보급 |
| `battle.formation.fire` | 사격·일제사격·사격 우선순위 | 탄약·시야·사거리 |
| `battle.formation.charge` | 돌격·백병전 진입 | 거리·사기·대형 질서 |
| `battle.formation.support` | 인접 부대 지원·측면 보호 | 지휘거리·역할 |
| `battle.formation.withdraw` | 질서 있는 후퇴 | 퇴각선·경로·사기 |
| `battle.formation.rally` | 붕괴 직전 부대 재집결 | 장수·신호·지휘 반경 |
| `battle.formation.resupply` | 보급 거점·마차 연결 | 보급 경로·호송 상태 |
| `battle.command.delegate` | 부대 AI doctrine 위임 | 소유권·작전 역할 |
| `battle.command.revoke` | 위임 취소·직접 지휘 복귀 | command sequence |

클라이언트의 드래그·단축키·미니어처 선택은 위 명령을 만드는 입력 방식일 뿐이다. 서버는 fixed tick에서 이동·충돌·사격·피해·사기·피로·보급을 계산하고 `BattleEvent`를 발행한다.

## 7. 커맨드 추가·수정·삭제·통합 정책

1. **추가**: v2-only 명령은 canonical id와 권한·payload·replay event를 먼저 등록한다.
2. **수정**: legacy payload에 선택 필드만 추가하고 필드 부재 시 v1 기본값을 사용한다.
3. **분리**: `che_출병`처럼 한 legacy 명령이 작전 생성과 전술 실행을 모두 함축하면 legacy facade를 유지하고 내부를 `operation.create`와 `battle.*`로 나눈다.
4. **통합**: precheck·권한·대상 해석·결과 envelope만 공통화한다. 패리티 로그·RNG·부수효과가 다른 실행을 합치지 않는다.
5. **삭제**: v1 parser·golden·adapter에서 먼저 제거하지 않는다. UI 숨김 → deprecated telemetry → 대체 command 검증 → parser 제거 순서다.

## 8. 구현 순서와 게이트

### C0. 카탈로그 동결

- `availableGeneralCommand`와 `availableChiefCommand`를 CSV/JSON catalog로 추출한다.
- 각 legacy code에 layer, sourceRing, owner, adapter, parityStatus를 매핑한다.
- 개인턴·사령턴 예약 API가 다른 링을 쓰는지 테스트로 고정한다.

### C1. v1 facade와 전략 adapter

- `che_출병` 개인턴 golden을 보존한다.
- 성공 결과만 `operation.create` adapter로 전달한다.
- chief 외교 명령은 기존 `nation_turn`과 로그를 유지하면서 `DiplomacyPolicy` read model을 추가한다.

### C2. 전략 상태와 건설

- `Operation`, `OperationParticipant`, `CityProject`, `DiplomacyPolicy` read model을 추가한다.
- 사령턴은 정책·예산·원군·건설 계획만 만들고, 개인턴은 출병·참여·현장 실행을 담당한다.
- 기존 v1 world와 v2 sandbox world를 feature flag/world id로 분리한다.

### C3. 전술 엔진

- `BattleSession`, `BattleState`, `Formation`, `BattleOrder`, `BattleEvent`, `BattleReplay`를 추가한다.
- 서버 fixed tick, idempotency, sequence, reconnect, AI delegation을 먼저 닫는다.
- 실시간 대형 부대 1개와 지원/적 formation 2개로 이동·대형 전환·사격·사기 붕괴·퇴각을 검증한다.

### C4. 전장·캠페인 연결

- 개인 출병 → 실시간 전투 → replay → BattleResultAdapter → 도시/인물/외교 결과를 닫는다.
- 곡창·역참·망루·성벽 중 2개가 전투 상태에 실제 영향을 주는 fixture를 만든다.
- 사령턴 외교가 전투를 직접 시작하지 않고, 열린 전선에 정책으로 반영되는지 검증한다.

### C5. 확장과 폐기

- command usage와 reject reason을 측정한다.
- v1 명령은 parityStatus가 `LOCKED`인 동안 삭제하지 않는다.
- 오픈 나폴레오닉·오픈 엠파이어는 같은 catalog와 BattleSession 계약을 사용하는 EraPack으로 추가한다.

## 9. 완료 기준

- `general_turn` 개인턴과 `nation_turn` 사령턴의 legacy 예약·실행·회전 테스트가 녹색이다.
- 개인 `che_출병`이 사령턴 승인 없이 침공을 만들고, 사령턴은 열린 전선에만 정책을 적용한다.
- 전략 명령과 전술 명령이 서로의 저장 링을 오염시키지 않는다.
- 같은 seed·snapshot·명령 sequence에서 전술 replay diff가 0이다.
- 재접속·명령 중복·명령 만료·플레이어 이탈 시 AI 위임이 재현 가능하다.
- v1 backend/web gate와 PHP golden 결과가 변하지 않는다.

## 10. 커맨드 카탈로그 효율화 규칙

### 10.1 정규화 파이프라인

모든 입력은 다음 한 경로를 통과한다.

```text
legacyCode 또는 canonicalId
  → alias resolve
  → canonical intent normalize
  → authority/precheck
  → execution adapter
  → domain event
  → legacy-compatible response/log
```

예약 링은 스케줄러이고, command catalog는 의미·권한·대상·효과의 정본이다. 따라서 `general_turn`과 `nation_turn`의 저장 구조를 합치지 않고도 내부 의미는 정규화할 수 있다.

정규 command는 `verb + target + options` 형태를 우선한다.

```text
personal.recruit(mode=CONSCRIPTION|VOLUNTEER)
personal.cityProject.execute(type=AGRICULTURE|COMMERCE|SECURITY)
chief.diplomacy.propose(relation=NON_AGGRESSION|WAR|PEACE|AID)
battle.formation.issue(order=MOVE|FIRE|CHARGE|WITHDRAW, formationId, target)
```

단, 원래 PHP가 별도 RNG·로그·부수효과를 갖는 legacy command는 같은 canonical handler를 공유하더라도 adapter에서 legacy 결과를 보존한다.

### 10.2 재배치 기준

재배치는 UI 카테고리 변경과 실행 계층 변경을 분리한다.

| 상황 | 처리 |
|---|---|
| 이름·메뉴만 어색함 | catalog category만 변경하고 legacyCode/sourceRing은 유지 |
| 개인 장수가 국가 자원을 대상으로 함 | 개인턴을 유지하되 `scope=STRATEGIC`, `authority=GENERAL`로 표시 |
| 국가 직책이 외교·예산·전쟁 정책을 대상으로 함 | 사령턴/nation_turn으로 유지 |
| 전투 중 부대 위치·대형·사격을 바꿈 | tactical/battle stream으로 이동. general_turn 예약 금지 |
| 국가 상태와 전투 상태를 한 명령이 동시에 변경함 | legacy facade를 두 canonical intent로 분리하고 campaign settlement에서 연결 |

`che_증여`, `che_헌납`, `che_물자조달`처럼 개인턴에 있으나 국가 자원을 대상으로 하는 커맨드는 v1에서 링을 옮기지 않는다. v2 catalog에서 전략 scope를 표시하고, 새 국가 정책 명령과 의미가 겹치는 부분만 adapter로 연결한다.

### 10.3 추가 기준

새 명령은 아래 네 조건을 모두 만족할 때만 추가한다.

1. 기존 canonical command의 옵션 확장으로 표현할 수 없다.
2. 권한 또는 처리 주기가 기존 명령과 다르다.
3. 독립된 domain event 또는 replay event가 필요하다.
4. 거부 사유와 성공 결과를 별도 검증할 테스트가 있다.

예를 들어 `battle.formation.rally`는 사기·지휘 반경·대형 붕괴라는 독립 판정과 event가 있으므로 추가한다. 반면 `battle.fastMove`는 `battle.formation.move`의 속도 옵션으로 처리한다.

### 10.4 삭제 기준

삭제는 실제 파일 제거가 아니라 네 단계로 진행한다.

```text
ACTIVE → HIDDEN_FROM_NEW_UI → DEPRECATED → REMOVED
```

- `ACTIVE`: legacy UI와 API에서 사용.
- `HIDDEN_FROM_NEW_UI`: 새 v2 화면에는 노출하지 않지만 legacy code와 parser는 유지.
- `DEPRECATED`: 사용량·대체 command·replay 회귀를 확인하고 경고를 남김.
- `REMOVED`: v1 golden, production data, saved queue, adapter가 모두 0임을 확인한 뒤에만 제거.

v1 parity command는 v2가 완성되어도 `LOCKED` 상태로 남길 수 있다. 사용하지 않는다고 추정해 삭제하지 않는다.

### 10.5 병합 후보와 금지선

| 병합 후보 | canonical command | legacy 처리 | 금지선 |
|---|---|---|---|
| `che_징병`, `che_모병` | `personal.recruit(mode)` | 각각 alias adapter | 비용·RNG·로그 순서가 다르면 실행기를 합치지 않음 |
| `che_농지개간`, `che_상업투자` | `personal.cityProject.execute(type)` | 각각 alias adapter | 농업·상업 효과와 PHP 로그는 분리 유지 |
| `che_수비강화`, `che_성벽보수`, `che_치안강화` | `personal.cityProject.execute(type)` | 각각 alias adapter | city column·constraint가 다르면 precheck를 공유하지 않음 |
| 외교 제의 계열 | `chief.diplomacy.propose(relation)` | 제의별 alias adapter | 선전포고·불가침·종전의 state transition은 분리 |
| `operation.support`, `operation.reinforce`, `operation.resupply` | `operation.support(type)` | old command는 각 adapter | 원군·물자·정찰의 authority와 비용이 다르면 옵션만으로 숨기지 않음 |
| 전술 단일 명령 여러 건 | `battle.orderBatch(orders[])` 전송 envelope | event는 원래 순서대로 분해 | batch는 네트워크 효율화일 뿐 판정·로그를 한 건으로 합치지 않음 |

### 10.6 분리해야 하는 후보

| legacy 또는 혼합 책임 | 분리 결과 |
|---|---|
| `che_출병` | `operation.create`와 `battle.*`를 분리. legacy 개인턴 facade는 유지 |
| 건설 계획과 현장 시공 | `chief.build.plan/assign`와 `personal.build.execute/pause` |
| 외교 제안과 외교 응답 | `chief.diplomacy.propose`와 `chief.diplomacy.respond` |
| 작전 목표 변경과 전술 공격 | `operation.changeObjective`와 `battle.formation.attack/charge` |
| 전투 결과와 도시 점령 정산 | `BattleResultAdapter`와 `campaign.conquer/settle` |
| 부대 편성·대형 전환·실시간 이동 | `personal.retinue.organize`, `battle.formation.change`, `battle.formation.move` |

### 10.7 전술 batch로 줄일 것과 줄이면 안 되는 것

실시간 전술은 매 클릭마다 HTTP 요청을 보내지 않는다. 클라이언트는 짧은 명령 묶음을 `battle.orderBatch`로 제출하고, 서버는 각 order에 sequence를 부여한다.

- 묶을 것: 같은 formation의 연속 이동점, 여러 formation의 동시에 실행할 이동·대형 전환, 짧은 시간의 목표 갱신.
- 묶지 않을 것: 사격 명중, 피해, 사기 붕괴, 퇴각, 포로, 점령. 각 event가 replay와 결과 정산의 단위다.
- 중복 요청은 `clientCommandId`와 sequence로 멱등 처리한다.
- 늦은 명령은 무조건 실패시키지 않고 `EXPIRED`, `SUPERSEDED`, `REJECTED`를 구분해 사용자에게 보여준다.

## 11. 최종 실행 순서

1. **카탈로그 추출**: 현재 `availableGeneralCommand`/`availableChiefCommand`와 `CommandRegistry`를 읽어 모든 legacy code에 layer·ring·authority·parityStatus를 부여한다.
2. **정규화 registry**: `alias → canonical intent → adapter`만 추가한다. v1 실행기를 먼저 재작성하지 않는다.
3. **개인/사령 예약 회귀**: 30-slot `general_turn`, chief `nation_turn`의 reserve/push/repeat/pull와 due 순서를 잠근다.
4. **전략 adapter**: 개인 `che_출병`에서 operation 생성, 사령 외교에서 policy 반영, 건설 계획에서 CityProject 생성까지 연결한다.
5. **전술 command stream**: `battle.orderBatch`, sequence, ack, reconnect, AI delegation을 닫는다.
6. **정규 command로 신규 기능 추가**: 새 v2 기능은 legacy alias를 만들 필요가 없으면 canonical id로만 추가한다.
7. **deprecated 정리**: 사용량과 golden/replay 회귀를 확인한 뒤 UI·parser·adapter 순으로 제거한다.

## 12. 커맨드별 채점표

각 커맨드는 구현 전에 아래 항목을 채운다.

```text
legacyCode / canonicalId
sourceRing / actor / target / authority
precheck / state mutation / side effects
rng draw count / log order / result payload
idempotency / expiry / reconnect behavior
golden or deterministic replay fixture
rollback or rejection reason
```

하나라도 비어 있으면 `DRAFT`에서 `ACCEPTED`로 올리지 않는다.
