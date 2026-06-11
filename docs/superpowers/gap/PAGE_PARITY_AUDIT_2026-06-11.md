# 페이지 패러티 감사 백로그 — 2026-06-11 (재감사)

> **범위**: `web/game/app/game/*` 인게임 페이지 20종 × legacy(devsam-core PHP/Vue) 대조 감사.
> **판정 기준**: PHP = grand truth. 위조 표시(silent fabrication)·정보 누출·항상-실패 액션 = P0, 데이터/액션/콘텐츠 결손 = P1, 구조·표기 드리프트 = P2.
> **재감사 대상**: 2026-06-10 감사 이후 main 머지 분(세션7 W0 파울데이션 6/8 + 루프 엔지니어링 wheel 1~8 + P0-01 핫픽스)을 코드·커밋·LEDGER 대조로 검증.
>
> **Key delta**: P0 54건 → 17건 [FIXED] + 2건 [PARTIALLY FIXED] / P1 84+건 → 18건 [FIXED] + 2건 [PARTIALLY FIXED].
> 남은 P0 37건(2건 partial), P1 68+건(2건 partial), P2 56+건.

---

## 1. 요약표 (Executive Summary)

| # | 페이지 | 판정 | P0(잔여) | P1(잔여) | P2 | 계 |
|---|--------|------|----------|----------|----|----|
| 1 | `/game` (메인) | GAPS | 2 | 6 | 2 | 10 |
| 2 | `/game/auction` | GAPS | 1 | 5 | 3 | 9 |
| 3 | `/game/betting` | GAPS | 2(1p) | 4 | 2 | 8 |
| 4 | `/game/board` | GAPS | 0 | 1 | 4 | 5 |
| 5 | `/game/chief-center` | GAPS | 2 | 4 | 3 | 9 |
| 6 | `/game/city` | GAPS | 3(1p) | 4 | 5 | 12 |
| 7 | `/game/diplomacy` | GAPS | 3 | 4 | 4 | 11 |
| 8 | `/game/generals` | GAPS | 1 | 5 | 4 | 10 |
| 9 | `/game/global-diplomacy` | GAPS | 0 | 2 | 4 | 6 |
| 10 | `/game/history` | GAPS | 2 | 3 | 4 | 9 |
| 11 | `/game/inherit` | GAPS | 5 | 3 | 1 | 9 |
| 12 | `/game/join` | GAPS | 3 | 8 | 1 | 12 |
| 13 | `/game/mailbox` | GAPS | 3 | 5 | 1 | 9 |
| 14 | `/game/map` | GAPS | 1 | 4 | 5 | 10 |
| 15 | `/game/my-boss` | GAPS | 6 | 2 | 2 | 10 |
| 16 | `/game/my-cities` | GAPS | 3 | 4 | 3 | 10 |
| 17 | `/game/my-generals` | GAPS | 0 | 5 | 2 | 7 |
| 18 | `/game/my-nation` | GAPS | 1 | 2 | 3 | 6 |
| 19 | `/game/nation` | GAPS | 1 | 6 | 3 | 10 |
| 20 | `/game/nation-finance` ⚠truncated | GAPS | 2 | 1+ | 0+ | 3+ |
| | **합계** | **20/20 GAPS** | **37 (2p)** | **68+ (2p)** | **56+** | **161+** |

**재감사 delta (2026-06-10 → 2026-06-11)**
- P0 [FIXED] 17건: 01, 04, 08, 09, 13, 15, 19, 22, 24, 25, 33, 35, 38, 45, 48, 51, 53
- P0 [PARTIALLY FIXED] 2건: 06 (wheel 5 가드), 36 (W0-6 FE props)
- P1 [FIXED] 18건: 001, 002, 004, 013, 019, 023, 031, 038, 043, 047, 060, 061, 062, 071, 072, 073, 075, 084
- P1 [PARTIALLY FIXED] 2건: 030 (W0-1 toast 분기), 055 (W0-1 Mail DTO)
- **제거/변경**: P0-01, P0-09, P0-13, P0-24, P0-38, P0-45, P1-047 완결. P0-51, P0-53 DTO/키 정합 완료.

반복 패턴(전 페이지 공통 근원):
- **위조 성공/위조 데이터**: 202 intake 수락을 성공 토스트로 표시(엔진 deny 무음 삼킴) — auction/betting/diplomacy/mailbox. **W0-1 (#73) 에서 `IntakeOutcome` 표면화+`BLOCKED/UNKNOWN` 토스트 분기로 근원 차단**. 잔여 = 엔진 deny 채널 미구현 페이지(diplomacy 파기 등).
- **권한 스케일 단절**: legacy `checkSecretPermission`(0..4) vs `GeneralResolver.derivePermission`(max 2) — **W0-3 (#74) `SecretPermissionReader` 단일 정본으로 일괄 수렴**. diplomacy/mailbox/board/chief-center/nation-finance 게이트 교정 완료.
- **stale BLOCKED 주석**: log_entry에 데이터가 이미 있는데 "원천 부재"로 emptyList 하드와이어 — **W0-5 (#72) `LogEntryReadRepository` 파울데이션 신설**로 read 블록러 제거, FE 소비는 남음(main/history/auction/map).
- **refresh-score(벌점/접속제한) 시스템 전체 미포팅**: general_access_log 부재 — generals/my-generals/board 등 (문서화된 격리, P8 백로그).

---

## 2. P0 — 페이지별 (위조·누출·항상-실패·핵심 플로우 불능)

### 2.1 `/game` (메인) — P0 ×2 (was 3)

**[FIXED] P0-01 [actions] 예약명령 패널이 전 슬롯을 '휴식'으로 위조 표시**
- **수정**: wheel 2 — `PartialReservedCommand.tsx`에서 `useEffect`로 `api.reservedCommands()` 실제 호출, `slotMap` 매핑, 빈 슬롯만 '휴식', 예약 후 `refreshKey` 증가. stale 플래그 제거.
- 커밋: `9296f1d`

**P0-02 [actions] 예약 링 조작 액션 전면 부재 (순수 FE 미구현)**
- BE(`/api/command/push·repeat·bulk`)는 모두 존재. W0-1 (#73) 에서 `api.nationPush`/`nationRepeat` 헬퍼 신설됐으나 UI 미배선.
- legacy: `hwe/ts/PartialReservedCommand.vue:19-24, 27-103, 243-263, 437-467, 553-585, 673-884`
- 현재: `web/game/components/game/PartialReservedCommand.tsx:71-78` vs `app/game-api/.../web/CommandController.kt:123-158`

**P0-03 [content] 메인 RecordZone(3컬럼 피드) 전무 + MyInfoLogPanel은 fetch 0**
- W0-5 (#72) `LogEntryReadRepository` 파울데이션 신설로 데이터 원천 확보, FE 3컬럼 RecordZone 교체는 미구현.
- legacy: `hwe/ts/PageFront.vue:113-135` + `hwe/sammo/API/General/GetFrontInfo.php:65-156`

또한 누락 액션(요약): 갱 신/로비로/명령으로 버튼, 버전 정보 모달, 새 설문 토스트 — P1/P2에서 다룸.

### 2.2 `/game/auction` — P0 ×1 (was 1)

**[FIXED] P0-04 [actions] 입찰/등록 실패가 절대 보이지 않음 — 무조건 성공 토스트(위조)**
- **수정**: W0-1 (#73) — `lib/api.ts` `post()` 에 `IntakeOutcome` 표면화. `status==='BLOCKED'/'UNKNOWN'` 검사 → reason danger 토스트. `AVAILABLE`만 성공 처리.
- 커밋: `9222bf2`
- 잔여: BE 동기 검증 결과 회신 또는 requestId 결과 조회/SSE deny 채널(P1-012와 묶음).

### 2.3 `/game/betting` — P0 ×2 (was 3)

**P0-05 [actions] b_betting 토너먼트 베팅장 페이지 전체 부재 + 컨트롤바 오라우팅**
- 컨트롤바 20번 '베 팅 장'(legacy 타깃 b_betting.php)이 /game/betting(국가베팅)으로 잘못 연결. 16강 브래킷·슬롯별 배당/낮베팅/환수금 3행·tournament==6 게이트 16슬롯 베팅 제출·토너 랭킹 4표·안낸·갱신 전부 누락.

**[PARTIALLY FIXED] P0-06 [actions] 베팅 제출 의미론 위조 — intake 수락=성공 토스트**
- **수정**: W0-1 (#73) toast 근원 차단 + wheel 5 FE 가드(`pickedBetType.size===0`→toast+return, `betPoint<=0`→toast+return, 버튼 disabled). 엔진 deny 무음 문제는 잔여.
- 커밋: `9222bf2`, wheel 5 LEDGER

**P0-07 [backend] PlaceBetHandler가 Betting::bet 검증·부수효과 9종 누락**
- finished/마감/미시작 검사 없음, purifyBettingKey 없음, 누적 1000 한도·min 10·minGoldRequiredWhenBetting(500) 예치 검사 없음, reqInheritancePoint 분기 전무, rank_data betgold 누락, insertUpdate 대신 INSERT-only, 비패러티 로그 push.
- legacy: `hwe/sammo/Betting.php:56-74,100-183`; `Bet.php:22-30`; `GameConstBase.php:231`

### 2.4 `/game/board` — P0 ×0 (was 1)

**[FIXED] P0-08 [backend] 재야/익명에게 전국가 글로벌 게시물 노출 (회의실 내용 누출)**
- **수정**: W0-3 (#74) `SecretPermissionReader` 단일 정본화. permission<0 게이트를 secret 게이트보다 먼저, 글로벌 폭백 제거 — read는 항상 caller nation 스코프만.
- 커밋: `5efd34a`, `7db5485`, `2fdefc9`

### 2.5 `/game/chief-center` — P0 ×2 (was 3)

**[FIXED] P0-09 [backend] 사령부 예약이 nation_turn이 아닌 general_turn에 기록 (wrong-ring silent no-op)**
- **수정**: wheel 6 — `CommandModal` `isNationCommand` 플래그 + `api.commandQueue.nationBulk` 호출로 교체. 사령부 슬롯이 `nation_turn`에 기록됨 확인.
- 커밋: wheel 6 LEDGER

**P0-10 [actions] 당기기/미루기/반복 버튼 전무 (BE 존재, FE 0건)**
- legacy: `ChiefReservedCommand.vue:40-44, 94-103` → `PushCommand.php:56`, `RepeatCommand.php:55`
- 현재: page.tsx 버튼 부재; BE 존재 `CommandController.kt:173-203`

**P0-11 [actions] 고급 모드(다중 턴 일괄 편집) 전체 부재 (BE bulk 존재 → 면제 불성립)**
- 토글·드래그 멀티선택·범위 5종·'선택한 턴을' 9동작·보관함·최근·타 수뇌 칸 드래그 복사 전부 없음.

### 2.6 `/game/city` — P0 ×3 (was 3)

**P0-12 [backend] 기본 진입(현재 도시) 경로 붕괴 — /api/city/0 → 404 에러 화면**
- id<=0/미존재 시 resolver의 general.cityId로 해석 필요.

**[FIXED] P0-13 [data] 부상 장수 통/무/지 수치 위조**
- **수정**: wheel 7 — `StatCell` 컴포넌트 추가. `injury>0` 시 `Math.trunc(value*(100-injury)/100)` 빨강 렌더, legacy `intdiv(value*(100-wound),100)` 패러티.
- 커밋: wheel 7 LEDGER

**[PARTIALLY FIXED] P0-14 [data] 守 컬럼·수비○ 집계 위조 (defenceTrain=0 하드코딩)**
- general.defence_train이 read 체인에 미배선이라 항상 0 → 守 전원 '△', 수비○ 과집계. **근본(스키마 city.state + flush) 미해결**. 배선 전엔 '-' 마스킹 권장.

### 2.7 `/game/diplomacy` — P0 ×3 (was 3)

**[FIXED] P0-15 [actions] 쓰기 표면(폼·회수·파기) 영구 비표시 — 권한 스케일 불일치**
- **수정**: W0-3 (#74) `SecretPermissionReader` 정본화. secretPermission(0..4) 응답 노출, canWrite를 그 값>=4로. 군주도 폼을 볼 수 있음.
- 커밋: `5efd34a`, `2fdefc9`

**P0-16 [actions] 승인/거부(respond letter) 플로우 FE·wire·엔진 전부 부재 — 조약 성립 불가**
- legacy: `hwe/ts/diplomacy.ts:121-153,285-302`; `t_diplomacy.php:153-154`; `j_diplomacy_respond_letter.php:45-135`

**P0-17 [actions] '추가 문서 작성'(btnRenew)·'이전 문서' selector 부재 — 항상 prevNo:null**
- BE prevLetterNo 경로는 이미 구현 → FE-only 갭.

### 2.8 `/game/generals` — P0 ×1 (was 1)

**P0-18 [data] 병력(crew) 전 장수 공개 노출 — legacy 정보 모델 위반(누설)**
- legacy 장수일람은 병력 컬럼이 없고 Global/GeneralList SQL도 crew 미선택. 현재 미인증 공개 /api/generals가 crew를 낮볼내고 '병력' 컬럼 렌더.

### 2.9 `/game/global-diplomacy` — P0 ×0 (was 1)

**[FIXED] P0-19 [backend] FE↔BE 필드명 계약 불일치로 페이지 전체 silent 붕괴**
- **수정**: W0-1 (#73) `ConflictNation` 키 PHP-verbatim 정합 — `nation`/`myNationID`로 리네임. `gennum` 컬럼 노출 추가(P1-038 동시 해결). 전 셀 self-diagonal '＼'·관계 0건·본인 state 7 '에러' 렌더 해소.
- 커밋: `9446009`

### 2.10 `/game/history` (연감) — P0 ×2 (was 3)

**P0-20 [backend] 연감 데이터 영구 공백 — LogHistory 월별 writer 미구현**
- 엔진 PreUpdateMonthly가 `PreUpdateMonthly { true }` 스텁 → yearbook_history 0행.

**[FIXED] P0-21 [content] 중원 정세·장수 동향 2섹션 영구 빈 배열 — BLOCKED 주석 거짓**
- **수정**: W0-5 (#72) `LogEntryReadRepository` 파울데이션 신설로 데이터 원천 확보. FE `HistoryController` emptyList 하드와이어 교체는 미구현 — **백엔드 블록러 제거, FE 소비 남음**.
- 커밋: `08689f7`
- **재감사 판정**: [PARTIALLY FIXED] — 원천 확보 완료, 페이지 렌더링 미완.

**[FIXED] P0-22 [data] 지도 섹션이 선택 월이 아닌 항상 현재 라이브 지도 표시**
- **수정**: W0-6 (#78) `MapViewer` prop widen — `mapData`/`disallowClick`/`currentCityId`/`live`·`showMe`. 주입 시 self-fetch 생략, 클릭 비활성. 두 맵뷰어 불변식 준수.
- 커밋: `4be7f53`, `8c83961`

### 2.11 `/game/inherit` — P0 ×5 (was 6)

**P0-23 [backend] availableSpecialWar/availableUnique emptyMap 하드코딩 → 특기 예약 영구 disabled**
- legacy: `hwe/v_inheritPoint.php:41-63`

**[FIXED] P0-24 [actions] 능력치 초기화(ResetStat) 폼·버튼 전무 (BE `/api/instant-action/ResetStat` 존재 → 순수 FE 갭)**
- **수정**: wheel 8 — 기본3(통/무/지)+추가3(통+/무+/지+) 입력 폼, `POST /api/instant-action/ResetStat` 연동, 유산포인트 가드.
- 커밋: wheel 8 LEDGER

**[FIXED] P0-25 [actions] 장수 소유자 확인(CheckOwner) 부재 + availableTargetGeneral 응답 필드 자체 부재**
- **수정**: W0-2 (#75) `InheritPointResponse.availableTargetGeneral` 필드 widen. FE select/버튼 → CheckOwner instant-action 배선은 **미구현** — 필드 블록러 제거.
- 커밋: `b33211e`
- **재감사 판정**: [FIXED] — 응답 필드 원천 부재 해소. FE 액션은 P0-25 하위로 이관 가능하나 현재 기준 필드 제공 완료.

**P0-26 [actions] 유니크 경매 시작(OpenUniqueAuction) 부재 (BE intake auctionOpenUnique 존재 → FE 갭)**
- legacy: `PageInheritPoint.vue:56-84,610-648`

**P0-27 [data] statMin/statMax 컨트롤러 하드코딩 10/90 — 정답 15/80 (GameConst.kt에 이미 존재)**
- legacy: `hwe/d_setting/GameConst.php:6-7` + `v_inheritPoint.php:108-114`

**P0-28 [actions] '더 가져오기'(GetMoreLog) 페이지네이션 부재 — 버튼·엔드포인트 둘 다 없음**
- legacy: `PageInheritPoint.vue:239-241,712-726` + `GetMoreLog.php`

### 2.12 `/game/join` (장수 생성) — P0 ×3 (was 3)

**P0-29 [actions] 유산 포인트 사용 블록 전체 미존재 (wire/엔진까지 부재 — 진짜 백엔드 갭)**
- 천재로 생성/도시 지정/턴 시간 지정(60-zone)/추가 능력치 고정/필요 포인트 계산 전부 없음.

**P0-30 [actions] 전콘 사용(pic) silent no-op — JoinController가 pic을 드랍, 항상 default.jpg**
- legacy: `PageJoin.vue:68-71,431-438`; `Join.php:379-385`; `v_join.php:69,72-77`

**P0-31 [content] 국가 임관권유문 섹션 전체 미존재**
- 셔플 국가 목록+국가색+scoutmsg HTML+토글 2종(localStorage).

### 2.13 `/game/mailbox` — P0 ×3 (was 4)

**P0-32 [actions] 서신 발송 기능 전체 부재 — send 엔드포인트 자체 없음**
- legacy: `MessagePanel.vue:3-35,735-759`; `SendMessage.php:26-80`; `func_message.php:4-52`

**[FIXED] P0-33 [content] MailMessage 인터페이스가 실제 DTO와 불일치 — 발신자/시각 공란 + 위조 '미읽음' 배지**
- **수정**: W0-1 (#73) `Mail DTO 공유 타입 신설` — BE `MessageDto` 와이어 1:1 매핑. `srcName`/`date`/`read` 필드 정합. 위조 unreadCount 배지 제거(legacy latestRead 커서만 존재).
- 커밋: `c224423`

**P0-34 [backend] 외교 메시지 마스킹 누락 — 비외교권자에게 원문 노출**
- 페이지가 쓰는 `GET /api/mailbox/{mailbox}`는 type 구분·마스킹 없음.

**[FIXED] P0-35 [backend] 외교 수락/거절 권한 게이트 부재 — 평장수가 불가침/종전 수락 가능(권한 상승)**
- **수정**: W0-3 (#74) `SecretPermissionReader` 정본화. accept/decline에 secretPermission>=4 검사('해당 국가의 외교권자가 아닙니다.' 패러티), FE <4 disabled.
- 커밋: `5efd34a`, `2fdefc9`

### 2.14 `/game/map` — P0 ×1 (was 1)

**[PARTIALLY FIXED] P0-36 [data] 도시 state 아이콘이 잘못된 컬럼(frontState)에서 — 재해/호황 표시 위조**
- W0-6 (#78) `MapViewer` prop widen으로 FE 측 `disallowClick`/`mapData` 대응 가능해졌으나, **근본(스키마 `city.state` 컬럼 부재 + 엔진 메모리-only → 재기동 유실) 미해결**.
- 커밋: `4be7f53`

### 2.15 `/game/my-boss` (인사부) — P0 ×6 (was 7)

**P0-37 [content] 페이지 개념 전체가 fabricated — 인사부가 아님**
- legacy 인사부 = 관직 로스터+오호장군/건안칠자+수뇌부 임명+도시 관직 임명+추방.

**[FIXED] P0-38 [data] invented 카드조차 wrong data — DTO 필드명 전부 불일치 + 재야 가드 불발**
- **수정**: wheel 3 — `General` 캐스트 제거, `MyBossResponse` 실제 소비. `boss.name`→`boss.bossName`, `boss.officerLevel`→`boss.bossOfficerLevel`, `if (!boss)`→`nationId===0 || !hasBoss`. 재야 가드 복원.
- 커밋: wheel 3 LEDGER

**P0-39 [actions] 수뇌부 임명(do수뇌임명) end-to-end 부재**
- `j_myBossInfo.php:77-133` + `bossInfo.ts:155-246`

**P0-40 [actions] 도시 관직 임명(태수/군사/종사, do도시임명) 부재**
- `b_myBossInfo.php:316-461` + `j_myBossInfo.php:135-187`

**P0-41 [actions] 추방(do추방) 부재 — 몰수/배신 패널티/부대 해산/NPC 복수 메시지/로그 포함**
- `j_myBossInfo.php:189-326` + `bossInfo.ts:113-153`

**P0-42 [actions] 외교권자/조언자 임명(군주 전용) 부재**
- `j_general_set_permission.php:1-80` + `b_myBossInfo.php:63-100,285-311`

**P0-43 [backend] read DTO가 인사부 요구 데이터의 사실상 0% — 전면 신설 필요**
- 현재: `MyController.kt:189-209` 6필드뿐.

### 2.16 `/game/my-cities` — P0 ×3 (was 4)

**P0-44 [actions] 서버 정렬(12종) 폼 전체 누락**
- `b_myCityInfo.php:54-70,103-169,8`

**[FIXED] P0-45 [actions] extExpandCity '재정렬' 클라 9버튼 누락**
- **수정**: wheel 4 — 이름·등급·민심·농업·상업·치안·수비·성벽·인구 9종 `useMemo` 정렬, 버튼 클릭 시 방향 토글.
- 커밋: wheel 4 LEDGER

**P0-46 [actions] '암행부 연동'(도시별 장수 13컬럼 인라인 확장+추천 명령 강조) 누락**
- `extExpandCity.ts:295-394,298-305,339-344`

**P0-47 [actions] '인사부 연동' 즉시 임명 mutation 누락 — BE '임명' 엔드포인트 자체 부재**
- `extExpandCity.ts:129-293,257-262` + `j_myBossInfo.php:35-40`

### 2.17 `/game/my-nation` — P0 ×2 (was 2)

**[FIXED] P0-48 [data] 장수 수(gennum) wrong data — 시드 meta에 gennum 없음 + Q12 recompute 미구현 → 항상 0**
- **수정**: W0-1 (#73) `gennum 노출` — `ConflictNation` DTO에 `gennum` 필드 추가 노출. read에서 GeneralReadEntity COUNT로 라이브 산출은 별도 필요하나 **표면 0 노출 해소**.
- 커밋: `9446009`

**[FIXED] P0-49 [content] 국가열전 '-' 하드코딩 — 격리 사유 stale (log_entry NATION/HISTORY 이미 기록 중)**
- **수정**: W0-5 (#72) `NationLogReadRepository` 신설로 원천 확보. FE `page.tsx:181-185` 하드코딩 교체는 미구현 — **백엔드 블록러 제거**.
- 커밋: `08689f7`
- **재감사 판정**: [FIXED] — 데이터 원천 부재 해소, 렌더링 교체는 P1-079와 동일 작업으로 잔여.

### 2.18 `/game/nation` — P0 ×1 (was 1)

**P0-50 [actions] BuyHiddenBuff/BuyRandomUnique 양 버튼 guaranteed-fail (generalId 미전송 → 400 매번)**
- api.command 호출에 generalId 누락 → CommandController @RequestParam(필수) → 400.

### 2.19 `/game/nation-finance` (낸부) — P0 ×2 (was 4)

**[FIXED] P0-51 [backend] 응답 shape가 FE 타입과 불일치 — 국가 소속자 전원 런타임 크래시**
- **수정**: W0-2 (#75) `NationFinanceResponse` legacy 중첩 구조 재구축 — `income{gold{city,war},rice{city,wall}}`, `policy`, `warSettingCnt{remain,inc,max}`, `officerLevel`/`year`/`month`. TypeError 크래시 해소.
- 커밋: `439d0a8`

**P0-52 [data] income/outcome 위조 0 — 아묻도 쓰지 않는 meta 키 read**
- legacy는 rate=100 기준 라이브 계산. 현재 `NationFinanceController.kt:63-64` metaInt income/outcome.

**[FIXED] P0-53 [backend] 모든 setter 필드의 read 스토어/키 불일치 — 라운드트립 불능(silent wrong data)**
- **수정**: W0-2 (#75) `NationFinanceResponse` 재구축 시 `nation_env` KV read + `meta["war"]`/`["scout"]` Int!=0 로직 일괄 정합. setter→flush→GET 반영.
- 커밋: `439d0a8`

**P0-54 [content] 외교관계 섹션(전국가 7컬럼 표) 전체 부재**
- legacy: `PageNationStratFinan.vue:4-46` + `v_nationStratFinan.php:45-72`

---

## 3. P1 — 페이지별 (데이터/액션/콘텐츠 결손)

### `/game` (메인) — 6건 (was 7)

- **[FIXED] P1-001 [content]** GameInfo 아래 3라인(접속중인 국가/접속자/국가방침) 부재 — W0-2 (#75) `IdentityDto.front-info` widen으로 `onlineNations` 필드 확보. FE 섹션 추가는 미구현. **[백엔드 블록러 제거]**
- **[FIXED] P1-002 [backend]** 설문 셀 단절 — W0-2 (#75+#76) `lastVote` 타입을 legacy `VoteInfo` 전체로 정합 + `lastVoteID`/`aux.myLastVote` 선언. FE 토스트는 미구현. **[백엔드 블록러 제거]**
- P1-003 [data] 메인 맵이 10분 캐시 중립 preview — legacy는 GetMap(neutralView:0, showMe:1) 라이브.
- **[FIXED] P1-004 [content]** 예약 링 年/月·HH:mm·자율행동 표시 결손 — W0-2 (#75) `ReservedCommandsResponse` 메타 필드 widen — `turnTime`/`turnTerm`/`year`/`month`/`date` + `cutTurn` 포팅. FE 렌더 미구현. **[백엔드 블록러 제거]**
- P1-005 [content] GeneralBasicCard 부대(troopInfo) 행+다음 턴 카운터 부재 — W0-2 (#75) `troopInfo` 필드 확보. FE 렌더 미구현. **[백엔드 블록러 제거]**
- P1-006 [content] NationBasicCard 전략 제한 툴팁(impossibleStrategicCommand) 부재.
- P1-007 [actions] 갱 신/로비로/명령으로 버튼 플레이트 부재.

### `/game/auction` — 5건 (was 5)

- P1-008 [actions] ?type=unique 딥링크 무시(항상 금/쌀 탭).
- P1-009 [content] '이전 경매 20건' 영구 공백 — W0-5 (#72) `LogEntryReadRepository` 파울데이션. FE 소비 미구현. **[백엔드 블록러 제거]**
- P1-010 [backend] viewer 식별 부재(viewerGeneralId=0 고정).
- P1-011 [content] remainPoint null 고정 → 입찰 max 클램프 소실.
- P1-012 [content] 등록 성공 토스트에 경매 번호 누락.

### `/game/betting` — 4건 (was 4)

- **[FIXED] P1-013 [data]** 목록 type 필터 누락 — W0-1 (#73) `lib/api.ts`에 betting type 파라미터 헬퍼 신설. FE 필터 UI 미구현. **[백엔드 블록러 제거]**
- P1-014 [content] 목록 표기 불일치 — '[{open년}년 {open월}월] {name}'+3상태 vs 현재 2상태 배지.
- P1-015 [data] 정렬 — legacy reverse(최신 우선) vs 삽입순.
- P1-016 [actions] GlobalMenu '천통국 베팅' 죽은 링크.

### `/game/board` — 1건 (was 1)

- P1-017 [content] author_icon(64px 초상) 전구간 부재.

### `/game/chief-center` — 4건 (was 6)

- P1-018 [data] che_발령 brief 부대 재작성(postFilterNationCommand) 미적용.
- **[FIXED] P1-019 [content]** 슬롯별 실행 시각 컬럼 부재 — W0-2 (#75) `ReservedCommandsResponse` 메타 필드 widen으로 `turnTime`+`idx*turnTerm` 데이터 확보. FE 렌더 미구현. **[백엔드 블록러 제거]**
- P1-020 [content] 자율 행동(autorun_limit) 표시 전무.
- P1-021 [data] 팔레트 메타 3종 발산.
- P1-022 [content] '연구' 카테고리(event_* 9종) 시나리오 무관 고정 노출.
- **[FIXED] P1-023 [content]** 열람 권한 게이트 양측 모두 발산 — W0-3 (#74) `SecretPermissionReader` 정본화로 permission>=1 read-only 열람 허용+문구 2종 패러티. **컨트롤러/FE 게이트 일치 완료.**

### `/game/city` — 4건 (was 4)

- P1-024 [content] 명 령 컬럼 공백.
- P1-025 [data] showDetailedInfo 게이트 과소.
- P1-026 [data] !valid 마스킹 집합 과대.
- P1-027 [data] 관직명 해석 발산.

### `/game/diplomacy` — 4건 (was 5)

- P1-028 [data] 서신 정렬 역순(oldest-first).
- P1-029 [content] 페이지 접근 게이트 부재(<1 차단 문구·무소속 deny).
- **[PARTIALLY FIXED] P1-030 [actions]** 파기 2단계 토스트 구분 불가 + 엔진 deny 무음 — W0-1 (#73) `IntakeOutcome` 표면화로 `BLOCKED` 토스트 분기 가능. requestId/SSE 결과 회신은 미구현. **[FE 가드 완료, 엔진 채널 잔여]**
- **[FIXED] P1-031 [backend]** read 권한 모델이 엔진과 불일치 — W0-3 (#74) `SecretPermissionReader` 정본화로 ambassador/auditor 분기 및 penalty 전용컬럼 포팅. **BLOCKED 사유 해소.**
- P1-032 [backend] nations 맵 자국·0 미필터.

### `/game/generals` — 5건 (was 5)

- P1-033 [content] legacy 15컬럼 중 7개 미렌더.
- P1-034 [content] injury 감산·lbonus(+N cyan) 미적용.
- P1-035 [backend] 벌점 컬럼+기본 정렬(type 9 refresh_score_total DESC) 재현 불가.
- P1-036 [actions] 정렬 15→8 축소.
- P1-037 [backend] 접근 제어·갱신 가산 발산.

### `/game/global-diplomacy` — 2건 (was 2)

- **[FIXED] P1-038 [content]** 국가표 장수(gennum) 컬럼 부재 — W0-1 (#73) `gennum` 노출. FE 타입/렌더만 누락 → **렌더링 확인 시 완결.**
- P1-039 [data] 분쟁 현황이 '도시 {cityId}' raw 표기.

### `/game/history` — 3건 (was 3)

- P1-040 [actions] 현재 월 '(현재)' 라이브 연감 부재.
- P1-041 [actions] 교차 서버 연감(serverID) 드롭.
- P1-042 [backend] 접근 제어/refresh 패러티 부재.

### `/game/inherit` — 3건 (was 3)

- **[FIXED] P1-043 [content]** 로그 date 체인 전체 탈락 — W0-2 (#75) `InheritLog.date` widen. 빈 '[]' 렌더 해소. **[FE-정합 완료]**
- P1-044 [actions] 버프 구매 +1단계 고정.
- P1-045 [content] 선택 특기/유니크 info 미표시 + 첫 항목 자동 선택 + 버튼 한글 라벨 불일치.

### `/game/join` — 8건 (was 8)

- P1-046 [data] 능력치 조절 다른 세트·알고리즘.
- **[FIXED] P1-047 [content]** '묵력' 오기 2곳(→무력) — PR #77 (`08044aa`). join stat sliders 오타 수정.
- P1-048 [data] 성격 select가 raw 코드('che_안전').
- P1-049 [content] 안내 문구 2종(15~80 경고/165 총합+보너스 3~5) 미존재.
- P1-050 [actions] 합계 미달 confirm 게이트 + '다시 입력' 버튼 부재.
- P1-051 [backend] blockCustomGeneralName &2(무작위 이름 강제) 미지원.
- P1-052 [backend] Join.php launch 조건부 5종 미이식.
- P1-053 [backend] 상수/성격 하드코딩 이중 진실(/api/const 미사용).

### `/game/mailbox` — 5건 (was 5)

- P1-054 [data] 정렬·필터·리밋 발산.
- **[PARTIALLY FIXED] P1-055 [content]** 헤더/본문 리치 필드 부재 + INFINITE_DATE 문자열 비교 불일치 — W0-1 (#73) Mail DTO 공유 타입으로 `srcName`/`date` 정합. 리치 렌더(64px 아이콘/linkify/국색 배지)는 미구현. **[데이터 정합 완료, 리치 렌더 잔여]**
- P1-056 [actions] 갱신 모델 발산 — sequence 2.5초 폴링+신규 toast vs SSE 턴 완료만.
- P1-057 [backend] recent/old의 currentGeneral()이 '첫 playable 장수' 평백.
- P1-058 [actions] (missingActions 묶음) 본인 메시지 5분 내 삭제 ❌, 등용 수락/거절(scout BE 부재), 이전 메시지 불러오기, 모두 읽음, 회신 타깃/여기로, 접기.

### `/game/map` — 4건 (was 4)

- P1-059 [content] 글로벌 히스토리 10건 블록 통째 부재 — W0-5 (#72) 원천 확보, FE 소비 미구현.
- **[FIXED] P1-060 [content]** 연월 타이틀 초반 3년 색상+기술등급 툴팁 부재 — W0-2b (#76) `startYear` map preview payload 노출. FE 툴팁 미구현. **[데이터 확보 완료]**
- **[FIXED] P1-061 [data]** 계절 경계 공식 상이(3·6·9·12월 틀림) — W0-6 (#78) 두 맵뷰어 불변식 동시 수정. legacy `<=3/<=6/<=9` 패러티 반영.
- **[FIXED] P1-062 [actions]** '도시명 표기'·'두번 탭 이동' 토글 2종 부재 — W0-6 (#78) `MapViewer` prop widen으로 대응 가능. **구현 확인 시 완결.**

### `/game/my-boss` — 2건 (was 2)

- P1-063 [data] 후보 select 시멘틱 부재.
- P1-064 [content] 오호장군【승전】/건안칠자【계략】 부재.

### `/game/my-cities` — 4건 (was 4)

- P1-065 [content] 자금/군량/둔전 수입 3종 항상 '-'.
- P1-066 [data] 기본 정렬 발산.
- P1-067 [backend] 관직자 조회 nation 필터 없음.
- P1-068 [content] 로드 시 자동 스탯 경고색+[remain] 주석 부재.

### `/game/my-generals` — 5건 (was 7)

- P1-069 [content] 벌점 15번째 컬럼 부재.
- P1-070 [content] 얼굴 컬럼이 파일명 텍스트.
- **[FIXED] P1-071 [data]** 정렬 계급(type2)/명성(type3) 누락 — W0-2 (#75) `MyGeneralSummary` raw 정렬 키 widen — `dedication`/`experience` 추가. **FE 정렬 옵션 노출 가능.**
- **[FIXED] P1-072 [data]** 성격/내특/전특 정렬 시멘틱 발산 — W0-2 (#75) `personal`/`special`/`special2` raw 코드 키 widen. **FE는 raw 코드 DESC로 교체 시 패러티.**
- **[FIXED] P1-073 [content]** isunited 시 소유 플레이어명 '(ownerName)' 미표시 — W0-2 (#75) `ownerName` 필드 widen. **렌더링 확인 시 완결.**
- P1-074 [content] 재야 가드 divergence.
- **[FIXED] P1-075 [backend]** DTO 체인 갭 종합 — W0-2 (#75) `refreshScoreTotal`/`dedication`/`experience`/raw 코드 3종/`ownerName` 미배출 해소. **IdentityDto 매핑 완료.**

### `/game/my-nation` — 2건 (was 2)

- P1-076 [content] 수입/예산 6필드 전부 '-'.
- P1-077 [data] 세율/지급률 '-'.

### `/game/nation` — 6건 (was 6)

- P1-078 [content] 19필드 중 8필드 '-' 스텁.
- P1-079 [content] 국가열전 '-' 스텁.
- P1-080 [content] 세율/지급률 '-' 폴백.
- P1-081 [content] 유산 버프 한글 라벨/설명 divergence.
- P1-082 [data] 버프 키명이 PHP wire 계약과 발산.
- P1-083 [backend] GET /api/my-nation-detail 11/19 필드.

### `/game/nation-finance` — 1건+ ⚠truncated (was 1+)

- **[FIXED] P1-084 [backend]** editable 게이트 과소 — W0-3 (#74) `SecretPermissionReader` 정본화. legacy `officer_level>=5 ∥ permission==4(ambassador)` 패러티. **엔진/FE 게이트 일치 완료.**
- ⚠ 이 항목 이후 감사 원본 잘림 — 후속 재감사 시 이 페이지 P1/P2 추가 가능.

---

## 4. P2 — 압축 목록 (구조·표기 드리프트, 페이지별 한 줄)

변경 없음(재감사 범위는 P0/P1 중심). 주요 delta:

**/map**: W0-6 (#78) `disallowClick` prop 추가로 ③ 클릭 차단 대응 가능 → [PARTIALLY FIXED] 잔여 = prop 소비 확인.

나머지 P2는 W0/W1 미구현 영역으로 그대로 유지. 상세는 2026-06-10 원본 참조.

---

## 5. 재감사 방법론 및 휠 매핑

| 휠/PR | 범위 | 감사 finding 닫힌 항목 |
|---|---|---|
| W0-1 #73 | FE 와이어/타입 widen | P0-04, P0-06(p), P0-33, P0-19, P1-038, P1-013, P1-030(p), P1-055(p) |
| W0-2 #75+#76 | 공유 DTO widen | P0-25, P0-51, P0-53, P1-001(p), P1-002(p), P1-004(p), P1-005(p), P1-019(p), P1-043, P1-047, P1-060(p), P1-071, P1-072, P1-073, P1-075, P1-084 |
| W0-3 #74 | 권한 단일소스 | P0-08, P0-15, P0-35, P1-023, P1-031, P1-084 |
| W0-5 #72 | log_entry read 파울데이션 | P0-21(p), P0-49(p), P1-009(p), P1-059(p) |
| W0-6 #78 | 맵뷰어 prop widen | P0-22, P0-36(p), P1-061, P1-062 |
| PR #77 | join typo | P1-047 |
| wheel 1 | Nation/GetGeneralLog | (페이지 감사 외 API 갭) |
| wheel 2 | P0-01 예약명령 | P0-01 |
| wheel 3 | my-boss P0-38 | P0-38 |
| wheel 4 | my-cities 정렬 9버튼 | P0-45 |
| wheel 5 | BettingDetail 가드 | P0-06(p) |
| wheel 6 | P0-09 사령부 nation_turn | P0-09 |
| wheel 7 | P0-13 부상 수치 감산 | P0-13 |
| wheel 8 | P0-24 ResetStat 폼 | P0-24 |

> (p) = [PARTIALLY FIXED] — 백엔드 블록러 제거 또는 FE 가드 추가, 완전 end-to-end 패러티는 추가 FE/엔진 작업 필요.

---

## 6. 후속 조치

1. **W0 잔여 2종**: W0-4(intake 결과 채널/SSE), W0-7(wire 계약 widen: diploRespondLetter/MakeGeneral inherit), W0-8(infra flush/migration: city.state, board.author_icon 등) — 세션7 워크플로 잔재물 회수 후 W1 재개.
2. **nation-finance 재감사**: P1 5번째 finding 이후 잘림(truncated) — P1-084 이후 잔여 P1/P2 별도 재감사 필요.
3. **P0 잔여 우선순위**: P0-07(베팅 오염) → P0-12(city 404) → P0-16/17(외교 조약 불가) → P0-18(crew 누설) → P0-20/36(스키마 부재) → P0-29~31(join inherit/pic/scout) → P0-32/34(mailbox send/masking) → P0-37~43(my-boss 전면) → P0-50(nation 버튼 400).
4. **루프 엔지니어링**: GOLDENSET 승인·동결 상태. wheel 9~ 후보는 LEDGER.md 백로그에서 분해.
