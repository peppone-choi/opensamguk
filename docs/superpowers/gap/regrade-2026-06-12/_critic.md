# Completeness Critic — regrade-2026-06-12 (6개 영역 리포트 교차 검증)

입력: audit-delta.md · nation-finance.md · command-registry.md · api-surface.md · design-parity.md · engine-write-path.md
역할: (1) 미측정 표면 열거 (2) 리포트 간 모순 적발 (3) 차기 10 루프 휠 제안.

---

## 1. 이번 6개 리포트가 측정하지 않은 패러티 표면 (UNMEASURED)

| # | 표면 | 비고 (가장 가까운 스침) |
|---|---|---|
| U-1 | **P3 월틱 이벤트** — `MonthlyPipeline.runMonth()` + PostUpdateMonthly Q1~Q17 + 9 world event leaves(ProcessIncome/RaiseDisaster/UpdateCitySupply/ProcessSemiAnnual…) 행동 재채점 | engine-write-path D2(registerAuction no-op)만 스침. 월틱 RNG draw-for-draw 재검 0건 |
| U-2 | **P4 전투 엔진** — processWar 단일 RandUtil(warSeed) draw 패러티, ConquerCity, 전투 아이템/특기 | 어느 리포트도 미접근 (G1 게이트는 과거 통과 기록뿐, 회귀 측정 없음) |
| U-3 | **P5 NPC AI** — 4-layer autorun, candidateAllowed, **long-sim multi-turn(gate dim c, 공식 백로그)** | nation-finance가 `AiTurnAdapter.kt:1619-1621` nation_env 공백 영향 반경만 메모 |
| U-4 | **flush/restart-rehydrate lossless 게이트** (P6 P8-coupled 백로그) — ChangeRecorder 전 채널↔WorldSnapshotLoader 왕복; prod 실측 이슈 "배포마다 턴 되감김"의 정본 검증 | engine-write-path는 betting/auction 채널만 측정. general/city/diplomacy/troop flush 채널 매트릭스 미측정 |
| U-5 | **게이트웨이 인증(F0)** — JWT/BCrypt, httpOnly 쿠키, AdminSeeder, 로비/어드민 페이지 | 전무 |
| U-6 | **SSE/실시간** — turnCompleted SSE(RealtimeRelayController) + FE 소비, 폴링 대체 동작 | api-surface F-13에서 ExecuteEngine divergence로 언급만 |
| U-7 | **턴 데몬 케이던스/수명주기** — 1h=1순, TurnRunService 장수 턴 순서, nation-pass-before-general | 전무 |
| U-8 | **B1-B3 장수 생성/빙의/선택** — MakeGeneralHandler·SelectNpcToken 행동 패러티(골든), v_join·select-pool 페이지 | api-surface는 Join 표면 OK만(행동 미측정), design-parity는 join/select-pool 명시 제외(§한계) |
| U-9 | **rankings a_* 7페이지 · admin1/2/5/7/8 · tournament-admin · map 페이지** | design-parity §한계에서 "표면 점검만" 자인 |
| U-10 | **토너먼트 엔진(W8)** — 예선 풀리그/베팅 결산 로직 | 페이지 라벨 verbatim 확인만 |
| U-11 | **vote RNG 골든** + `tryUniqueItemLottery` 횡단(34/55 커맨드) 행동 검증 | command-registry F6이 부재 사실만 기록, 측정 아님 |
| U-12 | **시나리오 시드 전필드 패러티** — ScenarioImporter vs PHP install dump 컬럼 단위 대조 | nation-finance NF-P1-C가 nation policy 5키만 스팟 체크 |
| U-13 | **모바일/반응형 + 런타임 비주얼 QA** — 6개 전부 정적 코드 대조, 브라우저 실행 0회 | design-parity §한계 자인("동적 렌더 미실행") |
| U-14 | **LogHistory 월별 writer / general_record·world_history 테이블 설계**(P0-20·F-8 원천) — "부재"는 적발됐으나 써야 할 스키마/순서의 PHP 대조 미수행 | api-surface F-8, design-parity P1-8 |
| U-15 | **checkStatistic(Tier1) · 진입 3버튼 · Tier4 15명령**(세션 메모리 백로그) + nginx/배포 드리프트(untracked live compose) | 전무 |

요지: 이번 6개는 **read-API·FE·경제 mutation**에 집중됐고, **틱·전투·AI·영속화·실행 환경**이라는 엔진 코어 4축 + 런타임 검증 전체가 사각이다.

---

## 2. 리포트 간 모순 (CONTRADICTIONS)

| # | 모순 | 판정 |
|---|---|---|
| C-1 | **SetBlockWar**: api-surface 표는 `Nation/SetBlockWar → OK`(CommandWireMapper.kt:51) vs nation-finance NF-P0-C **100% guaranteed-deny**(nation_env 영구 0). api-surface 자체 룰("명백한 차단 요인이 없음")에 비춰도 OK는 오판 | nation-finance 우세. api-surface 표 정정 필요 |
| C-2 | **Auction/OpenUniqueAuction**: api-surface `OK`(근거 = FE api.ts:490-494 헬퍼) vs audit-delta P0-26 NOT-FIXED — 실제 FE 호출처(inherit/page.tsx:344)는 그 헬퍼를 **안 쓰고** 미등록 코드로 휴식 턴 예약. 미사용 헬퍼를 도달 근거로 인정한 오류 | audit-delta 우세 |
| C-3 | **P0-32 SendMessage**: audit-delta 잔여 P0 표에 P0-32 유지("재분류 여지" 단서) vs api-surface F-0은 P1로 강등(BE 닫힘 확인). 같은 항목 이중 등급 | api-surface 우세(BE intake+MessageHandler 실증). 잔여 P0 32→31 재계산 필요 |
| C-4 | **GetMoreLog**: audit-delta는 P0-28로 잔여 P0 유지 vs api-surface F-10은 동일 갭을 **P2**로 등급. 동일 사실, 등급 2단계 차이 | 룰 통일 필요(FE 미호출이면 P0 불가 — api-surface 룰 적용 시 P2) |
| C-5 | **Message 컨트롤러 커버리지 홀**: api-surface Message 섹션(7 endpoint)은 `GET /api/messages/{id}`·`/api/mailbox/{mailbox}`·`/unread`를 표에 아예 안 올려 사실상 OK처럼 보이나, audit-delta F-2(P0 over-mask 회귀)·F-3(P0 단건 누출)이 바로 그 컨트롤러에서 발견됨 | api-surface는 PHP 81개 기준 매핑이라 Kotlin-only endpoint가 사각 — 매핑 방향(Kotlin→PHP) 역방향 감사 부재가 구조적 원인 |
| C-6 | **Auction Bid\***: api-surface `OK`(":140 tryExtendCloseDate 포함"을 패러티 근거처럼 인용) vs engine-write-path B9 — 그 연장 semantics가 **역전**(조건부 연장+무조건 카운트 차감)이고 B1/B2 자원 복제 P0. 룰상 "deep 제외"라지만 OK 셀이 근거로 deep 사항을 인용한 것은 자기모순 | engine-write-path 우세 |
| C-7 | **P1-084 대장 기록**: nation-finance NF-P1-A가 2026-06-11 대장 [FIXED]를 반증(컨트롤러 미배선, `NationFinanceController.kt:79`) — audit-delta의 닫힌 항목 재검 범위(휠 11-19 커밋)에 빠져 있어 교차 미적발 | 대장 정정 + audit-delta 재검 범위에 "이전 FIXED 표본 추출" 추가 권고 |
| C-8 | **chief-center 패러티 등급**: command-registry F5 — chief 팔레트/게이트가 PHP에 없는 "연구" 카테고리 9종 예약 허용(divergence, KDoc은 byte-for-byte 허위 주장) vs design-parity 닫힌항목 표는 chief-center를 일치/양호로만 기록 | 동일 표면에 silent 불일치 — design-parity가 팔레트 구성을 미대조 |
| C-9 | **발명 표면 등급 내부 비일관(design-parity)**: P0-D(외교 빠른 명령 — 실재 명령 기반 발명 UI)는 P0, P2-15/16(mailbox·world-log — 동급 발명 파생 페이지)은 self-doc 있으면 P2. "문서화 여부"가 등급 축이라면 룰에 명문화 필요 | 룰 명문화 |
| C-10 | **P0 합산 중복**: betting 갭이 audit-delta(P0-07)·api-surface(F-1)·engine-write-path(A2~A8)에 3중 계상, nationsList 갭이 audit-delta(P0-54)·nation-finance(NF-P0-A)·design-parity(P1-3)에 3중 계상. 6개 리포트 P0 단순합(21)은 dedup 전 수치 | 통합 대장에서 root-cause 단위 dedup 필수(실 고유 P0 ≈ 15±2) |

---

## 3. 차기 10 루프 휠 (가치순, 휠 = 가설 1개 + grader 1개)

> 선정 원칙: ① prod 턴 동결 트리거(단일 유저 액션 1건으로 발화) > ② 자원 복제/경제 오염 > ③ 라이브 위조·기밀 누출 > ④ 전원-크래시/항상-실패 > ⑤ 위조 금지 룰 위반. §1의 미측정 표면(틱/전투/AI/리하이드레이트)은 회귀 측정 휠로 후속 배치 권고 — 아래 10개는 이미 적발된 결함의 마감 휠.

| 휠 | 가설 (ONE hypothesis) | grader |
|---|---|---|
| **W-1** | betting/auction 핸들러의 non-parity 로그 push(scope="action"/category="betting\|auction")를 제거(PHP는 해당 경로 무로그)하면 베팅·입찰·경매만료 1건으로 발생하는 flush BatchUpdateException(턴 동결)이 소멸한다 — PlaceBetHandler.kt:69-76, AuctionBidHandler.kt:206-214, AuctionFinalizeHandler.kt:116-235, AuctionExpiryDaemon.kt:60-66 vs V1__baseline.sql:3-4 | real-Postgres IT(Testcontainers): bet+bid+finalize+expiry 각 1건 기록 후 flush green + ng_log에 해당 경로 로그 row 0건(PHP 무로그 패러티) + 전 핸들러 emit scope/category가 V1 enum 집합의 부분집합임을 assert |
| **W-2** | `bettingInsertMany`를 plain INSERT에서 `ON CONFLICT (general_id,betting_id,betting_type) DO UPDATE amount = amount + EXCLUDED.amount`로 바꾸면 PHP insertUpdate(Betting.php:162-166) 의미가 보존되고 재베팅발 unique-위반 flush 폭사가 소멸한다 — JdbcFlushExecutor.kt:890-907 + V7 UNIQUE | real-Postgres IT: 동일 (general,betting,type) 2회 베팅 → flush green + amount 합산값 assert |
| **W-3** | `applyDiplomacyMask`에 message-type 게이트(diplomacy 한정)를 추가하고 `GET /api/messages/{id}`에도 적용하면 GetRecentMessage.php:125-139 패러티가 복원된다(F-2 over-mask 회귀 + F-3 단건 누출 동시 해소) — MailboxController.kt:358-364, :70-75 | 컨트롤러 테스트 매트릭스: permission<3 장수 기준 private/national 원문 유지 + diplomacy만 '(외교 메시지입니다)' — `/api/mailbox/{mailbox}`·`/unread`·`/api/messages/{id}` 3경로 전부 |
| **W-4** | AuctionBidHandler에 PHP myPrevBid 유효성 규칙(최고입찰==내 row면 환불 금지 + 환불된 이전입찰은 null 처리, Auction.php:399-405,450-452)을 이식하면 자기-재상향 자원 복제(B1)와 stale 차액 under-deduct(B2)가 소멸한다 — AuctionBidHandler.kt:81-156 | 시나리오 유닛 테스트: (a) 자기-재상향 → 환불 0 + 차액만 차감, (b) outbid 후 재입찰 → 전액 차감 — 각 케이스에서 시스템 총자원 보존(complete conservation) assert |
| **W-5** | PlaceBetHandler에 Betting::bet 검증·부수효과 전량(BettingInfo 가드 5종 A2 + purifyBettingKey canonical化 A3 + 1000 한도 A4 + 금 플로어 500 A5 + 유산포인트 분기 A6 + rank_data A7)을 verbatim 포팅하면 mutation 오염(P0-07)이 종결된다 — PlaceBetHandler.kt:33-84 vs Betting.php:100-183 | deny 문자열 byte-assert(Betting.php 대비) + canonical key 테스트(`[2,1]`과 `[1,2]`가 동일 betting_type으로 저장) + 유산 분기 KV/rank 기록 assert; 이상적으로 P6 betting 골든 캡처 스크립트로 게이트 |
| **W-6** | FE 타입을 `income/outcome` nullable로 정정하고 null 시 BLOCKED 표기 가드를 넣으면(위조 0 아님) 국가 소속자 전원 nation-finance 렌더 크래시(NF-P0-B)가 소멸한다 — types/game.ts:798-799, page.tsx:134,142 | `tsc --noEmit`(nullable 강제 후 컴파일 green) + 렌더 테스트: income=null 응답에서 TypeError 0건 + BLOCKED 표기 렌더 |
| **W-7** | NationFinanceController에 SecretPermissionReader를 배선(read = 자국 + permission≥1, editable = officerLevel≥5 ∥ permission==4)하면 타국 재정 기밀 누출(NF-P0-D)과 P1-084(ambassador) 미배선(NF-P1-A)이 동시에 닫힌다 — v_nationStratFinan.php:27-34,128 vs NationFinanceController.kt:50-79 | 컨트롤러 테스트 매트릭스: 타국 {id} GET deny, 사관년도 미달 평장수 deny("권한이 부족합니다…" byte-match), ambassador editable=true, 군주 editable=true |
| **W-8** | nation_env read 채널(WorldSnapshotLoader가 nation_env 테이블→`nation.meta["nation_env"]` 머지 + recordNationEnvKv 시 in-memory 동기 갱신 + game-api read repo)을 신설하면 setBlockWar 100% deny(NF-P0-C)와 nationMsg/scoutMsg/remain 영구 null(NF-P1-B)이 같은 뿌리로 닫힌다 | 엔진 IT: nation_env에 available_war_setting_cnt 시드 → setBlockWar 성공 + 동일 업타임 내 차감 가시 + GET nation-finance에서 nationMsg/remain 라운드트립 assert |
| **W-9** | inherit 유니크 경매 제출을 정본 intake(`api.commandQueue.auctionOpenUnique({itemId, amount})` + amount 입력)로 교체하고 reserve 단계에서 미등록 코드를 deny하면(RestAction 낙하 차단) P0-26 휴식-턴 위조와 그 재발 클래스가 함께 소멸한다 — inherit/page.tsx:344, CommandReserveService.kt:82-96, CommandRegistry.kt:211. **P0-23(emptyMap) 마감 전 선행 필수** | 유닛: 가짜 코드 reserve → deny(202 아님) + 93 정규 코드 전부 기존 경로 유지; e2e/API: auctionOpenUnique {itemId,amount} → intake-queued; grep 게이트: web/game에 `'OpenUniqueAuction'` 리터럴 0건 |
| **W-10** | che_선전포고 로그 6종+국메를 tools/php-golden 캡처로 채취해 byte-port하면(che_선전포고.php:148-190) 창작 문자열 위조(command-registry F1, 규칙 5 위반)가 제거된다; 캡처 불가 판명 시 창작 로그 삭제 + sibling-경로 증빙 격리로 전환 — CheSeonjeonpogo.kt:120-137 | 전용 GoldenTest: 6개 로그 문자열 + 국메 byte-assert(캡처 산출물 대비); 격리 경로 선택 시 fabricated 문자열 0건 grep 게이트 + 백로그 등재 증빙 |

순서 근거: W-1·W-2는 prod에서 유저 액션 1건으로 턴 동결을 일으키는 유일 클래스(즉시), W-3은 현재 라이브로 일반 장수 전원의 서신함을 위조 중(회귀), W-4·W-5는 경제 무결성(복제/정산 오염), W-6·W-7·W-8은 페이지 전원-크래시·기밀 누출·항상-실패 3종(nation-finance 묶음 — 동일 PR 스택 가능), W-9는 잠복 위조의 뿌리 차단(P0-23 선행 조건), W-10은 위조 금지 룰 복원. api-surface F-2(ResetStat 409)·P0-50(generalId 400)은 11~12위 차점 — W-9와 같은 inherit 표면이라 후속 휠에서 묶음 처리 권장.
