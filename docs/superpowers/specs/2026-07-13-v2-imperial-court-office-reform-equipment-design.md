# 오픈삼국 v2 황실·관직·개혁·장비 설계

> 작성일: 2026-07-13
> 상태: user-direction-adopted, review-cleared
> 범위: 황제와 조정, 협천자령제후, 중앙·지방·군사 관직, 추천·자칭·임명, 개혁 확산, 장비·인장·옥새
> 비범위: v1 `officer_level`, 국가 작위, 아이템 효과와 PHP 패리티 변경

## 1. 결정

v2의 황실은 `황제를 점령하면 보너스`를 주는 단일 상태가 아니다. 황제의 신변, 조정 소재지, 조정 관료, 상서 문서 기구, 인장·절·조서, 궁정 경비, 재정·군량, 역참·사자망을 서로 다른 실체로 만든다.

v2의 관직도 `officer_level` 숫자 하나가 아니다. 다음 여섯 항목을 분리한다.

```text
OfficeDefinition       제도상 관직의 이름·시기·직무·정원·임명권자
OfficeNomination       누가 누구를 어떤 관직에 추천했는가
OfficeClaim            황제 임명·군벌 임명·자칭·대행 등 법적 주장
OfficeTenure           임명 수락 뒤 실제 재임 중인 관직
OperationalAssignment  현재 국가·도시·막부·전선에서 수행하는 실무
NobleTitle             왕·공·후와 식읍. 관직·통치권과 별개
```

이 구조로 다음 상태가 모두 가능해야 한다.

- 황제가 내린 예주자사이지만 예주에 치소·속관·군대가 없어 실효 지배하지 못한다.
- 군벌이 스스로 주목이라 칭하고 실제 영토는 지배하지만 조정의 승인을 받지 못한다.
- 군벌이 장수를 태수로 추천했으나 조정이 기각하거나 다른 관직만 내린다.
- 한의 중앙관직을 보유하면서 자기 세력에서는 상국·외교책임자·전선사령관 역할을 맡는다.
- 조정이 기존 자칭 관직을 사후 추인해 같은 경력의 법적 지위만 바뀐다.
- 높은 장군호와 후작을 갖고도 실제 지휘할 병력과 식읍 수입이 없을 수 있다.

## 2. 참고 자료에서 채택할 것과 기각할 것

### 토탈 워: 삼국/개혁

[개혁 문서](https://namu.wiki/w/%ED%86%A0%ED%83%88%20%EC%9B%8C:%20%EC%82%BC%EA%B5%AD/%EA%B0%9C%ED%98%81)는 농업·경제·행정·군사·교역의 선택 경로, 건물·지역·세력 단계와 연결된 해금, 황건·도적·남만의 별도 개혁 문법을 보여준다.

채택한다.

- 같은 문제도 국가 정체성·지역·조직 단계에 따라 다른 개혁 경로를 갖는다.
- 개혁은 새 시설, 새 관직, 새 외교 조항, 새 모집원을 열어야 한다.
- 도적 조직처럼 지도상의 지역망을 장악할수록 제도 흡수와 연락이 쉬워질 수 있다.
- 남만의 전통 유지/한식 제도 수용처럼 되돌리기 어려운 제도 갈등을 표현한다.

그대로 복제하지 않는다.

- 일정 턴을 기다리면 전국에 즉시 적용되는 추상 기술 트리.
- 오행 색깔과 다섯 분류에 맞춘 선행 노드.
- `수입 +15%`만 남는 개혁.
- 건물을 한 번 보유했다는 이유로 전국 제도를 연구하는 방식.

### 토탈 워: 삼국/부속 장비

[부속 장비 문서](https://namu.wiki/w/%ED%86%A0%ED%83%88%20%EC%9B%8C:%20%EC%82%BC%EA%B5%AD/%EB%B6%80%EC%86%8D%20%EC%9E%A5%EB%B9%84)는 무기·갑옷·탈것·추종자·장신구, 고유 물품, 지역 생산과 획득, 세트 구성을 보여준다.

채택한다.

- 고유 물품은 복제되지 않고 세계에 실제 소유자와 위치를 가진다.
- 말·갑옷·무기·문서·인장은 생산지와 공급망을 가진다.
- 장비는 사용 가능한 행동과 전술 능력을 바꾼다.
- 포획·증여·상속·교역·몰수·분실의 소유권 이력이 남는다.

그대로 복제하지 않는다.

- 사람인 추종자를 늙지 않는 장비 슬롯으로 취급하는 방식.
- 반란군이 무작위 장신구를 생성하는 방식.
- 세트 아이템 착용만으로 전국 비축·수입이 바뀌는 방식.
- 옥새·관인·절을 개인 능력치 장신구로 취급하는 방식.

### 토탈 워: 삼국/세력

[세력 문서](https://namu.wiki/w/%ED%86%A0%ED%83%88%20%EC%9B%8C:%20%EC%82%BC%EA%B5%AD/%EC%84%B8%EB%A0%A5)는 세력 단계, 조정 직책, 천자 옹립, 황실 호의, 황건·도적·남만의 다른 정치 구조를 보여준다.

채택한다.

- 한식 조정, 도적 연맹, 종교 운동은 같은 관직명과 승진표를 강제로 쓰지 않는다.
- 황제를 보호·추방·시해·복위하는 선택은 장기 정치 결과를 만든다.
- 정체성에 따라 후계·조정·외교의 작동 방식이 달라진다.

그대로 복제하지 않는다.

- 건물 위신을 모으면 귀족→후→공→왕→황제로 자동 승급하는 방식.
- 황제가 있는 도시를 점령한 세력이 즉시 천자를 소유하는 방식.
- 천자 옹립을 정기 이벤트의 전역 수치 보너스로 축소하는 방식.
- 황건·도적이라는 이유로 외교 문법 자체를 임의로 막는 방식.

### 삼국지/관직

[관직 문서](https://namu.wiki/w/%EC%82%BC%EA%B5%AD%EC%A7%80/%EA%B4%80%EC%A7%81)는 재상·삼공·구경·중앙 무관·내조·속관·지방관·작위·막부를 한눈에 보는 참고표다. 실제 구현 근거는 『후한서』 백관지와 개별 인물 열전으로 다시 고정한다.

핵심 교훈은 **높은 칭호, 실제 직무, 지휘권, 영토, 식읍은 서로 다르다**는 점이다.

## 3. 관직 정본 데이터

```text
OfficeDefinition
  id
  regimeId
  canonicalName
  officeClass
  validFrom, validTo
  appointmentAuthority
  nominalJurisdiction
  nominalDuties[]
  dutyCapabilityRequirements[]
  seatRequirement
  staffTemplate
  salaryAndBudgetRule
  capacity
  concurrencyRules[]
  provenance

OfficeNomination
  id
  proposerId
  candidateId
  requestedOfficeId
  requestedJurisdiction
  grounds
  submittedToCourtId
  status
  reviewedBy[]

OfficeClaim
  id
  officeId
  claimantId
  origin
  issuerId
  nominationId?
  credentialIds[]
  claimedJurisdiction
  recognitionByPolity{}
  status

OfficeTenure
  claimId
  holderId
  acceptedAt
  assumedAt?
  seatPlaceId?
  staffIds[]
  budgetNodeId?
  actualJurisdiction[]
  exercisedDuties[]
  endedAt?, endReason?

OperationalAssignment
  id
  polityId
  assigneeId
  role
  authorityScope
  resourceScope
  formationScope[]
  placeScope[]
  delegatorId
  activeFrom, activeTo
```

`OfficeDefinition`은 제도상 직무 후보를 설명할 뿐 행동 권한을 직접 부여하지 않는다. 모든 관직 행동은 `OfficeCapabilityResolver`가 다음 증거를 함께 확인한 뒤 허용한다.

```text
OfficeCapabilityResolver
  input: claim origin + recognition
       + accepted/active OfficeTenure
       + assumed seat + present staff + available budget
       + required seal/tally/document
       + current OperationalAssignment
       + place/formation jurisdiction
  output: ALLOW(capability, scope, resourceLimit)
        | DENY(reason, missingEvidence[])
```

따라서 황제에게 정식 임명되었어도 부임하지 않았거나 속관·예산·관인이 없으면 명목 직함만 남는다. 반대로 군벌이 맡긴 실제 전선 역할은 그 정권 안에서 실무 권한을 만들 수 있지만 황제 임명 관직으로 변환되지는 않는다. handler와 AI는 `OfficeDefinition.dutyCapabilityRequirements`를 단독으로 읽지 않고 반드시 resolver 결과만 사용한다.

### 3.1 주장 출처

| `OfficeClaim.origin` | 의미 | 시작 신뢰도 |
|---|---|---|
| `IMPERIAL_GRANT` | 황제·정상 조정이 발령 | 조정 정통성은 높으나 실효성은 별도 |
| `COURT_CONFIRMED` | 기존 자칭·군벌 임명을 조정이 추인 | 과거 자칭 이력을 지우지 않음 |
| `POLITY_APPOINTMENT` | 독립 정권·군벌이 자기 관할에 임명 | 내부 실권은 가질 수 있으나 타국 인정은 별도 |
| `SELF_STYLED` | 본인이 자칭 | 추종자가 따를 수 있으나 조정·타국에는 주장일 뿐 |
| `ACTING_XING` | 임시로 그 직무를 행함 | 정식 관직과 구분 |
| `CONCURRENT_LING` | 기존 관직과 함께 다른 직무를 겸함 | 물리적 수행 능력과 대리인이 필요 |
| `CAMPAIGN_COMMISSION` | 특정 정벌·방위 목적의 임시 군직 | 작전 종료·해임으로 소멸 가능 |
| `POSTHUMOUS` | 사후 추증 | 명예·가문 정통성만, 생전 지휘권 없음 |

`行`, `領`, `假節`, `持節` 등은 시대·사례별 의미를 출처와 함께 별도 credential/capability로 둔다. 이름이 비슷하다는 이유로 모두 같은 임시직 보너스로 합치지 않는다.

## 4. 추천·임명·자칭·실권의 상태 기계

```text
추천 작성
  → 제출
  → 심의중
      ├─ 원안 임명
      ├─ 낮은 관직 또는 대행으로 조정 임명
      ├─ 보류
      ├─ 기각
      └─ 경쟁 후보 임명

임명 조서
  → 후보 수락 또는 사양
  → 인장·인수·부임 명령 발급
  → 치소 도착·속관 인수
  → 재임
      ├─ 정상 수행
      ├─ 명목 재임
      ├─ 경쟁자와 분쟁
      ├─ 직무 정지
      └─ 파면·사망·사직·정권 교체
```

### 추천이 받아들여지는 조건

추천은 자동 성공률 판정 하나로 끝내지 않는다.

- 해당 관직의 공석·정원과 시기별 제도.
- 추천자의 표문 제출 권한과 조정 영향력.
- 후보의 명성·경력·가문·현재 충성·물리적 위치.
- 황제·상서·재상·외척·환관·조정 파벌의 이해.
- 실제 영토 지배자와 추천 후보의 관계.
- 경쟁 추천과 이미 존재하는 자칭·임명 claim.
- 조정이 임명을 강제할 군사·역참·재정 능력.

조정은 `기각`뿐 아니라 `대행만 승인`, `다른 관직 제시`, `관직은 승인하되 관할지는 불승인`, `관직과 작위를 분리 수여`할 수 있다. 후보도 임명을 사양하거나, 수락한 뒤 부임하지 않거나, 관직을 협상 수단으로 사용할 수 있다.

### 자칭의 효과

자칭은 금지된 실패 명령이 아니다. 자칭 즉시 다음이 발생한다.

- 자기 세력의 `OperationalAssignment`와 내부 서열은 만들 수 있다.
- 조정 인장·조서가 없으므로 `OfficeClaim`은 `SELF_STYLED`다.
- 부하·지역 명사·타 세력이 각각 인정·유보·부정할 수 있다.
- 실제 치소·속관·세입·군대를 장악하면 de facto 권한은 생긴다.
- 나중에 조정이 추인하면 claim 이력은 보존되고 origin이 `COURT_CONFIRMED` 상태를 추가한다.
- 경쟁자가 같은 관직을 받으면 관직 분쟁과 외교 명분이 생긴다.

## 5. 중앙관직·지방관직·군벌 역할의 동시 보유

### 5.1 세 개의 포트폴리오

한 장수 화면은 다음을 분리해 보여준다.

| 포트폴리오 | 예 | 주는 것 |
|---|---|---|
| 조정 관직 | 상서령, 사도, 태위, 대장군 | 조정 문서·의례·심의·중앙 지휘 권한 |
| 지방 관직 | 주목·자사·태수·국상·현령 | 특정 행정구역의 법적 관할 주장 |
| 소속 정권 역할 | 상국, 외교책임자, 동부전선사령관, 수도유수 | 현재 군벌 국가가 위임한 실제 권한 |

여기에 작위와 개인의 막부 직책이 별도로 붙는다. 한 인물이 중앙관직·지방관직·장군호·후작·군벌 내부 역할을 겸할 수 있다.

v1의 `che_발령`과 사령턴 직책은 v2에서 기본적으로 `OperationalAssignment` adapter다. 황제·조정의 관직을 만들어내지 않는다. 조정 관직은 추천·조서·수락·부임 workflow를 거쳐야 하며, 자체 정권이 독자 관직제를 세운 경우에도 발령 주체가 어느 정권인지 claim에 기록한다.

### 5.2 겸직은 공짜 슬롯이 아니다

- 관직마다 수도 출석, 순행, 치소 상주, 전선 지휘 같은 위치 요구가 있다.
- 장수가 전선에 나가면 중앙관직은 대리·속관에게 맡기거나 문서 처리 속도가 떨어진다.
- 대리인이 강해지면 독자 파벌과 정보 비대칭이 생긴다.
- 실제 수행하지 않은 관직은 명예·서열·정통성 claim만 남고 능력은 발동하지 않는다.
- 상충하는 두 관할을 동시에 행사하려면 명시적 겸임 조서, 충분한 속관, 통신망이 필요하다.

### 5.3 실효 지배 판정

관직명만으로 지도 색을 바꾸지 않는다. 지방관의 `actualJurisdiction`은 다음 증거를 조합한다.

```text
치소 점유
+ 현지 속관의 복종
+ 호적·재판·세입 장부 인수
+ 창고·역참·인장 통제
+ 주둔군 또는 인정된 무력
+ 지역 명사와 주민의 협력
+ 상급 조정/정권의 지원
```

따라서 `예주자사`를 받았어도 소패에 머물며 예주를 지배하지 못할 수 있고, 조정 승인이 없는 군벌도 실제 군현을 통치할 수 있다.

## 6. 관직 계층 카탈로그

정확한 명칭·정원·직무는 시나리오 연도와 정권별 content pack으로 둔다.

| 계층 | 대표 범주 | simulation 책임 |
|---|---|---|
| 황제·내조 | 황제, 시중, 상서령·상서, 황문·중상시, 부절 담당 | 접근, 조서 기초·심의·전달, 궁정 정보, 인장·절 |
| 재상·삼공 | 상국·승상, 태위·사도·사공, 녹상서사 | 최고 정책 조정, 감독, 상서 기구 통제 여부 |
| 구경 | 태상·광록훈·위위·태복·정위·대홍려·종정·대사농·소부 | 의례, 궁정, 경비, 말, 재판, 외교, 종실, 국가재정, 황실재정 |
| 중앙 무관 | 대장군, 표기·거기·위·전후좌우장군, 중랑장, 교위 | 수도군·숙위 또는 특정 원정의 군사 commission |
| 주 | 자사, 주목 | 감찰과 행정·군사 집행을 시기별로 구분 |
| 군·국 | 태수, 국상, 도위 | 군현 민정·재판·치안·군사·세입과 왕국 실무 |
| 현·후국 | 현령·현장, 상 | 주민과 가장 가까운 호구·재판·부역·치안 |
| 막부·속관 | 장사, 사마, 종사, 별가, 치중, 공조·병조·창조 등 | 주관자의 문서·인사·군량·법·공사·군무 실무 |
| 작위 | 왕·공·열후·관내후 등 | 신분·의례·식읍 수입 claim. 관할 통치와 분리 |

### 상설과 임시를 구분한다

- 상서·구경·지방관처럼 반복 행정을 수행하는 관직은 기관·속관·예산이 필요하다.
- 다수 장군호는 특정 정벌을 위해 설치·해제될 수 있는 commission이다.
- 대장군도 시기와 인물에 따라 야전 총사령관, 수도의 외척 섭정, 명예 최고무관이 다를 수 있다.
- 같은 이름의 관직도 후한·조위·촉한·손오에서 권한이 다를 수 있으므로 universal enum의 고정 효과를 금지한다.

## 7. 황실 정본 모델

```text
ImperialHouse
  dynasty, lineage, successionRule, ritualContinuity, recognition{}

Emperor
  personId, reign, health, age, agenda, household, personalLoyalty{}

ImperialCourt
  courtId, seatPlaceId, mobilityState
  officials[], secretariat, guards, household
  treasuryNodeId, granaryNodeId, archivesId
  communicationAccess, factionBalance

ImperialRegalia
  artifactId, kind, authenticityClaims[], custodianId, placeId

CourtProtectorate
  protectorPolityId
  securityControl, supplyControl, accessControl
  secretariatInfluence, regaliaCustody, courierControl
  obligations[], coercionEvidence[]

CourtSettlement
  settlementId, courtId, protectorateId
  stance:
    HONOR_AND_RESTORE       = 봉대(奉戴)
    COREGENT_PROTECTORATE   = 보정(輔政)
    COERCIVE_CONTROL        = 협제(挾制)
  proclaimedTerms[], actualPracticeEvidence[]
  proclaimedAt, reviewedAt, status
```

황제, 조정, 수도, 옥새는 하나의 객체가 아니다. 황제가 피난 중이고 조정 일부가 떨어져 나가며 옥새 소재가 불명확한 상태도 표현한다.

## 8. 협천자령제후의 실제 게임 루프

`협천자령제후`는 다른 세력에게 강제 명령 버튼을 얻는 효과가 아니다. 다음 일곱 단계의 정치·행정 파이프라인이다.

### 8.1 황제 확보 직후의 세 가지 조정 정착 방침

황제의 신변을 확보했다고 곧바로 소유권이 이전되지는 않는다. 안전한 치소에 도착하고 조정 경비·공급·상서·인장 관리가 재편될 때 `CourtSettlement`를 열어 다음 세 방침 중 하나를 공표한다.

| persisted stance | 방침 | 황제·조정 권한 | 보호자가 얻는 권한 | 의무 | 핵심 위험 |
|---|---|---|---|---|---|
| `HONOR_AND_RESTORE` | `봉대(奉戴)` | 황제가 재가·인사·궁정 경비·조정 의제를 주도 | 한실 최고 보호자·장군의 지위, 충성파 정통성, 정식 정벌 commission 청원 | 조서를 따르고 조정 재정·태묘·사직·백관을 복구 | 황제가 자기 세력에 불리한 인사·사면·정벌을 요구할 수 있음 |
| `COREGENT_PROTECTORATE` | `보정(輔政)` | 황제의 최종 재가와 밀지 가능성을 보존 | 추천권, 조서 공동기초, 수도 방위·공급, 합의된 상서 감독 | 권한 분장 헌장을 지키고 정기적으로 조정 심의를 열어야 함 | 황제파·보호자파의 이중 권력, 추천 기각, 섭정 장기화 |
| `COERCIVE_CONTROL` | `협제(挾制)` | 공개 재가는 남지만 접근·정보·경비가 강압 아래 놓임 | 상서 인사, 경비, 인장 보관, 사자 선발을 사실상 장악 | 조정을 먹여 살리고 강압 사실을 은폐·정당화해야 함 | 조서 신뢰 하락, 밀지·탈출·구출전·궁정 쿠데타·시해 누명 |

이 선택은 고정 세력 특성이 아니라 공개한 헌정 약속이다. 실제 행동이 약속과 어긋나면 상태가 이동한다.

```text
황제의 독자 재가·경비·재정 회복     협제 → 보정 → 봉대
접근 차단·상서 숙청·인장 강탈       봉대 → 보정 → 협제
```

폐위·시해·선양·새 왕조 선포는 네 번째 동급 보너스 선택이 아니다. 세 방침 중 하나로 조정을 운영한 뒤 발생하는 별도 `court.succession.resolve` 위기·종결 경로다.

```text
1. 보호자가 표문·조서안을 제안한다.
2. 황제와 조정 파벌이 동의·수정·거부·밀지를 선택한다.
3. 상서 기구가 문서를 기초·등록한다.
4. 권한 있는 인장·절·부절을 붙인다.
5. 사자가 역참·군사 호위를 통해 전달한다.
6. 수신 세력이 진위·강압·이익·조정 권위를 판단한다.
7. 수락·부분수락·지연·거부·공개비난 뒤 실제 집행 여부가 갈린다.
```

### 보호자가 얻는 것

- 조정에 관직·작위·정벌 명분을 청원할 우선 접근권.
- 조서 초안과 인사 추천 의제를 올릴 능력.
- 한실 충성 청중에게 보호자 정통성을 얻을 기회.
- 경쟁 군벌을 `불신·불정`으로 규정할 외교 명분.
- 조정 관료·문서·전국 인맥에 접근할 정보 이점.

### 보호자가 부담하는 것

- 황제·궁정·백관·숙위·의례·피난민의 식량과 급료.
- 수도와 역참, 태묘·사직·문서고 복구.
- 황제의 사면·인사·의례 요구와 충성파의 감시.
- 추천을 받아주지 않았을 때 자기 부하가 느끼는 굴욕.
- 조서에 복종하면 군벌의 자율성이 줄고, 거부하면 스스로 조정 권위를 훼손하는 딜레마.
- 황제 탈출·밀지·궁정 쿠데타·구출 작전·시해 누명의 위험.

### 조서 신뢰도

```text
EdictCredibility
  = 황제의 실제 재가
  + 정상 상서 기록
  + 인장·절의 진위
  + 조정 관료의 증언
  + 의례·연호의 연속성
  + 전달 사자의 신뢰
  + 수신 세력의 정치적 이익
  - 공개된 강압
  - 위조·중복 조서
  - 보호자의 반복 불복종
  - 조정의 물리적 붕괴
```

수치 하나로 모든 세력에 같은 효과를 주지 않고, 각 `LegitimacyAudience`와 수신 세력의 이해관계가 조서를 별도로 평가한다.

## 9. 황제의 행위 능력

황제는 `PUPPET=true`인 아이템이 아니다.

- 황제는 공개 재가, 거부, 지연, 조건부 승인, 사면, 밀지, 탈출, 후계 지명, 양위 협상을 할 수 있다.
- 궁정 관료와 황실 가족이 독자 파벌·정보망·경호 충성을 가진다.
- 황제가 유저 장수라면 기존 개인턴에서 `imperial.*` capability 명령을 제출한다.
- 황제가 NPC면 Court AI가 동일한 명령과 제약을 사용한다.
- 보호국 사령턴은 황제에게 청원·추천·경호·이동을 제안할 뿐 황제 명령을 직접 위조하지 않는다.
- 위조는 별도 계략 명령이며 발각·문서 대조·사자 증언·인장 진위 위험을 가진다.

새로운 턴 링은 만들지 않는다. 황제 개인턴, 관직자의 개인턴, 보호국 사령턴이 각 권한으로 같은 court domain command를 제출한다.

## 10. 황실 상태와 선택지

| 상태 | 설명 | 주요 위험과 기회 |
|---|---|---|
| `ITINERANT_COURT` | 피난 중인 황제와 분산된 백관 | 구조·호송·약탈·기근·관인 남발 |
| `PROTECTED_COURT` | 보호자가 경비·공급, 황제가 일정 자율성 유지 | 높은 충성 정통성, 보호 비용과 조정 요구 |
| `DOMINATED_COURT` | 보호자가 접근·상서·인장·사자를 장악 | 조서 생산은 쉽지만 강압 노출 시 신뢰 저하 |
| `DIVIDED_COURT` | 황제·관료·인장·문서망이 갈라짐 | 경쟁 조서·구출·내통·정통성 분쟁 |
| `RESTORED_COURT` | 독립 재정·경비·조정 기능이 회복 | 보호자의 자율성 축소, 한실 재통합 가능 |
| `ABOLISHED_OR_TRANSITIONED` | 폐위·시해·양위·새 왕조 성립 | 장기 정통성·종실 처리·의례·연호 재편 |

### 세 방침 이후의 작전·왕조 위기

황제를 확보한 보호자가 고르는 `CourtSettlement` 방침은 `봉대·보정·협제` 세 가지뿐이다. 다음 항목은 네 번째 방침이 아니라, 어느 방침에서도 조건이 쌓이면 별도 명령과 위기 chain으로 발생하는 후속 사건이다.

| 후속 사건 | 분류 | 처리 |
|---|---|---|
| 구출·천도 | 호송 operation | 황제를 다른 도시로 옮기며 조정 기능·인장·문서·가족 분리 위험을 정산 |
| 폐위·시해 | succession crisis | 청중별 반응, 종실 후계, 경쟁 황제 옹립, 시해 책임 claim을 연쇄 처리 |
| 선양·찬탈 | dynasty transition | 조서·인장·제단·작위 상승·백관 동의·종실 처우·새 연호를 장기 workflow로 처리 |
| 한 조정 거부·별도 천명 | legitimacy break | 태평도·자칭 황제·지역 왕조가 기존 조정을 거부하고 독자 정통성을 세우되 외교·내부 반응을 부담 |

## 11. 인장·옥새·부절·문서

### 물품 분류

| 분류 | 예 | 역할 |
|---|---|---|
| `PERSONAL_EQUIPMENT` | 무기·갑옷·방패 | 개인 전투 capability |
| `MOUNT` | 군마·명마·수송 동물 | 이동·전투·연락 capability |
| `KNOWLEDGE_OBJECT` | 병서·지도·율령·호적 | 정보·훈련·행정 행동 |
| `OFFICE_INSTRUMENT` | 관인·절·부절·임명장 | 특정 관직과 명령의 인증 |
| `STATE_REGALIA` | 황실 옥새·종묘 의기·연호 문서 | 왕조 정통성 claim과 조서 의례 |
| `HUMAN_FOLLOWER` | 장인·학자·의관·통역 | 독립 인물·수행원. 장비가 아님 |

### 옥새 원칙

- 옥새는 소유 즉시 황제가 되는 아이템이 아니다.
- 진위·전승·발견 경위·증언이 `authenticityClaims`로 경쟁한다.
- 황제 본인, 조정 관료, 종묘·연호·인사 기구 없이 옥새만 있으면 정통성의 한 근거일 뿐이다.
- 관인·부절은 어느 관직·관할·기간에 유효한지 기록한다.
- 분실·탈취·복제·폐기·재발급은 문서·인사 분쟁을 만든다.
- `인장 + 문서고 + 상서 기구 + 사자망`이 함께 있어야 조서 파이프라인이 정상 작동한다. 이것이 세트 효과를 대신한다.

## 12. 개혁은 연구가 아니라 제도 채택과 확산이다

```text
ReformDefinition
  problem, proposedRule, sponsorRequirements
  institutionRequirements[], facilityRequirements[]
  affectedDuties[], enabledActions[]
  oppositionGroups[], maintenanceCost
  provenance

ReformProposal
  sponsorId, courtOrPolity, targetJurisdictions[]
  draft, debate, approval, pilot

ReformAdoption
  jurisdictionId
  adoptionState
  responsibleOfficeId
  staff, budget, facilities
  compliance, resistance, localVariant
```

### 개혁 수명주기

```text
문제 인식
  → 담당 관직자 또는 장수의 상주
  → 조정·사령부 심의
  → 시범 군현 지정
  → 시설·속관·예산 배치
  → 현지 시행
  → 장부·결과 보고
  → 확대·수정·철회·지역 변형
```

개혁은 전국 boolean이 아니다. 허의 조정에서 법을 공포해도 익주·강동·산지 연맹의 실제 채택에는 사자·관인·속관·지역 협력이 필요하다.

### 개혁 분야

- 호구·추천·관직·문서 행정.
- 율령·재판·감찰·사면.
- 조세·화폐·염철·시장·도량형.
- 농업·수리·둔전·창고·구휼.
- 역참·도로·교량·나루·수송.
- 징발·모집·군호·주둔·군량·장비.
- 학교·의례·율력·종교 공동체.
- 외교·책봉·속국·인질·통행·자치.

국가 성향 이름은 유지하지만 개혁을 하드락하지 않는다. 유가는 학교·추천 개혁의 지지 기반이 강하고, 법가는 호구·율령 집행에 유리하며, 태평도·오두미도·도적은 자기 조직망에 맞는 다른 시행 기구를 사용한다.

## 13. 세력 고유성의 구성

세력 고유성은 `유비 세력 +10%`, `조조 세력 고유 버튼`처럼 지도자 ID에만 붙이지 않는다.

```text
세력 고유성
  = 국가 성향 구성
  + 지도자·핵심 인물의 관계와 야망
  + 보유한 조정·관직·작위 claim
  + 지역 조직망과 자원
  + 시행 중인 개혁
  + 역사·연의 사건 overlay
```

지도자가 죽거나 영토가 바뀌어도 일부 제도와 조직망은 남고, 후계자가 다른 방향으로 개혁할 수 있다. 도적이 한 관직을 받아도 즉시 한식 국가가 되지 않으며, 한식 군벌도 태평도 방·불교 후원망·오두미도 교구를 영토 안에 품을 수 있다.

## 14. 명령 카탈로그

아래 표는 황실·관직 도메인의 projection이다. canonical id·payload·authority policy의 단일 정본은 `docs/superpowers/specs/2026-07-12-v2-command-catalog-and-rollout.md`이며, 이 문서는 lifecycle과 resolver 의미를 정의한다.

### 개인턴

| canonical id | 설명 |
|---|---|
| `personal.office.petition` | 자신·타인을 추천하거나 관직 변경을 표문으로 청원 |
| `personal.office.accept` | 임명을 수락·사양·조건부 수락 |
| `personal.office.assume` | 치소·인장·속관을 인수해 실제 부임 |
| `personal.office.exercise` | resolver가 허용한 범위에서 감찰·재판·추천·문서·군량 등 관직 직무 수행 |
| `personal.office.selfStyle` | 관직·장군호 자칭 claim 생성 |
| `personal.court.audience` | 황제·상서·조정 파벌에 알현·상주 |
| `personal.court.assentEdict` | 황제 본인이 조서 초안을 재가·기각·수정하거나 밀지 intent를 남김 |
| `personal.court.carryEdict` | 조서·관인·부절을 호송·전달 |
| `personal.court.escort` | 황제·황실·백관의 이동을 지휘 |
| `personal.reform.pilot` | 담당 군현에서 개혁을 시범 시행 |

### 사령턴

| canonical id | 설명 |
|---|---|
| `chief.office.nominate` | 국가 차원에서 후보를 조정·자체 정권에 추천 |
| `chief.office.assignOperationalRole` | 실제 국가·도시·전선 역할 위임 |
| `chief.office.challengeClaim` | 경쟁 관직 claim의 정통성과 실효 지배를 다툼 |
| `chief.court.petition` | 황제에게 조서·관직·작위·사면·정벌을 청원 |
| `chief.court.protect` | 경비·공급·접근·사자 정책 설정 |
| `chief.court.relocate` | 황실 이동을 제안하고 호송·수도 복구 계획 수립 |
| `chief.court.proposeEdict` | 조서 초안을 상서·황제에게 제출 |
| `chief.court.dispatchEdict` | 재가된 조서의 사자·경로·호위 계획 제출 |
| `chief.reform.propose` | 개혁안·시범지역·담당관·예산을 제안 |
| `chief.reform.expand` | 검증된 개혁을 다른 군현으로 확대 |
| `chief.reform.repeal` | 제도를 철회하고 잔존 조직·반발을 정산 |

### 전략 domain

| canonical id | 설명 |
|---|---|
| `court.edict.resolve` | 재가·기각·수정·밀지·위조를 확정 |
| `court.edict.dispatch` | 사자·경로·호위를 배치 |
| `court.edict.respond` | 수신 세력의 수락·유보·거부·공개 대응 |
| `office.claim.resolve` | 추천·자칭·경쟁·추인을 판정 |
| `office.jurisdiction.resolve` | 치소·속관·세입·군사에 따른 실권 갱신 |
| `court.succession.resolve` | 후계·폐위·시해·양위·복위 결과 확정 |
| `reform.adoption.resolve` | 시범·확대·저항·지역 변형 결과 확정 |

실시간 전술은 관직명을 직접 검사하지 않는다. 유효한 `OperationalAssignment`와 campaign commission이 어느 formation에 어떤 명령권을 주는지만 확인한다.

## 15. UI 정보 구조

### 인물 관직 카드

```text
한 조정 관직      좌장군             황제 임명 · 수락 · 재임
지방 관직         예주자사           법적 관할: 예주 / 실효 관할: 없음
소속국 역할       동부전선사령관      실제 지휘: 3개 formation
작위              한수정후           식읍 claim: 한수정 / 실제 수납: 미수
추천 이력         A가 표문 → 조정 보류 → 재상 재심 중
경쟁 claim        B의 자칭 예주목     2개 군 실효 지배
```

### 황실 화면

- 황제·황후·후계·종실의 신변과 소재.
- 조정 소재지, 백관·상서·숙위·재정·문서 기능의 상태.
- 황제와 보호국에 각각 충성하는 경비·관료 비율.
- 옥새·관인·절·문서고의 소재와 진위 claim.
- 조서별 기초·재가·봉인·전달·수신·집행 상태.
- 추천·임명·기각·자칭·경쟁 관직 타임라인.
- 한 조정 정통성을 인정하는 세력과 청중별 반응.

`황실 호의 62` 한 줄로 축소하지 않는다. 플레이어는 왜 조서가 먹히거나 무시되는지 확인할 수 있어야 한다.

## 16. 첫 수직 슬라이스

### 시나리오

196년 전후의 피난 조정→허 정착을 축소한 fixture를 만든다.

1. 황제, 소수 백관, 상서 문서망, 궁정 경비, 인장·절, 군량 수송대를 별도 객체로 둔다.
2. 두 군벌이 황제 영접을 제안하고 서로 다른 경로·공급·자율성 조건을 낸다.
3. 황제 AI가 보호 조건을 평가해 이동을 선택한다.
4. 보호자가 한 장수를 지방관으로 추천한다.
5. 조정이 원안 임명·대행 임명·기각 중 하나를 근거와 함께 선택한다.
6. 임명된 장수가 부임하지 못하면 명목 관직으로 남는다.
7. 다른 군벌이 같은 관직을 자칭하고 실제 치소를 장악한다.
8. 황제 조서가 사자를 통해 도착해 양측이 수락·거부·추인을 협상한다.
9. 황제가 밀지를 보내거나 탈출을 시도할 수 있다.
10. replay가 조서·인장·추천·관직·실효 지배의 전체 이력을 재현한다.

### 수용 조건

- 황제 소재 도시 점령만으로 `CourtProtectorate`가 자동 이전되지 않는다.
- 황제·조정·상서·옥새·경비·군량의 소재가 서로 다를 수 있다.
- 조서가 발행되어도 수신 군벌이 거부하고 실제 영토를 유지할 수 있다.
- 거부에는 조정 불복이라는 외교·정통성 비용이 남는다.
- 추천은 원안 수락, 변경 임명, 보류, 기각, 경쟁 후보 임명을 모두 표현한다.
- 자칭 관직과 황제 임명 관직이 동시에 존재하고, 각 세력이 별도로 인정할 수 있다.
- 중앙관직·지방관직·소속국 역할·작위·실제 formation 지휘가 독립적으로 보인다.
- 관직자는 치소·속관·예산·인장 없이 직무 capability를 행사할 수 없다.
- 모든 관직 행동은 claim origin·재직 수락·부임·치소·속관·예산·관인·실무 위임을 확인하는 `OfficeCapabilityResolver`를 통과하며, `OfficeDefinition` 필드만으로 허용되지 않는다.
- 사람인 추종자를 장비 inventory로 이동할 수 없다.
- 개혁은 시범 군현에서 먼저 시행되고 전국에 즉시 적용되지 않는다.

## 17. 근거

### 직접·근접 사료

- [『후한서』 권9 효헌제기](https://zh.wikisource.org/zh-hant/%E5%BE%8C%E6%BC%A2%E6%9B%B8/%E5%8D%B79): 낙양·장안·허의 조정 이동, 헌제 피난, 조정 강압, 밀지, 선양.
- [『후한서』 권74상 원소전](https://zh.wikisource.org/zh-hant/%E5%BE%8C%E6%BC%A2%E6%9B%B8/%E5%8D%B774%E4%B8%8A): 원소 진영의 천자 영접·협천자 논쟁과 부담.
- [『삼국지』 위서1 무제기](https://zh.wikisource.org/wiki/%E4%B8%89%E5%9C%8B%E5%BF%97/%E5%8D%B701): 조조의 영접, 녹상서사, 허 천도, 관직·서열 협상.
- [『삼국지』 위서2 문제기](https://zh.wikisource.org/wiki/%E4%B8%89%E5%9C%8B%E5%BF%97/%E5%8D%B702): 조비의 선양, 인수, 연호·제단·산양공 처리.
- [『후한서』 백관1](https://ctext.org/hou-han-shu/bai-guan-yi/zh): 삼공, 장군, 막부 속관과 임시 원정군 직제.
- [『후한서』 백관3](https://ctext.org/hou-han-shu/bai-guan-san/zhs): 상서·내조·인장·부절·황제 직속 문서 기구.
- [『후한서』 백관4](https://ctext.org/hou-han-shu/bai-guan-si/zh): 궁정·황후부·수도 군직.
- [『후한서』 백관5](https://ctext.org/hou-han-shu/bai-guan-wu/zh): 자사·주목·태수·현령·왕국·후국과 지방 행정.

### 연구·게임 참고

- [Rafe de Crespigny, *To Establish Peace*](https://openresearch-repository.anu.edu.au/bitstreams/fcf6cff2-9d85-4b28-add3-f9b4baddc9fd/download): 189~220 조정과 군벌 정치의 주석 번역.
- [Hans Bielenstein, *The Bureaucracy of Han Times*](https://www.cambridge.org/core/books/bureaucracy-of-han-times/73FB6AD7FF8180CAA518073222E67FA4): 한대 관료제 연구.
- [Rafe de Crespigny, Later Han Local Administration](https://researchportalplus.anu.edu.au/en/publications/an-outline-of-the-local-administration-of-the-later-han-empire/): 후한 지방 행정 구조.
- [토탈 워: 삼국/개혁](https://namu.wiki/w/%ED%86%A0%ED%83%88%20%EC%9B%8C:%20%EC%82%BC%EA%B5%AD/%EA%B0%9C%ED%98%81), [부속 장비](https://namu.wiki/w/%ED%86%A0%ED%83%88%20%EC%9B%8C:%20%EC%82%BC%EA%B5%AD/%EB%B6%80%EC%86%8D%20%EC%9E%A5%EB%B9%84), [세력](https://namu.wiki/w/%ED%86%A0%ED%83%88%20%EC%9B%8C:%20%EC%82%BC%EA%B5%AD/%EC%84%B8%EB%A0%A5), [삼국지/관직](https://namu.wiki/w/%EC%82%BC%EA%B5%AD%EC%A7%80/%EA%B4%80%EC%A7%81): 게임 문법·용어 탐색용. 역사 claim 근거로 단독 사용하지 않는다.

## 18. 자체 검토

- 관직의 추천·기각·자칭·추인·수락·부임·실권을 한 상태로 뭉개지 않았다.
- 중앙관직, 지방관직, 소속 군벌 역할, 장군 commission, 작위를 분리했다.
- 협천자를 황제 소유 boolean이나 전역 보너스로 만들지 않았다.
- 황제와 조정 관료의 독자 행위 능력과 보호자의 정치적 부담을 보존했다.
- 인장·옥새·부절을 능력치 장신구가 아닌 문서·권한 도구로 만들었다.
- 개혁을 즉시 전국 적용되는 기술 트리에서 군현별 제도 확산으로 바꿨다.
- 제자백가와 기존 국가 성향 이름을 변경하지 않고 개혁 지지 기반으로 연결했다.
