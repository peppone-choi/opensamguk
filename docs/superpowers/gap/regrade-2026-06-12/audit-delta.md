# 감사 델타 재검증 — 2026-06-12 (loop wheels 11~19)

> **범위**: `PAGE_PARITY_AUDIT_2026-06-11.md` 이후 main 커밋 9건(`git log b54b7ed~9..HEAD`, 휠 11~19)이
> 주장하는 P0 마감을 **구현 코드를 직접 읽어** 재판정하고, 잔여 P0 목록을 재계산한다.
> **판정 기준**(원 감사와 동일): PHP `legacy/devsam-core` = grand truth. 위조 표시(silent fabrication)·
> 정보 누출·항상-실패(또는 항상-엉뚱한) 액션 = P0, 데이터/액션/콘텐츠 결손 = P1, 구조·표기 드리프트 = P2.
> READ-ONLY 측정 — 소스/테스트/골든 무수정. 모든 인용은 실제 열람한 file:line.

## 0. 요약

| 커밋 | 감사 항목 | 판정 |
|---|---|---|
| `ef463b0` | P0-12 city id<=0 → general.cityId fallback | **FIXED** |
| `b2da61d` | P0-27 inherit statMin/Max 10/90 → 15/80 | **FIXED** |
| `127454e` | P0-18 공개 장수일람 crew 노출 제거 | **FIXED** |
| `e256dee` | P0-17 diplomacy prevNo selector | **PARTIAL** |
| `066a711` | P0-26 inherit OpenUniqueAuction FE 배선 | **NOT-FIXED** (잠복 위조 신설) |
| `ced05b2` | P0-10 chief-center 당기기/미루기/반복 | **FIXED** (항목 범위) |
| `0f32d8f` | P0-02 개인 예약 링 당기기/미루기/반복 | **PARTIAL** |
| `b54b7ed` | P0-34 mailbox 외교 마스킹 (커밋 prefix `P0-28`은 오기) | **PARTIAL** (신규 P0 회귀 동반) |
| `6cbecfc` | P0-14 守/수비○ 위조 '-' 마스킹 | **FIXED(P0 기준)** — 잔여는 P1 강등 |

신규 finding: **P0 ×3 / P1 ×3 / P2 ×1** (아래 §2).
잔여 P0: **31건(부분 6건) + 신규 회귀 1건 = 32건** (아래 §3).

---

## 1. 닫힌 항목 검증 결과 (커밋별)

### 1.1 P0-12 — FIXED (`ef463b0`)

- **legacy**: `hwe/b_currentCity.php:77-78` — `if (!$citylist) { $citylist = $me['city']; }`
  (citylist=0/부재 시 현재 장수 소재 도시).
- **impl**: `app/game-api/.../web/CityDetailController.kt:170-177` — general 먼저 resolve 후
  `effectiveId = if (id <= 0) general?.cityId?.takeIf { it > 0 } ?: id else id`로 도시 조회.
  FE 기본 진입이 정확히 이 경로를 탄다: `web/game/app/game/city/page.tsx:53` (`idParam` 부재 → `cityId=0`),
  `:63` (`api.city(0)`).
- 판정: 기본 진입(현재 도시) 404 붕괴 해소. **FIXED**.

### 1.2 P0-27 — FIXED (`b2da61d`)

- **legacy**: `hwe/d_setting/GameConst.php:6-7` — `$defaultStatMin = 15; $defaultStatMax = 80;`
  + `hwe/v_inheritPoint.php:112-113` — `'statMin' => GameConst::$defaultStatMin, 'statMax' => GameConst::$defaultStatMax`.
- **impl**: `app/game-api/.../controller/InheritPointController.kt:50-52` —
  `statMin = GameConst.defaultStatMin / statMax = GameConst.defaultStatMax`;
  `common/.../constants/GameConst.kt:183-184` = 15/80. 테스트 기대값 교정은 골든 약화가 아니라
  날조값(10/90) 제거 — 적법.
- 판정: **FIXED**.

### 1.3 P0-18 — FIXED (`127454e`)

- **legacy**: `hwe/sammo/API/Global/GeneralList.php:69` — SELECT 목록에 `crew` 없음
  (owner,no,picture,…,killturn,refresh_score_total 만).
- **impl**: `PublicGeneral` DTO에서 `crew` 제거(`app/game-api/.../dto/F4Dto.kt:23-47` 현재 필드에 crew 부재),
  `GeneralsController` projection 제거, FE 컬럼/정렬키 제거(`web/game/app/game/generals/page.tsx:42-66`).
  `F4Dto.kt:591`의 `crew`는 별개 DTO(`TroopMember` — 부대원, 누설 아님).
- 판정: **FIXED**. (단 `GeneralsController.kt:26` 주석에 crew 언급 잔존 — 코드 비영향 stale 주석.)

### 1.4 P0-17 — PARTIAL (`e256dee`)

- **legacy**: `hwe/ts/diplomacy.ts:370-390` — prevNoList는 `state == 'replaced' || 'cancelled'`만 제외
  → **'proposed'도 교체 대상에 포함**. `:405-417` — 이전 문서 선택 시 destNation **잠금** + brief/detail
  **프리필**. `:333-337` — 서신 카드별 `btnRenew`(폼으로 점프).
- **impl**: `web/game/app/game/diplomacy/page.tsx:240-256` — selector가 `l.state === 'activated'`만 노출.
  prevNo state가 `api.command('diploSendLetter', { …, prevNo })`로 전달되고 wire는 이미 수용
  (`CommandWireMapper.kt:283` `args.int("prevNo")?.takeIf { it >= 1 }`).
- 판정: "항상 prevNo:null" 핵심은 해소. 그러나 **proposed 서신 교체(자국 제안 갱신) 불가** + destNation
  잠금/프리필/btnRenew 부재 → **PARTIAL**.

### 1.5 P0-26 — NOT-FIXED (`066a711`) ← 최중대

- **legacy**: `hwe/ts/PageInheritPoint.vue:610-643` — `openUniqueItemAuction()`은
  `SammoAPI.Auction.OpenUniqueAuction({ itemID, amount })` 호출. `:70-71` — `amount`는
  `NumberInputWithInfo`(min=`inheritActionCost.minSpecificUnique`, max=보유 포인트) 필수 입력.
- **impl 결함 3중**:
  1. **잘못된 명령 코드**: FE가 `api.command('OpenUniqueAuction', …)` 전송
     (`web/game/app/game/inherit/page.tsx:344` → `components/CommandModal.tsx:183-188`).
     intake 코드 정답은 `auctionOpenUnique`(`app/game-api/.../reserve/CommandWireMapper.kt:80`) —
     `"OpenUniqueAuction"`은 `intakeCodes`에 없어 `toCommand()` null → **Model A 일반 턴 링 예약**으로
     낙하(`CommandReserveService.kt:82-96`). precheck는
     `CommandRegistry.kt:211` `else -> RestAction`으로 **Allow** → 202 → CommandModal이
     "유니크 경매 시작 명령이 예약되었습니다." 성공 토스트. **실제로는 휴식 턴이 예약**된다
     (턴 소모 + 경매 미개설 + 성공 표시 = 3중 위조).
  2. **잘못된 인자 키**: FE `extraArgs: { item }` vs mapper가 읽는 키 `itemId`/`itemKey` + `amount`
     (`CommandWireMapper.kt:273-277`). 코드가 맞았더라도 itemId=""/amount=0.
  3. **amount 입력 자체 부재**: legacy 필수 입찰 포인트 입력이 FE에 없음.
- 이미 존재하는 올바른 헬퍼 `api.commandQueue.auctionOpenUnique({itemId, amount})`
  (`web/game/lib/api.ts:490-494`)를 쓰지 않았다.
- **현재는 잠복 상태**: P0-23(`InheritPointController.kt:123-124` availableSpecialWar/availableUnique
  `emptyMap()` 하드코딩)이 select를 비워 버튼이 `disabled(!selectedUnique)`로 영구 비활성 —
  P0-23이 닫히는 순간 위 위조가 활성화되는 **시한폭탄**. 판정: **NOT-FIXED**.

### 1.6 P0-10 — FIXED, 항목 범위 (`ced05b2`)

- **legacy**: `hwe/ts/components/ChiefReservedCommand.vue:40`(반복), `:94-103`(당기기=음수/미루기=양수,
  `pushNationCommand(-turnIdx / turnIdx)`), `:408,440` → `hwe/sammo/API/NationCommand/PushCommand.php:19-23`
  (amount int, -12..12), `:38-39`(`amount==0 → '0은 불가능합니다'`), `:56`(`pushNationCommand`).
- **impl**: BE `app/game-api/.../web/CommandController.kt:173-207` — range/0-게이트 순서까지 PHP 일치.
  FE `web/game/components/game/ChiefCommandReserve.tsx:161-215` — signed numeric input + 적용 2종,
  `api.commandQueue.nationPush/nationRepeat`(`web/game/lib/api.ts:425-428`) 배선,
  `isIntakeQueued/isIntakeDenied` 분기(성공 위조 없음).
- 판정: 감사 항목(당기기/미루기/반복 버튼 전무)은 **FIXED**. 고급 모드는 별도 항목 P0-11로 잔존.
  P2 드리프트: legacy 1..12턴 dropdown ↔ signed input 단일, `window.alert` ↔ 페이지 토스트.

### 1.7 P0-02 — PARTIAL (`0f32d8f`)

- **legacy**: `hwe/ts/PartialReservedCommand.vue:243-263`(당기기/미루기 dropdown,
  `pushGeneralCommand(-turnIdx/turnIdx)`), `:19-24`(반복), `:437-467`(API 호출) — 이 부분은 배선됨:
  `web/game/components/game/PartialReservedCommand.tsx:131-187` + BE `CommandController.kt:134-157`
  (push -12..12 / repeat 1..12).
- **잔여**: 감사 인용 범위 중 edit-mode 링 일괄 조작 전부 미구현 —
  `PartialReservedCommand.vue:27-103`(범위 선택: 해제/모든턴/홀짝/간격), `:88-95`(반복하기/비우기/
  지우고 당기기/뒤로 밀기), `:553-585`(`reserveCommandDirect` 멀티턴 bulk), `:673-884`(선택 조작).
- 판정: 헤드라인 3종(당기기/미루기/반복)은 닫힘, 일괄 편집 표면은 잔존 → **PARTIAL**.

### 1.8 P0-34 — PARTIAL + 신규 P0 회귀 (`b54b7ed`, 커밋 prefix "P0-28"은 오기)

- **legacy**: `hwe/sammo/API/Message/GetRecentMessage.php:125-139` — 마스킹은 **diplomacy 섹션
  (`MessageType::diplomacy`) 한정**으로 `dest->nationID != 0 && permission < 3`일 때만
  `'(외교 메시지입니다)'` + `option.invalid`. private/public/national 섹션(`:100-123`)은 무마스킹.
  (`GetOldMessage.php:135` 동일.)
- **impl**: `app/game-api/.../controller/MailboxController.kt:48-68` — `/api/mailbox/{mailbox}` ·
  `/unread`에 `secretPermission` + `applyDiplomacyMask` 적용. 페이지가 쓰는 endpoint가 맞다
  (`web/game/app/game/mailbox/page.tsx:59`). **누출 방향은 닫힘**.
- **결함 1 (신규 P0 회귀, over-masking)**: `applyDiplomacyMask`(`MailboxController.kt:358-364`)가
  **메시지 type을 검사하지 않고** `dest.nation_id != 0 && permission < 3`만 본다. private 메시지의
  dest도 수신자의 실제 국가가 들어가므로(`app/game-engine/.../intake/MessageHandler.kt:556-571`
  `MsgTarget.toArray()` — `"nation_id" to nationId`) **permission<3 일반 장수의 개인 서신함/국가
  서신이 전부 '(외교 메시지입니다)'로 위조 마스킹**된다. 같은 컨트롤러의 `recent`는 diplomacy 섹션에만
  적용해(`MailboxController.kt:122-123`) 올바름 — 신규 두 endpoint만 type-blind.
- **결함 2 (누출 잔존)**: `GET /api/messages/{id}`(`MailboxController.kt:70-75`)는 마스킹 없이
  `msg.toResponse()` 원문 반환 — 비외교권자가 id로 외교 원문 단건 조회 가능.
- 판정: **PARTIAL** — 리스트 누출은 닫혔으나 단건 누출 잔존 + 신규 over-masking 회귀.

### 1.9 P0-14 — FIXED(P0 기준), 잔여 P1 강등 (`6cbecfc`)

- 원 감사 자체가 "배선 전엔 '-' 마스킹 권장"으로 명시한 처방을 그대로 이행:
  `web/game/app/game/city/page.tsx:199-204`(수비○ → `-`), `:275-280`(守 → `-`),
  `formatDefenceTrain(0)` 위조 호출 제거. 수치 날조 없음.
- 판정 기준상 위조 표시(P0)는 소멸, **defence_train read-chain 미배선은 데이터 결손(P1)으로 강등**
  (BE `defenceTrain` 원천 부재 — GeneralReadEntity 컬럼/flush 백로그, LEDGER wheel 19 명시).

---

## 2. 신규/잔존 finding 목록

| # | 심각도 | finding | legacy 근거 | 현재 impl 근거 |
|---|---|---|---|---|
| F-1 | **P0** | P0-26 배선이 잘못된 코드(`OpenUniqueAuction`)로 휴식 턴을 예약하면서 성공 토스트 표시(잠복 — P0-23 닫히면 활성). 인자 키 `item`≠`itemId`, `amount` 입력 부재 | `PageInheritPoint.vue:635-638` (`{itemID, amount}`), `:70-71` (amount 필수) | `inherit/page.tsx:344`; `CommandWireMapper.kt:80,273-277`; `CommandReserveService.kt:82-96`; `CommandRegistry.kt:211` |
| F-2 | **P0** | mailbox/unread 마스킹이 type-blind — permission<3 일반 장수의 **개인/국가 서신이 '(외교 메시지입니다)'로 위조 마스킹** (b54b7ed 신규 회귀) | `GetRecentMessage.php:125-139` (diplomacy 섹션 한정) | `MailboxController.kt:48-68,358-364`; dest 구조 `MessageHandler.kt:556-571`; 페이지 소비 `mailbox/page.tsx:59` |
| F-3 | **P0** | `GET /api/messages/{id}` 단건 endpoint 마스킹 부재 — 외교 원문 누출 잔존 (P0-34 잔여) | `GetRecentMessage.php:134-137` | `MailboxController.kt:70-75` |
| F-4 | **P1** | P0-17 잔여 — proposed 서신 교체 불가(activated만 노출) + destNation 잠금/프리필/btnRenew 부재 | `diplomacy.ts:380-390,405-417,333-337` | `diplomacy/page.tsx:247-255` |
| F-5 | **P1** | P0-02 잔여 — 개인 링 edit-mode 일괄 조작(범위/비우기/지우고 당기기/뒤로 밀기/멀티턴 bulk) 부재 | `PartialReservedCommand.vue:27-103,88-95,553-585,673-884` | `PartialReservedCommand.tsx` (해당 표면 없음) |
| F-6 | **P1** | P0-14 강등 잔여 — defence_train read-chain 미배선(守/수비○ '-' 영구 표시) | `b_currentCity.php` 守 렌더(원 감사 인용) | `city/page.tsx:199-204,275-280` (마스킹 주석) |
| F-7 | **P2** | P0-10/02 표기 드리프트 — legacy 1..12턴 dropdown ↔ signed numeric input, chief는 `window.alert` ↔ 토스트 | `ChiefReservedCommand.vue:94-103` | `ChiefCommandReserve.tsx:161-215` |

(P0-12/27/18 검증 통과 — finding 없음. `GeneralsController.kt:26` stale 주석은 비계상 메모.)

---

## 3. 잔여 P0 재계산 — 32건 (부분 6건 + 신규 회귀 1건)

완전 마감으로 제외: **P0-10, P0-12, P0-18, P0-27** (이번 델타) + 기존 FIXED 17건 + P0-13.
P1 강등: **P0-14**.

| # | 한 줄 요약 |
|---|---|
| P0-02 (p) | 개인 예약 링 — 당기기/미루기/반복은 닫힘, edit-mode 일괄 조작(범위/비우기/지우고 당기기/뒤로 밀기/bulk) 잔여 |
| P0-03 | 메인 RecordZone 3컬럼 피드 전무 (W0-5 원천 확보, FE 소비 미구현) |
| P0-05 | b_betting 토너먼트 베팅장 페이지 전체 부재 + 컨트롤바 오라우팅 |
| P0-06 (p) | 베팅 제출 — FE 가드/토스트 분기 완료, 엔진 deny 결과 채널 잔여 |
| P0-07 | PlaceBetHandler가 Betting::bet 검증·부수효과 9종 누락 (바퀴 20 진행 중) |
| P0-11 | chief-center 고급 모드(다중 턴 일괄 편집) 전체 부재 |
| P0-16 | 외교 승인/거부(respond letter) FE·wire·엔진 전부 부재 — 조약 성립 불가 |
| P0-17 (p) | prevNo 전송 가능해짐 — proposed 교체/프리필/btnRenew 잔여 |
| P0-20 | 연감 데이터 영구 공백 — LogHistory 월별 writer 미구현 |
| P0-21 (p) | 중원 정세·장수 동향 — 원천 확보 완료, FE/컨트롤러 소비 잔여 |
| P0-23 | availableSpecialWar/availableUnique emptyMap 하드코딩 (`InheritPointController.kt:123-124` 현존 확인) |
| P0-26 | 유니크 경매 시작 — NOT-FIXED, 잘못된 코드로 휴식 턴 예약 위조(F-1). 정정 필수 |
| P0-28 | inherit '더 가져오기'(GetMoreLog) 페이지네이션 부재 (커밋 b54b7ed의 "P0-28" prefix는 오기 — 이 항목은 미착수) |
| P0-29 | join 유산 포인트 사용 블록 전체 미존재 (wire/엔진 포함) |
| P0-30 | join 전콘 사용(pic) silent no-op — 항상 default.jpg |
| P0-31 | join 국가 임관권유문 섹션 전체 미존재 |
| P0-32 | mailbox 서신 발송 FE 전체 부재 (intake `sendMessage`는 존재 — FE 갭으로 재분류 여지) |
| P0-34 (p) | 외교 마스킹 — 리스트 닫힘, `/api/messages/{id}` 단건 누출(F-3) + type-blind 회귀(F-2) 잔여 |
| P0-36 (p) | map 도시 state — FE prop 대응 완료, 스키마 `city.state`+flush 근본 미해결 |
| P0-37 | my-boss 페이지 개념 전체 fabricated — 인사부 아님 |
| P0-39 | 수뇌부 임명(do수뇌임명) end-to-end 부재 |
| P0-40 | 도시 관직 임명(태수/군사/종사) 부재 |
| P0-41 | 추방(do추방) 부재 — 몰수/배신 패널티/부대 해산 포함 |
| P0-42 | 외교권자/조언자 임명(군주 전용) 부재 |
| P0-43 | my-boss read DTO가 인사부 요구 데이터의 사실상 0% |
| P0-44 | my-cities 서버 정렬(12종) 폼 전체 누락 |
| P0-46 | my-cities 암행부 연동(13컬럼 인라인 확장) 누락 |
| P0-47 | my-cities 인사부 연동 즉시 임명 — BE '임명' 엔드포인트 자체 부재 |
| P0-50 | nation BuyHiddenBuff/BuyRandomUnique generalId 미전송 → 400 (바퀴 21 대기) |
| P0-52 | nation-finance income/outcome 위조 0 — rate=100 라이브 계산 미이식 |
| P0-54 | nation-finance 외교관계 7컬럼 표 전체 부재 |
| **NEW F-2** | **mailbox/unread type-blind 위조 마스킹 회귀 — 일반 장수 개인/국가 서신 전부 '(외교 메시지입니다)'** |

> 우선 권고: ① F-2 회귀 즉시 정정(`it.type == DIPLOMACY` 조건 추가 — 1줄), ② F-1 P0-26 정정
> (`api.commandQueue.auctionOpenUnique({itemId, amount})` + amount 입력 — P0-23 닫기 전에 선행 필수),
> ③ F-3 `/api/messages/{id}` 마스킹. 이후 기존 우선순위(P0-07 → P0-50 → P0-16/17 → …) 재개.
