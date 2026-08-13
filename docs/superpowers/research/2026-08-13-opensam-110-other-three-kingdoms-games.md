# OPENSAM-110 타 삼국지 게임 시스템 조사

- 조사 기준일: 2026-08-13 (Asia/Seoul)
- 상태: `DONE_WITH_CONCERNS`
- 추적: [GitHub #253](https://github.com/peppone-choi/opensamguk/issues/253), OPENSAM-107 하위 작업
- 범위: 코에이 외 삼국지 게임의 공개 시스템 표면, 멀티 적합성, 결정적·오프라인 실행 근거, memory-centric CQRS 도입 비용
- 비범위: 구매, 계정 생성, 비공개 빌드·파일 조사, 코드 도입, 구현 승인, 라이브 서비스 변경

## 1. 결론

가장 직접적인 조사 대상인 **삼국지 클래식(Three Kingdoms Classic)** 은 공식적으로
single-player만 표시된 턴제 대전략이다. 따라서 관전 모드를 현재 오픈삼국 SSE의
"멀티플레이어 관전자" 선례로 부르면 안 된다. 대신 다음 네 가지는 후속 설계 후보로
가치가 있다.

1. 관전자에게 동일한 내부 상태를 그대로 노출하지 않고, 속도·생략·요약·세력 정보·기록을
   별도 presentation으로 제공하는 방식
2. 도시 생산부터 전선 수송, 도착 예상 시간, 군의 잔여 지속 기간, 보급 교란, 포위·기근까지
   이어지는 **단계적 보급 상태**
3. AI 위임 진행을 하단 패널과 전투·공격 진행으로 설명하는 방식
4. 저장과 replay를 구분하고 replay를 local-only 파일로 취급하는 명시적 보존 정책

즉시 구현을 추천하지 않는다. 오픈삼국에서는 v1 PHP 패러티를 건드리지 않는 v2 additive
계약으로 다시 설계해야 하며, 공개 자료는 삼국지 클래식의 RNG seed, replay 재현 방식,
저장 스키마, 서버 권위 모델을 설명하지 않는다.

기타 게임 중에는 **Late Eastern Han Dynasty**가 결정적 authoritative engine의 가장 가까운
기술 참고이고, **Sanguo's Ambition 4**가 bounded multiplayer world의 가장 가까운 상품 참고다.
두 게임 모두 그대로 채택할 수는 없다. 전자는 multiplayer와 durable persistence가 없고,
후자는 deterministic replay와 서버·저장 구조가 공개되지 않았다.

## 2. 조사 방법과 판정 언어

### 2.1 evidence 경계

- `[사실]`: 개발자·배급사 공식 사이트, 공식 Steam store/news, 개발자가 운영하는 공개
  source repository에서 직접 확인한 내용이다.
- `[추론]`: 위 사실을 오픈삼국의 서버 권위, 예약턴, replay, CQRS 구조에 대입한 해석이다.
- `UNKNOWN`: 공개 1차 자료가 주장하지 않는 내용이다. single-player 표시는 offline 보장이
  아니고, replay 제공은 seeded determinism의 증거가 아니다.
- user review, community guide, 댓글, 제3자 영상은 후보 발견 외에는 규칙 근거로 쓰지 않았다.
  공식 launch trailer는 store 설명을 시각적으로 재확인할 뿐, 텍스트에 없는 규칙의 근거로
  사용하지 않았다.
- 모든 URL은 로그인·구매 없이 공개 접근했다. 원문, 표, 이미지 또는 game data를 저장소에
  복제하지 않고 짧은 사실 요약과 링크만 남긴다.

### 2.2 도입 판정

| 판정 | 의미 |
|---|---|
| `FOLLOW-UP` | 아이디어를 별도 계약·티켓에서 검증할 가치가 있다. 구현 승인 아님 |
| `DEFER` | 근거 또는 선행 계약이 부족해 지금은 설계·구현하지 않는다 |
| `REJECT` | 현재 형태는 오픈삼국의 제품·권위·결정론 경계와 충돌한다 |

### 2.3 비용 기준

| CQRS 비용 | 의미 |
|---|---|
| 낮음 | 기존 read model 또는 presentation projection에 필드·필터를 추가하는 수준 |
| 중간 | 새 versioned state/delta와 flush·rehydrate·read projection이 필요 |
| 높음 | 새 runtime cadence, 동기화 모델 또는 외부 권위와 기존 daemon 계약을 함께 바꿔야 함 |

## 3. 삼국지 클래식 — 현재 공개 시스템

### 3.1 제품·시나리오·명령 표면

- `[사실]` SEEAT가 개발·배급했고 Steam 출시일은 2026-07-02다. Steam 기능 표시는
  `Single-player`, Steam Achievements, Steam Cloud, Family Sharing이며 multiplayer/co-op/PvP는
  표시하지 않는다. [공식 Steam store](https://store.steampowered.com/app/4818580/?l=english)
- `[사실]` 공식 사이트는 제품을 single-player turn-based grand strategy로 설명하고,
  189 반동탁연합부터 263 촉한 멸망까지 아홉 historical scenario를 열거한다.
  [SEEAT 공식 사이트](https://threekingdoms-classic.web.app/)
- `[사실]` 도시는 인구·금·군량·병력·치안·경제·농업·병참을 가진다. 장수는 역할,
  성격, 특성, 충성, 관계, 이력과 업적을 가지며, 외교에는 휴전·동맹·공동 침공·원조·
  등용·포로·종속이 포함된다. 전투는 야전·회전·공성·관문·산악 등으로 나뉜다.
  [공식 Steam store](https://store.steampowered.com/app/4818580/?l=english)
- `[사실]` 공식 controls에는 `Military`, `Personnel`, `Diplomacy`, `Domestic`, `Schemes`,
  `Research`, `Governance`, `Intelligence`, `Strategic Advice`가 있고, 전투 명령 예로
  돌격·방어·매복·보급 습격이 있다. Space는 월/턴 진행, Enter/Space는 전투 presentation
  생략, Shift+overlay는 공격·원군·보급 화살표를 포함한다.
  [SEEAT 공식 controls](https://threekingdoms-classic.web.app/en/controls/)

공식 사이트가 표시하는 launch date 2026-07-03과 Steam의 2026-07-02는 하루 차이가 있다.
시간대 표기 차이로 추정할 수 있으나 원인은 `UNKNOWN`이며, 이 문서는 Steam store 날짜를
제품 출시일로 사용한다.

### 3.2 보급·병참

공개 자료는 단일 `supply` 숫자보다 다음 연결을 보여 준다.

| 단계 | 공식적으로 관찰한 규칙 | 오픈삼국 해석 |
|---|---|---|
| 생산 | 도시 인구가 클수록 식량 생산이 늘고, 도시의 농업·경제·치안·병참이 전쟁 지속력에 연결된다. [1.3.2](https://store.steampowered.com/news/app/4818580/view/1839676055894353), [store](https://store.steampowered.com/app/4818580/?l=english) | 생산량은 supply와 동일하지 않다. 생산→재고→수송을 분리할 근거다. |
| 배분·수송 | 자동 식량 재배분은 전선·대병력 도시를 우선하고, 긴 전선에서는 원군·보급 수송에 시간이 걸린다. 수송 중인 shipment에는 예상 도착 시간이 있다. [공식 patch notes](https://threekingdoms-classic.web.app/en/patch-notes/), [1.3.0](https://store.steampowered.com/news/app/4818580/view/1839676055887510) | 즉시 자원 이전 대신 transit state와 ETA를 둘 후보가 된다. |
| 출진 지속력 | 출진 화면은 현재 군량으로 군대가 얼마나 유지되는지 표시한다. [1.3.0](https://store.steampowered.com/news/app/4818580/view/1839676055887510) | 결과 숫자보다 `consumption forecast + reason` projection이 중요하다. |
| 전장 교란 | `Supply Disruption`은 일정 기간 식량과 사기를 압박하는 persistent status다. 산악전은 보급로 통제를 포함하고, 원군은 보급선 교란을 수행할 수 있다. [1.2.0](https://store.steampowered.com/news/app/4818580/view/679630785292010216), [1.1.0](https://store.steampowered.com/news/app/4818580/view/1838407329255772) | `route cut = 즉시 0`보다 기간·원인·해제 조건을 가진 상태가 replay에 적합하다. |
| 포위·기근 | 포위는 수비군 사기 압박, 탈영, 식량 소비 증가를 일으키고 야습·출격으로 해제될 수 있다. 기근 손실은 아사와 탈영을 구분한다. [1.1.2](https://store.steampowered.com/news/app/4818580/view/1838407329263922), [1.3.0](https://store.steampowered.com/news/app/4818580/view/1839676055887510) | 피해량만 남기지 말고 원인별 event/delta를 보존할 후보다. |

`[추론]` 오픈삼국에 가장 잘 맞는 최소 형태는 `SupplyShipment`의 전체 물류망이 아니라,
이미 v2가 계획한 convoy/route 계약 위에 `planned → in_transit → delayed|interdicted → arrived`
상태와 `SupplyForecast` read projection을 얹는 것이다. 이는 새 persistent aggregate와 ordered
delta가 필요하므로 CQRS 비용은 **중간 이상**이다. v1의 도시 rice, battle RNG, 월간 로그를
재해석하면 안 된다.

### 3.3 관전·위임·replay

| 표면 | 공식적으로 관찰한 규칙 | 금지되는 과잉 해석 |
|---|---|---|
| Observer/Spectator | AI 세력의 전쟁·외교를 관찰한다. playback speed를 바꾸고 세력 정보·장수 수를 볼 수 있다. [store](https://store.steampowered.com/app/4818580/?l=english), [1.1.0](https://store.steampowered.com/news/app/4818580/view/1838407329255772), [1.2.0](https://store.steampowered.com/news/app/4818580/view/679630785292010216) | 네트워크 spectator, multiplayer 권한 모델 또는 delayed anti-cheat feed의 증거가 아니다. |
| Full Delegation | 위임 진행 패널이 AI 공격 명령·전투 진행을 보여 주며, 주요 결정은 위임 중에도 플레이어 통제에 남을 수 있다. [1.1.0](https://store.steampowered.com/news/app/4818580/view/1838407329255772), [1.1.2+2](https://store.steampowered.com/news/app/4818580/view/1838407329264761) | AI가 플레이어 권한을 안전하게 대신하는 일반 계약의 증거가 아니다. |
| Presentation control | 다른 세력의 전략 행동을 Slow/Fast/Skip으로 보고, Skip은 중간 presentation 대신 주요 결과 summary만 보인다. [1.1.2](https://store.steampowered.com/news/app/4818580/view/1838407329263922) | simulation time을 임의 배속하거나 서버 cadence를 바꾸는 기능과 같지 않다. |
| Replay/History Book | 캠페인 명령·장수 행적·세력 흥망을 기록하고 History Book에 playback speed가 있다. replay 파일은 local-only이며 save는 Steam Cloud 대상이다. [store](https://store.steampowered.com/app/4818580/?l=english), [1.1.2](https://store.steampowered.com/news/app/4818580/view/1838407329263922), [1.2.0](https://store.steampowered.com/news/app/4818580/view/679630785292010216) | command log 재실행, event sourcing, 동일 seed 재현 또는 byte-identical replay의 증거가 아니다. |

`[추론]` SSE와의 접점은 transport가 아니라 projection이다. 오픈삼국은 simulation speed를
관전자 요청으로 바꾸지 않고, 같은 committed version에서 다음 세 read model을 구분할 수 있다.

1. `live summary`: world clock, 주요 결과, 전쟁·외교 변화
2. `authorized detail`: 현재 플레이어가 볼 수 있는 장수·도시·명령 근거
3. `archive replay`: 이미 공개 가능한 committed event와 snapshot 기준 기록

현재 SSE는 coarse `turnCompleted` relay이므로 이 분리는 단순 UI 복제가 아니다. visibility,
version barrier, reconnect cursor, retention을 포함한 별도 계약이 필요해 CQRS 비용은 **중간**이다.

### 3.4 offline·determinism 판정

- offline: `UNKNOWN`. local save/replay와 device-side save editor는 로컬 상태의 증거지만,
  Steam이나 network 없이 실행된다는 공식 보장은 찾지 못했다.
- determinism: `UNKNOWN`. randomized scenario reroll, save 변환, replay recovery는 문서화됐지만
  seed, draw order, command-log replay 또는 동일 입력 재현 계약은 공개되지 않았다.
- authority: `UNKNOWN`. single-player 내부 구조를 client/server/CQRS로 추정하지 않는다.
- multiplayer fit: 관전 정보 구조와 AI 위임 설명은 `FOLLOW-UP`, simulation/runtime 복제는 `REJECT`.

## 4. 기타 게임 후보 카탈로그

점수 `높음/중간/낮음`은 오픈삼국에 대한 상대 평가이며 게임 품질 평가가 아니다.

| 후보 | 1차 근거로 확인한 시스템 | 멀티 적합성 | offline·결정론 근거 | CQRS 비용 | 판정 |
|---|---|---|---|---|---|
| **Late Eastern Han Dynasty** | MIT 공개 prototype. 도시·장수·외교·첩보·전투·보급을 authoritative Express/WebSocket server가 판정하고 thin client가 command를 보낸다. 모든 난수는 seeded PRNG를 통과해 같은 save+seed 결과 재현을 명시한다. [README at `68f66fa`](https://github.com/CtxPilot/Late-Eastern-Han-Dynasty/blob/68f66fa93f7f8d94139572642c9a7aeea468c261/README.md) | 구조 참고 **높음**, 실제 multi **낮음** | offline 명시, deterministic 명시. 단 multiplayer 없음, durable SQLite save/load 미구현 | 중간 | **FOLLOW-UP**: deterministic harness/authority 경계. multiplayer 선례는 `DEFER` |
| **Sanguo's Ambition 4** | 8인 1v1·2v2·4v4 matchmaking, parameterized room, 약 1시간 match, 103도시·500+장수, 매 판 군주·정책·장수·건물·전장 위치 randomization. [공식 Steam store](https://store.steampowered.com/app/2525880/Sanguos_Ambition_4_Three_Kingdoms/) | bounded world **높음** | single-player 표시는 있으나 offline은 `UNKNOWN`; seed/replay/authority `UNKNOWN` | 높음 | **FOLLOW-UP**: room/world lifecycle만. engine 채택 `DEFER` |
| **Three Kingdoms:BOND** | full draft, formation/positioning 후 auto battle, 과거 player data와 싸우는 asynchronous mode, code 기반 friend real-time match. 공식 문구의 “Zero RNG frustration”와 randomized draft는 seed/replay 계약을 뜻하지 않는다. [공식 Steam store](https://store.steampowered.com/app/4375230/Three_KingdomsBOND/) | async encounter **높음**, grand strategy **낮음** | offline `UNKNOWN`; determinism `UNKNOWN` | 중간 | **FOLLOW-UP**: immutable opponent snapshot/reason projection. 경제·수집 구조는 `REJECT` |
| **Three Kingdoms: The Last Warlord** | 1,300+ 장수, 도시·장수 차별화, 정책 기반 도시 자동/원격 관리, dynamic relationship, scenario editor. [공식 Steam store](https://store.steampowered.com/app/577230/Three_Kingdoms_The_Last_Warlord/) | gameplay taxonomy **중간**, multi **낮음** | single-player only; offline·seed·replay `UNKNOWN` | 중간 | 위임·editor taxonomy만 `DEFER`; runtime 선례 `REJECT` |
| **Total War: THREE KINGDOMS** | turn-based campaign+real-time battle, Guanxi relationship, 외교·정치·경제·군사, online/LAN PvP·co-op. [SEGA 공식 사이트](https://asia.sega.com/totalwar-three-kingdoms/en/), [공식 Steam store](https://store.steampowered.com/app/779340/Total_War_THREE_KINGDOMS/) | 관계·외교 **중간**, cadence **낮음** | offline·determinism `UNKNOWN` | 높음 | 관계 consequence 참고만 `DEFER`; real-time battle/runtime 복제 `REJECT` |
| **Oriental Empires: Three Kingdoms** | 26세력, 300+ 역사 인물, scripted event, 넓은 campaign map. base game 표면은 online PvP/co-op을 표시한다. [공식 Steam DLC](https://store.steampowered.com/app/1089620/Oriental_Empires_Three_Kingdoms/) | 넓은 faction start **중간** | offline·determinism `UNKNOWN` | 높음 | 고유 근거가 적어 **DEFER** |
| **Three Kingdoms: Ancient Battlefield** | room 기반 실시간 PvP·난투·team-vs-AI, 10~60분, 도시명 외 route가 random. [공식 Steam store](https://store.steampowered.com/app/985020/Three_KingdomsAncient_battlefield/) | short room **중간**, 예약턴 **낮음** | broadband 필요; seed/replay `UNKNOWN` | 높음 | room duration만 참고. online real-time·과금 surface는 **REJECT** |
| **Three Kingdoms: Real-Time War** | MMO/online PvP·co-op, 실시간 대형 sandbox와 seasonal rule/map 변화, “no turn-based restrictions”. [공식 Steam store](https://store.steampowered.com/app/3091020/Three_Kingdoms_RealTime_War/) | 전쟁 규모 **중간**, 제품 cadence **낮음** | broadband 필요; determinism `UNKNOWN` | 높음 | seasonal demand의 counterexample. runtime은 **REJECT** |
| **The Three Kingdoms: Rebirth** | parallel-world FMV choice narrative, 30+ ending, single-player. 개발자 설명상 역사 사실이나 원전 재현이 아니다. [공식 Steam store](https://store.steampowered.com/app/3412900/The_Three_Kingdoms_Rebirth/) | 낮음 | offline·determinism `UNKNOWN` | 높음 | 전략 시스템 범위 밖이므로 **REJECT** |

공개된 closed-source 후보 중 memory-centric CQRS, event sourcing, durable idempotent intake,
seeded authoritative multiplayer replay를 명시한 제품은 찾지 못했다. 해당 항목은 모두
`UNKNOWN`으로 남긴다.

## 5. 우선순위와 후속 분리

| 우선순위 | 후속 | 이유 | 문서 |
|---:|---|---|---|
| 1 | 관전·기록 read model 계약 | 기존 SSE와 가장 가까우며 simulation semantics를 바꾸지 않고 검증 가능 | [관전·기록 follow-up](../plans/2026-08-13-opensam-110-spectator-record-follow-up-draft.md) |
| 2 | 보급 shipment·forecast 계약 | 기존 v2 convoy/보급 계획을 구체화하지만 persistent state 비용이 큼 | [보급 follow-up](../plans/2026-08-13-opensam-110-supply-follow-up-draft.md) |
| 3 | 결정적 외부 benchmark spike | architecture 선례를 코드 복제 없이 검증하고 acceptance vocabulary를 정리 | [결정론 follow-up](../plans/2026-08-13-opensam-110-deterministic-benchmark-follow-up-draft.md) |

세 문서는 모두 `DRAFT / NOT APPROVED`다. OPENSAM-110 완료가 후속 구현 승인, 티켓 완료,
v2 activation, merge 또는 deploy를 뜻하지 않는다.

## 6. 제약·미확인 사항

- 삼국지 클래식의 공개 manual-like 표면은 공식 site controls와 patch notes다. 구매 없이
  별도 downloadable manual은 찾지 못했다.
- 공식 Steam news는 출시 뒤 빠르게 변한다. 이 문서는 2026-08-13 기준 1.3.2까지 확인했다.
- store review 수·평점·가격은 가변이고 시스템 판정에 필요하지 않아 증거에서 제외했다.
- Steam 검색/캐시가 일부 localized page를 열지 못한 경우 official canonical URL과 Valve
  public app/news endpoint로 교차확인했다. 이는 source retrieval 제약이지 제품 결론이 아니다.
- 외부 게임의 수치, 텍스트, portrait, 영상, save/replay file은 수집·복제하지 않았다.
