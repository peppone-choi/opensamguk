# 제위·황통 시스템 설계

## 목적

국가의 군주(`officer_level=12`)와 황제를 분리한다. 황제는 장수의 영구 NPC 속성이 아니라
세계 안에서 장수 사이를 이전할 수 있는 황통의 직위다. 따라서 황제의 사망, 폐위, 선양,
왕조 교체와 복수 황제의 병립을 같은 상태 전이 모델로 처리한다.

## 핵심 구분

- **국가 군주**: 국가를 실제로 지휘하는 장수. 기존 `officer_level=12`와
  `RulerSuccessionHandler`가 계속 담당한다.
- **황제**: `imperial_reign`의 현재 보유자. 국가 군주와 같을 수도, 다를 수도 있다.
- **섭정**: 황제를 대신해 조정을 통제하지만 황제는 아니다. 국가 군주 또는 별도 장수가 될 수 있다.
- **황통**: 후한·조위·촉한·손오처럼 독립적으로 존속하는 제위 계보. 한 세계에 여러 활성 황통을 허용한다.

예를 들어 184년 후한은 유굉이 황제이고 하진이 실권 국가 군주 겸 대장군·섭정이다.
후한 영토 표시는 법적 영유권 전체가 아니라 하진 정권의 실효 지배 범위만 사용한다.

## 데이터 모델

### `imperial_line`

- `world_id`: 세계 식별자
- `code`: 세계 안에서 유일한 황통 코드(`later_han`, `cao_wei`, `shu_han`, `sun_wu`)
- `name`: 표시 왕조명
- `status`: `ACTIVE`, `VACANT`, `ENDED`
- `court_nation_id`: 황실을 보호·통제하는 국가. nullable
- `capital_city_id`: 조정 소재지. nullable
- `holder_general_id`: 현재 황제의 장수 ID. `VACANT`이면 nullable
- `regent_general_id`: 섭정 장수 ID. nullable
- `designated_heir_general_id`: 현재 지정 후계자. nullable
- `succession_rule`: `SCRIPTED_THEN_DYNASTIC`
- `legitimacy`: 0~100
- `founded_year`, `founded_month`, `ended_year`, `ended_month`
- `meta`: 혈통 후보 순서, 역사 사건 키 등 확장 데이터

한 세계에서 `code`는 유일하고, 한 장수는 동시에 둘 이상의 활성 황통을 보유할 수 없다.

### `imperial_transition`

모든 변경을 append-only로 기록한다.

- `type`: `ENTHRONEMENT`, `DEATH_SUCCESSION`, `ABDICATION`, `DEPOSITION`,
  `FOUNDATION`, `VACANCY`, `EXTINCTION`
- `from_holder_general_id`, `to_holder_general_id`
- `actor_general_id`: 폐위·선양을 집행한 장수. nullable
- `year`, `month`, `phase`
- `reason_code`: 시나리오 사건 또는 시스템 사유
- `meta`: 작위 강등, 새 황통 창설 등 부수 결과

### `imperial_allegiance`

일반 국가 간 외교와 별개로, 각 국가가 각 황통을 어떻게 대하는지 기록한다. 복수 황통 시대에는
같은 국가가 조위는 인정하고 촉한은 부정하는 식으로 황통별 관계를 각각 가질 수 있다.

- `world_id`, `imperial_line_id`, `nation_id`: 복합 식별 범위
- `relation`: `COURT_GUARDIAN`, `LOYAL`, `INVESTED`, `TRIBUTARY`, `NEUTRAL`,
  `REJECTED`, `HOSTILE`, `PRETENDER`
- `recognition`: `RECOGNIZED`, `CONTESTED`, `REJECTED`
- `investiture_title`: 황제가 수여한 왕·공·주목 등의 작위. nullable
- `favor`: -100~100의 황실 친밀도
- `tribute`: 정기 조공 여부와 주기
- `meta`: 책봉 일자, 강제 복속, 역사 사건 키

`COURT_GUARDIAN`은 황제를 보호하거나 통제하는 국가이며 황제 자신이나 황통과 동일하지 않다.
`PRETENDER`는 해당 황통의 정통성을 부정하고 자기 황통을 내세우는 상태다. 황실 관계는 국가 간
동맹·전쟁 상태를 자동으로 대체하지 않지만, 황실 명령의 수락·책봉·선양 명분·정통성 계산에 쓰인다.

## 승계 규칙

황제 사망 시 다음 순서로 단 한 번 결정한다.

1. 현재 연도·월에 발동하는 시나리오 지정 승계 사건
2. 생존한 `designated_heir_general_id`
3. 황통 `meta.dynasticCandidates`에 기록된 생존 후보 순서
4. 후보가 없으면 `VACANT`

미성년 황제도 즉위할 수 있다. 이 경우 황제와 국가 군주를 합치지 않고 기존 섭정을 유지하거나
사건이 지정한 섭정을 설정한다. 임의의 능력치·공적 순위로 황제를 뽑지 않는다.

## 선양·폐위

기존 `che_선양`은 **국가 군주직 양도**로 유지한다. 제위를 넘기는 명령은 별도의
`imperial_abdicate` 전이를 사용하며 다음을 원자적으로 수행한다.

1. 이전 황제의 재위 종료
2. 후계 황제 즉위 또는 새 황통 창설
3. 이전 황통의 `ACTIVE`/`ENDED` 상태 변경
4. 이전 황제의 사후 지위 기록(예: 유협 → 산양공)
5. 세계·국가·장수 역사 로그 기록

폐위는 이전 황제를 생존 상태로 남길 수 있으며 `DEPOSITION`으로 기록한다. 즉, 폐위와 사망은
같은 동작이 아니다.

## 시나리오 기준 상태

- **1010 황건적의 난**: 후한/유굉, 지정 후계 유변, 섭정 하진
- **유굉 사망**: 유변 즉위, 하진 섭정 유지
- **동탁 폐위 사건**: 유변 폐위, 유협 즉위, 조정 통제국과 섭정 갱신
- **220년 선양**: 유협 퇴위, 후한 황통 종료, 조비가 조위 황통 창설, 유협은 산양공
- **삼국 병립기**: 조위·촉한·손오 황통을 각각 활성 상태로 함께 보유 가능

## 표시와 NPC 정책

- 장수 DB의 정본 이름은 항상 개인명(`유굉`, `유변`, `유협`)이다.
- `♛` 표식과 황제 금색은 활성 `imperial_line.holder_general_id`에서 파생한다.
- 이름 문자열에 `♛`를 저장하거나 `npc_state=7`만으로 황제 여부를 고정하지 않는다.
- 황제의 행동 제한은 제위 보유 상태와 시나리오 정책으로 판정한다.
- 황제가 퇴위·폐위되면 표식과 황제 전용 행동 제한이 즉시 사라진다.
- 국가 화면에는 황통별 황실 관계, 책봉 작위, 인정 여부를 표시한다.

## 황실 관계의 게임 효과

- `COURT_GUARDIAN`: 조정 소재지와 황제를 실제로 보유하며 황실 명령을 제안할 수 있다. 강압적 통제는
  다른 충성 국가의 관계와 정통성을 떨어뜨릴 수 있다.
- `LOYAL`/`INVESTED`: 황실 명령과 관직·작위 수여의 대상이며, 정통 황통 보호 전쟁의 명분을 얻는다.
- `TRIBUTARY`: 정기 자원을 보내고 외교·상업 보너스를 받지만 내정 지휘권은 넘기지 않는다.
- `REJECTED`/`HOSTILE`: 황실 명령을 받지 않으며 황통 공격에 별도 명분이 필요 없다.
- `PRETENDER`: 자기 황통 창설 또는 기존 병립 황통 지지를 전제로 하며, 양쪽 황통의 정통성 경쟁을 만든다.
- 관계 변화는 명시적 외교 명령·시나리오 사건·황제 이동·선양 전이로만 발생한다. 일반 우호도만으로
  자동 승격하지 않는다.

## 일관성 규칙

- 황제 사망 처리와 국가 군주 사망 처리는 각각 실행한다. 같은 장수라면 제위 승계를 먼저 확정한 뒤
  기존 국가 군주 승계를 수행한다.
- 하나의 트랜잭션에서 황통 현재 상태와 전이 이력을 함께 저장한다.
- 후계자도 같은 처리 도중 사망하거나 삭제되는 경우 재조회하여 다음 후보로 넘어간다.
- 시나리오에 지정된 황제·후계자·섭정은 해당 시나리오의 활성 장수 명단에 반드시 존재해야 한다.
- `ENDED` 황통은 일반 승계로 다시 활성화되지 않는다. 복원은 명시적 `FOUNDATION` 사건만 허용한다.
