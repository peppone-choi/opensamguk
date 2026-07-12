# 오픈삼국 v2 병종·건축물 콘텐츠 카탈로그

> 작성일: 2026-07-13
> 상태: content-budget-adopted, architecture-review-cleared
> 범위: 삼국지 `CLASSIC` 콘텐츠 팩의 병력 편제·전통과 도시 시설·기반망·자원거점 목표 수량
> 비범위: 최종 전투 수치, v1 병종 상성·건축물 패리티 변경

## 1. 콘텐츠 예산 결정

### 병력

- v2 전체 목표는 **72개 curated formation template/tradition**이다.
- 계약을 증명하는 첫 기술 슬라이스는 **4개 formation**만 쓴다. 검증 뒤 첫 공개 roster를 18개 공통 편제와 선별한 역사·지역·연의 편제를 합쳐 **36개**까지 확장한다.
- `경장/중장/정예`를 모두 별도 병종으로 복제하지 않는다. 무기·갑옷·방패·말·훈련·대형·모집원의 조합이 같은 전통의 변형을 만든다.
- 72개 template에서 장비와 대형 조합으로 수백 개의 실제 formation을 만들 수 있다.

### 정착지 콘텐츠

- 물리적 **시설 48개**.
- 여러 장소에 걸친 **기반망 10개**.
- 지도에 원래 존재하는 **자원거점 12개**.
- 시설이 아닌 **정착지·행정 단계 5개**.
- 계약을 증명하는 첫 기술 슬라이스는 시설·자원거점을 합쳐 **3개**만 쓴다. 검증 뒤 첫 공개 roster를 시설·기반망·자원거점 합계 **24개**까지 확장한다.
- 각 시설은 2~4개의 목적 분기를 가질 수 있으므로 전체 목표에서 보이는 선택지는 100개 이상이지만, 10단계 선형 업그레이드는 만들지 않는다.

## 2. 병종과 편제의 계산 단위

```text
Formation
  = RecruitmentSource
  + CommandAttachment
  + MobilityProfile
  + WeaponLoadout
  + ProtectionProfile
  + TrainingAndDoctrine
  + SupplyProfile
  + claimIds[]
```

카탈로그 이름은 이 조합을 쉽게 알아보게 하는 전통·편제명이다. simulation kernel은 `호표기`나 `등갑병` 문자열을 보고 상성을 계산하지 않는다.

## 3. 72개 formation template/tradition

### 3.1 공통 편제 18개

이 편제는 전국 공용 완제품이 아니라 장비와 모집원을 바꾸어 지역·정권별로 구성하는 기본 틀이다.

| # | 이름 | 역할 | 주요 조합 축 |
|---:|---|---|---|
| 1 | 향리 경비대 | 도시 치안·초기 수비 | 지역 인력, 혼합 무장, 낮은 원정 지속력 |
| 2 | 징발 창병 | 값싼 대열·기병 저지 | 징발, 창, 낮은 훈련 |
| 3 | 징발 도검병 | 근거리 전투·주둔 | 징발, 도검, 경방호 |
| 4 | 방패 도검병 | 밀집 근접 방어 | 방패, 도검, 대열 훈련 |
| 5 | 방패 창병 | 전면 방어·기병 대응 | 방패, 창, brace |
| 6 | 극병 | 혼합 거리 근접전 | 극·장병, 대열 |
| 7 | 장창병 | 밀집 대형·접근 거부 | 장창, 깊은 대열, 측면 취약 |
| 8 | 경갑 돌격보병 | 측후면 충격·험지 | 경갑, 도끼·장병, 높은 피로 위험 |
| 9 | 중갑 보병 | 대열 유지·거점 돌파 | 중갑, 높은 군량·장비 부담 |
| 10 | 궁수대 | 곡사·지속 사격 | 활, 탄약, 시야 |
| 11 | 노수대 | 방호 관통·일제사격 | 노, 느린 재장전, 대열 |
| 12 | 투사·산병대 | 정찰·교란·험지 | 투척무기, 개방 대형, 낮은 지속전 |
| 13 | 기마궁사대 | 원거리 기동·추격 | 말, 활, 말 사료 |
| 14 | 경기병대 | 정찰·추격·측면 | 경장 말, 창·도, 낮은 정면 유지력 |
| 15 | 충격기병대 | 돌파·사기 충격 | 창, 돌격 대형, 회복 공간 |
| 16 | 중장기병대 | 정예 돌파·호위 | 중갑 인마, 높은 유지·지형 제약 |
| 17 | 공병대 | 야전 공사·장애물·공성 | 도구, 기술자, 노동·목재 |
| 18 | 수송호위대 | 군량·장비·부상자 호송 | 수레·짐승·호위, route capacity |

`투사·산병대`의 구체 무장은 지역·사료에 맞춰 투창·노·활·석궁 등 loadout으로 정한다. 하나의 전국 공용 투창병을 역사 사실로 확정하지 않는다.

### 3.2 실명 부대·지휘기관 기반 편제 전통 22개

| # | 이름 | 출처 등급 | 가용 조건과 역할 |
|---:|---|---|---|
| 19 | 둔기교위 휘하 기병대 | `SCHOLARLY_RECONSTRUCTION` | 사료상 둔기교위 command에 기병 loadout을 붙인 제한 편제 |
| 20 | 월기교위 휘하 기병대 | `SCHOLARLY_RECONSTRUCTION` | 사료상 월기교위 command에 기병 loadout을 붙인 제한 편제 |
| 21 | 보병교위 휘하 보병대 | `SCHOLARLY_RECONSTRUCTION` | 사료상 보병교위 command에 보병 loadout을 붙인 제한 편제 |
| 22 | 장수교위 휘하 호기대 | `SCHOLARLY_RECONSTRUCTION` | 사료상 장수교위 command와 변경·호기 맥락을 조합한 제한 편제 |
| 23 | 사성교위 휘하 사수대 | `SCHOLARLY_RECONSTRUCTION` | 사료상 사성교위 command에 사격 loadout을 붙인 제한 편제 |
| 24 | 호분 | `PRIMARY_ATTESTED` | 황실 숙위, 궁정 경비 |
| 25 | 우림 | `PRIMARY_ATTESTED` | 황실 숙위·호위 |
| 26 | 서원군 | `PRIMARY_ATTESTED` | 188년 전후 수도 비상 지휘체계 |
| 27 | 무기교위 변경군 | `PRIMARY_ATTESTED` | 서역 변경 주둔·둔전, 시기·지역 제한 |
| 28 | 오환돌기 | `PRIMARY_ATTESTED` | 북방 보조 기병, 동맹·모집 관계 필요 |
| 29 | 백마의종 | `PRIMARY_ATTESTED` | 공손찬·유주·백마 기마 수행 집단 |
| 30 | 청주병 | `PRIMARY_ATTESTED` | 조조가 청주 황건 항졸을 선발·재편 |
| 31 | 호표기 | `PRIMARY_ATTESTED` | 조씨 핵심 지휘망의 제한 정예 기병 |
| 32 | 선등 | `PRIMARY_ATTESTED` | 국의·원소 진영의 선봉 돌격·노병 맥락 |
| 33 | 대극사 | `PRIMARY_ATTESTED` | 원소의 대극 호위 병력 |
| 34 | 해번병 | `PRIMARY_ATTESTED` | 손권·손오의 특수 호위·파견 병력 |
| 35 | 감사대 | `PRIMARY_ATTESTED` | 손오의 결사 공격 병력 |
| 36 | 요장·장하병 | `PRIMARY_ATTESTED` | 손권 개인 군사·장막 호위 |
| 37 | 무난독 소속 | `PRIMARY_ATTESTED` | 손오 후반 특정 command, 규모·편제 과장 금지 |
| 38 | 단양병 | `SCHOLARLY_RECONSTRUCTION` | 서주·단양 지역 모집 전통, 후대 주석·개별 전승의 거리를 표시 |
| 39 | 함진영 | `SCHOLARLY_RECONSTRUCTION` | 고순의 소규모 정예 돌격부대, 후대 기록의 거리를 표시 |
| 40 | 백이·백모병 | `SCHOLARLY_RECONSTRUCTION` | 유비·진도·백제/영안 맥락의 숙련 호위, 주장별 출처를 분리 |

북군 오교는 다섯 전국 공용 병종이 아니라 수도 기관의 다섯 command다. `둔기교위` 같은 이름 자체는 `OfficeDefinition/CommandAttachment`로 저장하고, 19~23번은 그 command에 실제 loadout을 붙여 생성한 제한적 formation template이다. 편제의 무장·인원은 사료가 허용하는 범위만 확정하며, 관직이 곧 병종이라는 뜻으로 읽지 않는다.

### 3.3 지역·보조군 모집 전통 12개

| # | 이름 | 출처 등급 | 모델링 |
|---:|---|---|---|
| 41 | 강호·제융 보조군 | `SCHOLARLY_RECONSTRUCTION` | 양·옹 변경 동맹과 보조 기병 풀 |
| 42 | 흉노 보조 기병 | `SCHOLARLY_RECONSTRUCTION` | 남흉노·변경 정치 관계와 말 공급 필요 |
| 43 | 선비 보조 기병 | `SCHOLARLY_RECONSTRUCTION` | 북방 외교·인질·교역·고용 조건 |
| 44 | 산월 병원 | `SCHOLARLY_RECONSTRUCTION` | 단양·강동의 산지 집단 모집·복속·동맹 |
| 45 | 남중 부족병 | `SCHOLARLY_RECONSTRUCTION` | 남중 집단별 무장·지형 전통, 하나의 남만 종족병 금지 |
| 46 | 파인·판순 계열 | `SCHOLARLY_RECONSTRUCTION` | 파군·한중 지역 집단과 방패·보조군 전통 |
| 47 | 양주 군마 전통 | `SCHOLARLY_RECONSTRUCTION` | 목축·시장·강호 동맹을 통한 기병 기반 |
| 48 | 유주 변경 기마 | `SCHOLARLY_RECONSTRUCTION` | 유주 말·변경 경계·오환 관계 기반 |
| 49 | 형주 수륙병 | `SCHOLARLY_RECONSTRUCTION` | 강·호수·육로를 오가는 혼합 주둔 전통 |
| 50 | 강동 수군 | `SCHOLARLY_RECONSTRUCTION` | 선박·수군 숙련·강변 거점 기반 |
| 51 | 익주 산악 주둔병 | `SCHOLARLY_RECONSTRUCTION` | 잔도·협곡·관문 방위와 산악 보급 |
| 52 | 서역 변경 둔전병 | `SCHOLARLY_RECONSTRUCTION` | 변경 주둔·농업·수송을 결합한 모집원 |

이름은 모집 pool과 전통을 뜻한다. `강호`·`산월`·`남중`을 단일 문화·단일 병종으로 평면화하지 않는다.

### 3.4 공성·수군·군수 편제 10개

| # | 이름 | 출처 등급 | 역할 |
|---:|---|---|---|
| 53 | 벽력거·발석거 운용대 | `SCHOLARLY_RECONSTRUCTION` | 투석 장비 제작·운용·탄약 수송, 명칭·시기별 claim 분리 |
| 54 | 충차 운용대 | `SCHOLARLY_RECONSTRUCTION` | 성문·목책 파괴, 목재·보호 구조 필요 |
| 55 | 누차·공성탑 운용대 | `SCHOLARLY_RECONSTRUCTION` | 성벽 접근, 조립·이동·화재 위험 |
| 56 | 굴착 공병대 | `SCHOLARLY_RECONSTRUCTION` | 갱도·성벽 기초 공격·대응 굴착 |
| 57 | 교량·부교 공병대 | `SCHOLARLY_RECONSTRUCTION` | 도하·도로 복구·수송 capacity |
| 58 | 둔전 수비대 | `SCHOLARLY_RECONSTRUCTION` | 생산 거점 방위와 군량 운영 |
| 59 | 군량 수송대 | `PRIMARY_ATTESTED` | 곡물·사료·급료 수송과 호송 |
| 60 | 수레 호송대 | `SCHOLARLY_RECONSTRUCTION` | 장비·부상자·공성 부품 수송 |
| 61 | 하천 수송선단 | `SCHOLARLY_RECONSTRUCTION` | 군량·병력의 강·운하 이동 |
| 62 | 전투 수군 선단 | `PRIMARY_ATTESTED` | 선박 formation, 승조원·육전대·화공 분리 |

공성 장비는 병사를 소모해 즉시 나타나는 병종이 아니다. 공방·목재·철·기술자·수송 경로를 통해 제작하고 formation에 배속한다.

### 3.5 `CLASSIC` 연의·전승·게임 참고 전통 10개

| # | 이름 | 출처 등급 | 사용 원칙 |
|---:|---|---|---|
| 63 | 등갑병 | `ROMANCE_ATTESTED` | 등나무 방호·습지/숲 적응·가연성 capability. 엄격 고증에서 비활성 |
| 64 | 전상대 | `ROMANCE_ATTESTED` | 대표 claim은 연의 등장. `game.elephant-panic-balance` claim을 별도 연결해 코끼리 자원·공황·보급 수치를 분리 |
| 65 | 맹수몰이대 | `ROMANCE_ATTESTED` | 짐승에 의한 교란. 통제 실패·화염·소음 위험 |
| 66 | 남중 화염전사 | `GAME_REFERENCE` | 축융 연의 flavor, 판타지 강도 명시 |
| 67 | 독천 협곡병 | `ROMANCE_ATTESTED` | 독성 지형·매복·보급 위험을 event/terrain으로 구현 |
| 68 | 목우유마 수송대 | `ROMANCE_ATTESTED` | 대표 claim은 연의 전승. `history.wooden-ox-transport-reconstruction` claim을 별도 연결하고 초자연 성능 금지 |
| 69 | 연노 운용대 | `SCHOLARLY_RECONSTRUCTION` | 반복노 실재와 제갈량 발명·대규모 운용 전승을 서로 다른 claim으로 분리 |
| 70 | 비웅군 | `ROMANCE_ATTESTED` | 동탁 연의 병력. 역사 roster에서는 금지 |
| 71 | 금범 유격선단 | `GAME_REFERENCE` | 감녕의 금범·수행 집단을 정규 군단으로 과장하지 않음 |
| 72 | 무당비군 | `GAME_REFERENCE` | 사료의 `無當監`과 후대·대중 병종명을 분리 표시 |

`CHRONICLE`은 63~72를 자동 비활성화하거나 근거가 허용하는 일반 capability로 치환한다. `CLASSIC`은 출처 배지를 표시한 채 사용한다.

## 4. 정착지·건축물 정본 분리

```text
SettlementStatus   촌락·현치·군치·수도 같은 규모·행정 상태
Facility           한 위치의 물리 시설
InfrastructureNetwork  여러 위치를 연결하는 도로·수리·역참·봉수망
ResourceSite       광산·염장·농경지·목장처럼 지도에 선재하는 생산 거점
ResourceNode       Facility·Place·Formation·IN_TRANSIT에 귀속된 실제 재고 ledger
Policy             세율·시장 통제·징발·구휼. 건물이 아님
OfficeSeat         태수부·현관아가 수행하는 관직 치소 기능
```

토탈 워의 주정착지 등급·건물 슬롯·자원 정착지·정책을 한 `Building` 테이블에 섞지 않는다. 현창·군량고는 물리 `Facility`, 그 안의 실제 곡물은 연결된 `ResourceNode`가 소유한다.

```text
FacilityState
  facilityId, condition, damage, maintenanceDebt
  staffAssignments[], officeAssignmentIds[]
  linkedResourceNodeIds[], linkedRouteIds[]
  activeProjects[], lastInspectedGameDate

FacilityCapabilityResolver
  input: FacilityTemplate requirements
       + FacilityState condition/damage/maintenance
       + qualified staff and office assignment
       + linked ResourceNode stock/reservation
       + route capacity/control/season
  output: ALLOW(capability, scope, capacity)
        | DENY(reason, missingEvidence[])
```

시설 행의 `새로 여는 능력`은 presence-based grant가 아니다. command handler와 AI는 반드시 `FacilityCapabilityResolver` 결과를 읽고, 재고·인력·경로·유지보수 중 필요한 조건이 빠지면 능력을 닫거나 capacity를 줄인다.

## 5. 정착지·행정 단계 5개

다음은 건축물 수에 포함하지 않는다.

1. 촌락·리 집락.
2. 장시·진.
3. 현 치소.
4. 군·국 치소.
5. 수도·황실 조정 소재지.

인구가 늘었다고 자동으로 군치나 수도가 되지 않는다. 조정·관직·성곽·창고·교통·정치 결정이 필요하다.

## 6. 물리 시설 48개

### 6.1 행정·조정 10개

| # | 시설 | 새로 여는 능력 |
|---:|---|---|
| 1 | 현 관아 | 호구·재판·부역·현창 명령 |
| 2 | 군 태수부 | 여러 현의 세입·군무·감찰 조정 |
| 3 | 주 자사·주목부 | 순찰 감찰 또는 주 단위 군정. 시기별 차등 |
| 4 | 상서·조정 문서부 | 조서·상주·인사 문서 workflow |
| 5 | 문서·인장소 | 관인·인수·호적·조약의 인증과 보관 |
| 6 | 호적·회계소 | 인구·세입·군량 장부 정확도와 감사 |
| 7 | 재판소 | 송사·형벌·조약 위반 판정 |
| 8 | 감옥 | 구금·포로·인질. 유지·탈옥·학대 위험 |
| 9 | 세무소 | 세율·체납·현물 수납·운송 배정 |
| 10 | 객관·사절관 | 사절·통역·인질·외교 회담 |

### 6.2 농업·창고·시장 8개

| # | 시설 | 새로 여는 능력 |
|---:|---|---|
| 11 | 현창·공창 | 현지 곡물 저장·대여·출납 |
| 12 | 군창·군량고 | 다도시 비축·전방 수송 예약 |
| 13 | 구휼 창고 | 기근·유민 때 실제 비축 방출. 학술 복원 배지 |
| 14 | 둔전 치소 | 둔전 인력·경작·군량·수비 관리 |
| 15 | 관영 시장 | 거래 규칙·가격·세금·도량형 통제 |
| 16 | 사영 공방 지구 | 장인·상인 생산과 민간 주문 |
| 17 | 국영 공방 | 국가 도구·공공 공사·군수품 생산 |
| 18 | 화폐 주조소 | 금속·주형·신뢰를 사용한 화폐 발행과 위조 대응 |

### 6.3 군사·산업 10개

| # | 시설 | 새로 여는 능력 |
|---:|---|---|
| 19 | 병기고 | 무기·갑옷 재고·관인화된 지급·회수 |
| 20 | 무기 제작소 | 도검·창·극·노·공구 생산 |
| 21 | 갑옷 제작소 | 천·피혁·찰갑·등갑 등 방호 생산 |
| 22 | 수레·수송 공방 | 수레·마구·짐틀·부품 생산 |
| 23 | 공성 공방 | 충차·누차·발석 장비 조립·수리 |
| 24 | 조선소 | 수송선·전선·화공선 제작·정비 |
| 25 | 연병장 | 대형·신호·무장별 훈련 |
| 26 | 모집·징발소 | 모집원 등록·집결·장비 배정 |
| 27 | 마구간·역마소 | 군마·전령마·회복·사료 관리 |
| 28 | 의관·약초소 | 병자·부상자·장수 치료와 방역 |

### 6.4 방어·요새 8개

| # | 시설 | 새로 여는 능력 |
|---:|---|---|
| 29 | 성벽 | 실제 방어선·주둔 공간·유지보수 |
| 30 | 성문·문루 | 출입 통제·문 방어·검문·통행세 |
| 31 | 해자·수방 | 공성 지연·배수·화재·수위 관리 |
| 32 | 독립 요새 | 통행로·전방 거점의 주둔·창고 |
| 33 | 관문 | 협곡·도로의 choke point와 검문 |
| 34 | 봉수대 | 경보·신호·시야. 네트워크 연결 필요 |
| 35 | 하천 책·쇄강 | 수로 봉쇄·선박 통제·화공 대비 |
| 36 | 민간 대피소 | 공성·화재·재난 때 인구 손실 감소와 식량 부담 |

### 6.5 문화·국가 성향·지역 조직 12개

| # | 시설 | 새로 여는 능력 |
|---:|---|---|
| 37 | 학교 | 경학·문서·관료 후보·천거 |
| 38 | 사당·향리 제사소 | 지역 의례·가문·주민 관계 |
| 39 | 태묘·사직 | 왕조 의례·조정 연속성. 수도 전용 |
| 40 | 태평도 집회·치병소 | 포교·자복·치병·방 조직 |
| 41 | 오두미도 제사주 치소 | 교구 행정·신도 등록·제사주 직책 |
| 42 | 의사·의미육 | 여행자 구호·곡물·고기 출납 |
| 43 | 불교 역경·교단소 | 객승·역경·상인·구호 네트워크. 시기 제한 |
| 44 | 도적 산채 | 산지 주둔·은닉 창고·연맹 집결 |
| 45 | 장물 시장 | 약탈품 처분·정보·밀수·통행 협상 |
| 46 | 묵가 공병당 | `CLASSIC` 수성·절용 기술단과 공사 파견 |
| 47 | 율력 관측소 | 천문·역법·수문·기상 기록과 계절 자문 |
| 48 | 외교 문서관 | 조약·인질·책봉·계승 claim 기록 |

시설 40~48은 국가 성향 전용 보너스 건물이 아니다. 해당 조직망·인물·지역·연도·개혁이 있을 때 기존 건물을 전환하거나 함께 사용할 수 있는 institution seat다.

## 7. 기반망 10개

1. 관개·수로·제방망.
2. 도로·교량망.
3. 역참·전령망.
4. 군창·전방 군량고·수송로 보급망.
5. 봉수·초소 경보망.
6. 나루·부두·하천 수송망.
7. 배수·방화·우물 도시 안전망.
8. 태평도 `方` 연락망.
9. 오두미도 교구·의사망.
10. 도적 산길·산채·장물 연락망.

기반망은 한 도시 슬롯을 점유하는 건물 하나가 아니다. 구간별 capacity·손상·통제·계절·유지 담당 관직을 가진다.

## 8. 자원거점 12개

1. 북방 조·기장·밀 농경지.
2. 남방 논·습지 농경지.
3. 목장·군마 산지.
4. 산림·죽재 거점.
5. 철광·제철 거점.
6. 동광·주조 거점.
7. 염정·염장.
8. 어장·호수·하천 어업.
9. 뽕밭·양잠 거점.
10. 약초·의약 산지.
11. 석재·점토·와요 거점.
12. 옻·목칠·특수 공예 산지.

자원거점은 건설해서 무에서 만드는 건물이 아니다. 점유·협약·노동·수송·환경 조건으로 생산량과 접근성이 바뀐다.

## 9. 시설 분기 원칙

시설의 상위 단계는 항상 더 좋은 같은 건물이 아니다.

| 기초 시설 | 분기 예시 | 선택의 대가 |
|---|---|---|
| 현창 | 세입 창고 / 구휼 창고 / 군량 집산고 | 세입 정확도 / 민중 지지 / 전선 보급 중 우선순위 |
| 관아 | 호구·감찰 / 재판·치안 / 문서·인사 | 속관과 예산·지역 반발이 다름 |
| 국영 공방 | 농기구 / 무기 / 수레·공성 | 생산 설비·장인·원료 전환 비용 |
| 연병장 | 주둔 수비 / 야전 대형 / 기병·궁노 전문 | 모집원과 장비·부지 요구가 다름 |
| 조선소 | 수송선 / 전투선 / 화공 준비 | 적재량·전투력·화재 위험의 교환 |
| 학교 | 경학·천거 / 율령·문서 / 군정 참모 | 어떤 관료 pool을 키우는지 달라짐 |
| 도적 산채 | 은닉·피난 / 통행세 / 대규모 집결 | 은밀성·수입·발각 위험의 교환 |

건설·철거 반복으로 순간 보너스를 먹는 플레이를 막기 위해 시설은 장인·속관·조직망·재고·문서 이력을 가진다. 전환하면 이 자산 일부를 잃거나 반발 세력이 생긴다.

## 10. 국가 성향·관직·개혁과의 연결

- 유가는 학교를 보유한다고 자동 강화되지 않는다. 교관·학생·추천 관직·실제 천거가 있어야 한다.
- 법가는 재판소·호적소·감찰 직책과 일관된 집행이 있어야 한다.
- 병가는 연병장·병기고·군량고·전선 사령 assignment를 연결한다.
- 묵가는 공병당·봉수망·대피소를 상호 방위 정책으로 묶는다.
- 태평도는 타국 도시에도 `方` network를 만들 수 있으며 시설은 발각·탄압될 수 있다.
- 도적은 도시 없이 산채·산길·장물 시장으로 존속하고, 귀순 뒤 관문·역참·주둔군으로 전환할 수 있다.
- 오두미도는 제사주 치소와 의사망이 한식 관아를 대체하거나 병존한다.
- 황실은 태묘·사직·상서 문서부·관인·역참·궁정 경비가 연결되어야 정상 조서를 발행한다.

개혁은 시설을 `해금`하는 지식 버튼이 아니라 그 시설을 운영할 관직·법·예산·속관을 채택하고 군현별로 확산하는 과정이다.

## 11. 토탈 워: 삼국에서 가져올 문법

- 제한된 도시 공간과 서로 다른 전문화.
- 주정착지와 소규모 자원 거점의 역할 분리.
- 건물 분기가 주둔군·모집·행정·공급 행동을 바꾸는 구조.
- 지역·세력·인물 조건을 모두 만족해야 하는 희소 부대.
- 부대 경험과 장수 수행원 관계의 지속.
- 무기·갑옷·말·방패·탄약·피로·사기·대형의 분리.
- 개혁이 건물·외교·관직·부대에 연결되는 구조.

가져오지 않을 것은 10단계 도시, 오행 하드락, 정확한 수치, 창작 병종의 역사화, 전역 퍼센트 버프, 랜덤 고유 아이템 생성이다.

## 12. 구현 순서

### C0: 데이터 계약

- `FormationTemplate`, `RecruitmentSource`, `EquipmentProfile`, `EvidenceRef`, `HistoricalClaim`, `ContentEntry`.
- `SettlementStatus`, `Facility`, `FacilityState`, `InfrastructureNetwork`, `ResourceSite`, `ResourceNode`.
- `FacilityCapabilityResolver`와 claim→evidence 참조 무결성.
- content profile validation과 provenance badge.

### C1: 3개 정착지 항목 기술 증명

- 후방 곡창 Facility+ResourceNode, 전방 군량고 Facility+ResourceNode, 농경지 ResourceSite만 구현한다. 시설과 재고는 한 행에 보이더라도 별도 객체다.
- 도시 사이 route 1개에서 실제 재고·예약·수송·소비를 검증한다.

### C2: 4개 formation 기술 증명

- 징발 창병, 노수대, 경기병대, 수송호위대만 구현한다.
- 한 번의 이동·교전·재보급에서 인원·장비·군량 보존과 replay를 검증한다.

### C2.1: 4/3 proof claim map

| content entry | claim id | evidence class | EvidenceRef | 검증할 주장 |
|---|---|---|---|---|
| `formation.conscript_spear` | `claim.mobilized-infantry-template` | `SCHOLARLY_RECONSTRUCTION` | `hhs-118-local-administration` | 군현 동원 인력에 창 loadout을 붙인 일반 편제 |
| `formation.crossbow` | `claim.crossbow-formation-template` | `SCHOLARLY_RECONSTRUCTION` | `hhs-117-armory-and-northern-army` | 노·탄약·대열을 분리한 사격 편제 |
| `formation.light_cavalry` | `claim.frontier-light-cavalry-template` | `SCHOLARLY_RECONSTRUCTION` | `decrespigny-northern-frontier` | 말·기수·사료·기동을 묶은 변경 기병 편제 |
| `formation.transport_escort` | `claim.military-transport-escort` | `SCHOLARLY_RECONSTRUCTION` | `sgz-16-renjun-supply` | 군량 수송대와 호위를 하나의 convoy formation으로 표현 |
| `facility.rear_granary` | `claim.county-granary-storage` | `PRIMARY_ATTESTED` | `hhs-118-county-granary` | 현창의 물리 저장 기능과 관리 주체 |
| `facility.forward_granary` | `claim.forward-supply-depot` | `SCHOLARLY_RECONSTRUCTION` | `sgz-16-renjun-supply` | 전방 군량 집산·호송·소비 capacity |
| `resource.grain_field` | `claim.tuntian-production-site` | `PRIMARY_ATTESTED` | `sgz-1-tuntian` | 둔전·농경 생산이 위치와 수송 조건을 가짐 |

이 7개 entry의 `claimIds[]`와 `EvidenceRef`가 하나라도 끊기면 C2를 통과하지 않는다. 나머지 72/48/10/12 항목도 활성화 전에 같은 claim map을 가져야 하며, 현재 표의 출처 등급만으로 production content가 되지 않는다.

### C3: 첫 공개 36/24 roster

- 검증된 계약 위에 formation 36개와 시설·기반망·자원거점 합계 24개를 단계적으로 연다.
- 항목을 한꺼번에 활성화하지 않고 provenance·모집·수송 fixture가 있는 항목만 공개한다.

### C4: 정체성·황실 확장

- 태평도 방, 도적 산채, 오두미도 제사주, 유가 학교·추천.
- 상서·인장·역참·조서와 중앙/지방 관직.
- 시설 전환·개혁 확산·점령 후 제도 병존.

### C5: 전체 72/48/10/12 카탈로그

- 근거와 테스트가 준비된 family만 추가한다.
- template 개수를 채우기 위해 이름만 다른 병종·건물을 만들지 않는다.

## 13. 수용 조건

- 72개 병력 항목마다 모집원·지휘·이동·무장·방호·보급·출처가 있다.
- 실명 병종은 시기·소유자·지역·사건 제약 없이 전국 모집되지 않는다.
- `CHRONICLE`에서 연의·게임 참고 병종이 역사 사실로 활성화되지 않는다.
- 시설, 기반망, 자원거점, 정착지 단계, 정책이 서로 다른 타입이다.
- 건물이 단순 퍼센트 버프가 아니라 명령·저장·생산·모집·경로·주둔 기능을 연다.
- 도시가 없는 도적 연맹과 타국 도시의 태평도 방을 표현할 수 있다.
- 장비·공성기·선박은 생산·재고·수송 없이 생성되지 않는다.
- 같은 content data로 AI와 플레이어가 동일한 모집·건설 조건을 평가한다.

## 14. 근거

- [『후한서』 백관4](https://ctext.org/hou-han-shu/bai-guan-si/zh): 북군 오교·궁정 숙위.
- [『후한서』 백관5](https://ctext.org/hou-han-shu/bai-guan-wu/zh): 군현·창고·농업·지방 관직.
- [『삼국지』 위서1](https://zh.wikisource.org/wiki/%E4%B8%89%E5%9C%8B%E5%BF%97/%E5%8D%B701): 청주병·둔전.
- [『삼국지』 위서9 조인·조순](https://ctext.org/text.pl?if=gb&node=602347): 호표기.
- [『삼국지』 위서6 원소](https://ctext.org/text.pl?if=gb&node=602234): 선등·대극사.
- [『삼국지』 오서10](https://zh.wikisource.org/zh-hant/%E4%B8%89%E5%9C%8B%E5%BF%97/%E5%8D%B755): 해번병·감사대와 감녕 기록.
- [『삼국지연의』](https://ctext.org/sanguo-yanyi/zh): 등갑병·남중 연의 콘텐츠.
- [토탈 워: 삼국/부대](https://namu.wiki/w/%ED%86%A0%ED%83%88%20%EC%9B%8C:%20%EC%82%BC%EA%B5%AD/%EB%B6%80%EB%8C%80), [건축물](https://namu.wiki/w/%ED%86%A0%ED%83%88%20%EC%9B%8C:%20%EC%82%BC%EA%B5%AD/%EA%B1%B4%EC%B6%95%EB%AC%BC), [개혁](https://namu.wiki/w/%ED%86%A0%ED%83%88%20%EC%9B%8C:%20%EC%82%BC%EA%B5%AD/%EA%B0%9C%ED%98%81): 게임 문법 참고.

## 15. 자체 검토

- 사용자가 요청한 병종·건축물의 목표 개수를 명시했다.
- 병종 수를 장비 단계별 복제품으로 부풀리지 않았다.
- 정사·학술 복원·연의·게임 참고를 같은 역사 등급으로 섞지 않았다.
- 도시 등급, 정책, 시설, 기반망, 자원거점을 분리했다.
- 국가 성향·관직·황실이 실제 시설과 조직망을 통해 작동한다.
