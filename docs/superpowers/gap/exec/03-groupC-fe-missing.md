# 그룹 C — FE 미포팅 실행계획 (web/game·web/gateway)

> 정본: PHP legacy = `legacy/devsam-core/hwe/*` (grand truth). divergence는 PHP가 이긴다.
> 데이터 소스: `docs/superpowers/gap/_full_audit_2026-06-07.raw.json` (jq 슬라이스) + 실파일 grep/Read 교차검증.
> 모듈 매핑 규칙: 명령=logic actions/* + CommandRegistry + intake(CommandWireMapper)+game-engine dispatcher + golden / FE=web/game(또는 gateway) / read=app/game-api controller+DTO / admin=app/gateway-api + web/gateway/app/admin.
> 빌드 금지(읽기전용 계획). 본 문서는 골든필요(RNG-bearing) 여부를 게이트 컬럼으로 명시.

## 0. 이번 감사에서 확정된 인테이크 아키텍처 사실 (계획 전제)

근거 grep/Read:

1. **인테이크 공통 시임 = `POST /api/command/{code}`** — `app/game-api/.../web/CommandController.kt:55` → `CommandReserveService.reserve(...)` → durable `general_turn` + 데몬 poke. precheck AVAILABLE일 때만 202 reserve. 즉 **새 컨트롤러를 매 명령마다 만들 필요 없음**; 명령형 mutation은 이 단일 시임으로 들어간다.
2. **wire 코드는 이미 광범위하게 존재** — `app/game-api/.../reserve/CommandWireMapper.kt` 에 `diploSendLetter`(:279) / `diploRollbackLetter`(:287) / `diploDestroyLetter`(:291) / `boardArticle`(:208) / `boardComment`(:214) / `selectPoolPick`(:296) / `selectPoolUpdate`(:303) 가 **이미 등록됨**. 따라서 이 7종은 *"BE intake 먼저"가 사실상 이미 끝났고*, 남은 일은 **(a) FE submit 호출 배선 + (b) read DTO shape 정합(카드 렌더)** 이다.
3. **반면 wire 코드가 부재한 4종** — `set_my_setting` / `vacation` / `set_npc_control(write)` / `myBoss(인사 write)` 는 CommandWireMapper에 코드가 **없음**(grep 0). 이들은 **신규 wire 코드 + logic action + game-engine 핸들러 + dispatcher + (필요시) golden** 까지 풀 인테이크 빌드가 필요 → C1 내에서 가장 무겁다.
4. **SelectPoolHandler 는 deny-only 스텁** — `app/game-engine/.../intake/SelectPoolHandler.kt:31,37` `reason="미구현"`. 픽 경로는 RNG-bearing(`allStat^1.5` 가중추첨) → **골든 필요, `/parity-wave` 이관**. update 경로는 결정론적(골든 부담 없음).
5. **SimulatorController 는 난수 스텁 + FE 키 불일치** — `SimulatorController.kt`: `(100..500).random()` 반환; FE는 `{attackerGeneralId,defenderGeneralId}` 송신, 컨트롤러는 `body["attackerId"]` 읽음 → 항상 에러 분기.
6. **utilGame(Tier-0) = ✅ 완료** — `web/game/lib/utilGame/` 16+종 존재(formatLog/techLevel/calcInjury/formatInjury/formatDexLevel/formatHonor/getNPCColor/formatOfficerLevelText/tournament 등). 본 그룹 컴포넌트는 이를 **소비**만 한다. 보류: `formatCityName`/`postFilterNationCommandGen`(GameConstStore 배선 대기), `getNewMsgToast`(Vue 전용).

---

## C1 — write 인테이크 + 컴포넌트

> "BE intake 먼저" 원칙. 단 §0-2에 따라 **두 부류로 갈린다**:
>   · **C1-α (wire 기존)**: diplo 3종 + board 2종 — FE submit + read DTO 정합만.
>   · **C1-β (wire 신규)**: set_my_setting / vacation / set_npc_control(write) / myBoss(write) — 풀 인테이크 빌드.
> 컴포넌트(AuctionResource/UniqueItem·BettingDetail·BoardArticle/Comment·TipTap)는 read 표시면이자 일부 write surface.

### C1-α  외교 서신 3종 + 게시판 2종 (wire 기존 → FE submit + DTO 정합)

| id | kind | legacy(file) | 대상 파일 (opensamguk) | 의존 | 골든(RNG) | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| j_diplomacy_send_letter | php-ajax write | `hwe/j_diplomacy_send_letter.php` + `hwe/t_diplomacy.php` + `hwe/ts/diplomacy.ts` + `hwe/j_diplomacy_get_letter.php` | FE: `web/game/app/game/diplomacy/page.tsx`(+`#newLetter` 작성 폼 신규) · api: `web/game/lib/api.ts` `diploSendLetter` · read DTO: `app/game-api/.../dto/F4Dto.kt` `DiplomacyLetter` + `DiplomacyController.kt` | wire `diploSendLetter`(:279) **기존**, 엔진 `DiplomacyLetterHandler.handleSend` **포팅됨** | **N** (메시지 분기/aux 삽입순서 결정론) | diplomacy page에서 작성→제출→202; read 카드 src/dest/state 렌더 정합(Vitest + 수동 QA) | 🔴 미배선(FE 작성 폼 부재) | (1) FE 외교 서신 **작성 폼** 신규(수신국 select=자국·재야 제외+level, 본문, 서명인 generalIcon/generalName). (2) `api.command('diploSendLetter', args)` 배선. (3) **read DTO shape 정합** — BE 평탄 `srcNationId/textBrief` ↔ FE 기대 `src{nationName,nationColor,generalName,generalIcon}/dest{...}/stateOpt/aux/state(케이싱)`. (4) `detail` permission<3 → '(권한이 부족합니다)' 마스킹. (5) 한글 행 라벨 verbatim(문서 번호/이전 문서/상태/서명인). 값 날조 0 — 단 `level` 컬럼 실존 매핑 직전 재확인. |
| j_diplomacy_rollback_letter | php-ajax write | `hwe/j_diplomacy_rollback_letter.php` + `ts/diplomacy.ts` | FE: `web/game/app/game/diplomacy/page.tsx` LetterCard · api: `lib/api.ts` `diploRollbackLetter` | wire `diploRollbackLetter`(:287) **기존**, 엔진 `handleRollback` **포팅됨**(Model A turn-reserved) | **N** (결정론) | LetterCard 회수 버튼 노출조건(state=='proposed' && src.nation==my) + confirm + 202; 알림 '회수 했습니다.' | 🔴 미배선(카드 read-only) | (1) LetterCard 회수 버튼 추가(가시성 게이트). (2) confirm('회수하시겠습니까?'). (3) `api.command('diploRollbackLetter',{...})`. (4) 성공/실패 알림 문자열 verbatim('회수 했습니다.'/'회수를 실패했습니다: …'). (5) 서명자 장수명·아이콘(aux) 표시. **BE 신규 작업 0** — 기존 인테이크로 해소. |
| j_diplomacy_destroy_letter | php-ajax write | `hwe/j_diplomacy_destroy_letter.php` + `ts/diplomacy.ts` + `j_diplomacy_get_letter.php` | FE: `web/game/app/game/diplomacy/page.tsx` LetterCard · api: `lib/api.ts` `diploDestroyLetter` · read DTO: `F4Dto.kt DiplomacyLetter`(+`state_opt` 필드) | wire `diploDestroyLetter`(:291) **기존**, 엔진 `handleDestroy`(`DiplomacyLetterHandler.kt:221-288`) **포팅됨** | **N** (1단계 요청/2단계 cancelled 전이 결정론) | 파기 버튼 노출/disable(state_opt 기반) + '파기 요청'/'파기' 2단계 라벨 + 202 | 🔴 미배선(카드 read-only) | (1) LetterCard `.btnDestroy` 추가(상호 동의 파기). (2) **read 응답에 `state_opt` 추가**(버튼 노출/진행상태 라벨 결정자 — 현 DTO 누락). (3) `api.command('diploDestroyLetter',{...})`. (4) src/dest Party 객체·brief/detail·stateOpt 로 카드 렌더 정합. (5) 발신/수신 서명 장수명·아이콘. |
| j_board_article_add | php-ajax write | `hwe/j_board_article_add.php` + `ts/components/BoardArticle.vue` | FE: `web/game/app/game/board/page.tsx`(글쓰기 폼) + **컴포넌트 `BoardArticle`(신규)** · api: `lib/api.ts` `boardArticle` · 스키마: `infra/.../V1__baseline.sql:352`(author_icon 결정) | wire `boardArticle`(:208) **기존**, 엔진 `BoardHandler.handleArticle`+`BoardActions.addArticle` **포팅됨**(검증 라인순서 EXACT) | **N** | board page 글쓰기(isSecret/title/text) 제출→202; null/blank/permission 게이트 동작; 비밀실 permission<2 차단 | 🔴 write 경로 부재(read-only) | (1) FE **BoardArticle 컴포넌트**(제목/본문/댓글목록/댓글입력) + 글쓰기 폼(isSecret 토글). (2) `api.command('boardArticle',{isSecret,title,text})`. (3) **author_icon 결정** — legacy는 INSERT+64px 초상 렌더, 현 스키마부터 DTO·FE까지 전구간 부재(parityViolation MEDIUM). 복원=마이그레이션+DTO+FE 일괄 / 미복원=백로그 결정 필요. (4) TipTap(아래) 소비. checkLimit/increaseRefresh는 QUARANTINE(P8). |
| j_board_comment_add | php-ajax write | `hwe/j_board_comment_add.php` + `ts/components/BoardComment.vue` | FE: `web/game/app/game/board/page.tsx` CommentRow · 컴포넌트 `BoardComment`(신규) · api: `lib/api.ts` `boardComment` | wire `boardComment`(:214) **기존**, 엔진 `BoardHandler.handleComment` **포팅 완료**(감사: parityViolation 0건, 게이트/INSERT/DTO/FE 1:1 일치) | **N** | 댓글 제출 {articleNo,text} maxlength 250 → 202; null/blank/존재/권한 게이트 | 🟡 **백엔드+DTO 완료**, FE 폼만 | 거의 완료 상태. (1) FE 댓글 행 3필드[작성자|본문|날짜(slice 5,16)] + '댓글 달기' 폼(placeholder '새 댓글 내용', maxlength 250, '등록'). (2) `api.command('boardComment',{articleNo,text})`. **댓글엔 author_icon 없음이 정상**(article만). date 타임존 직렬화만 별도 확인. |

### C1-β  개인/국가 설정 write 4종 (wire **신규** → 풀 인테이크 빌드)

| id | kind | legacy(file) | 대상 파일 (opensamguk) | 의존 | 골든(RNG) | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| j_set_my_setting | php-ajax write | `hwe/j_set_my_setting.php` | logic action 신규 + `CommandRegistry` + wire 신규 `setMySetting`(CommandWireMapper) + 엔진 핸들러 신규 + dispatcher · FE: `web/game/app/game/my-generals/page.tsx`(또는 b_myPage 설정 패널) · api `lib/api.ts` | **wire 부재**(grep 0) → 신규. 필드: `defence_train`/`tnmt`/`use_treatment`/`use_auto_nation_turn` | **N** (개인 KV write 결정론) | 설정 저장→202; general.aux KV 반영 IT(real Postgres flush) | 🔴 인테이크 전무 | (1) wire 코드 `setMySetting` 추가. (2) logic action(개인 설정 4필드 KV write) + 엔진 핸들러 + dispatcher. (3) ChangeRecorder dirty 경유 flush(인라인 write 금지). (4) FE 설정 패널(토너먼트/수비훈련/환약/자동사령턴 토글)+submit. b_myPage 설정 패널과 연동(C3 참조). |
| j_vacation | php-ajax write | `hwe/j_vacation.php` | logic action 신규 + wire 신규 `vacation` + 엔진 핸들러 + dispatcher · FE: my-generals/b_myPage 설정 패널 · api | **wire 부재** → 신규. 동작: `killturn` 3배 연장(autorun_user 서버 제외) | **N** | 휴가 명령→202; killturn ×3 반영 IT | 🔴 인테이크 전무 | (1) wire `vacation`. (2) logic action(killturn=killturn*3, autorun_user 게이트) + 핸들러 + dispatcher. (3) FE '휴가' 버튼(설정 패널). |
| j_set_npc_control | php-ajax write | `hwe/j_set_npc_control.php` (+표시 정본 `v_NPCControl.php`/`PageNPCControl.vue`) | logic action 신규(국가정책 23필드 KV write) + wire 신규 `setNpcControl` + 엔진 핸들러 + dispatcher · FE: `web/game/app/game/npc-control/page.tsx`(DnD 우선순위) · read 보강: `NpcPolicyController.kt`+`NpcPolicyResponse`(F4Dto) | **wire 부재** → 신규. read도 부분(meta 키 구조 불일치→실데이터 빈값) | **N** (KV write 결정론) | 정책 저장→202; nation_env(KV) 반영 IT; DnD 우선순위(사령20/일반15) 직렬화 정합 | 🔴 write 전무 + read 28% (날조 2키) | (1) wire `setNpcControl` + logic action(generalPriority 등 23필드 + 우선순위 배열 nation_env write). (2) 엔진 핸들러 + dispatcher. (3) **read 정합 동반** — 현 `defaultPolicy`는 23키 중 6키만+2키 날조(`reqHumanWarUprising`/`autorun_user`)+2키 기본값 오류(`reqNationGold/Rice`); meta 키 구조가 정본과 달라 currentPolicy/priority/lastSetters 항상 빈값. zeroPolicy 파생(GameUnitConst.costWithTech/develcost 포팅 미확인=BLOCKED 후보). (4) FE 좌우 2칼럼 DnD(비활성↔활성), 35행동 툴팁(`helpTexts.ts`=C2). 인증 게이트가 정본보다 넓음→parityViolation 표기. |
| j_myBossInfo (인사부 write) | php-ajax write | `hwe/j_myBossInfo.php` | logic action 신규(임관/해임/전출/배속) + wire 신규 `myBoss` + 엔진 핸들러 + dispatcher · FE: `web/game/app/game/my-boss/page.tsx`(현 read-only) · api | **wire 부재** → 신규. action=임관/해임/전출/배속 | **N** (직위 변경 결정론, 단 권한 게이트 패러티) | 인사 write→202; officer_level 변경 IT; 권한 게이트(군주/수뇌) | 🔴 read만(MyController GET /my-boss) | (1) wire `myBoss`. (2) logic action(4 action 분기, 권한 게이트) + 핸들러 + dispatcher. (3) FE my-boss 페이지에 인사 조작 버튼 + submit. 별도 `j_general_set_permission`(외교권/감찰권 일괄 UPDATE)은 군주 전용 write — 동일 패턴으로 wire `generalSetPermission` 추가(C1-β 확장). |

### C1 컴포넌트 (read 표시면 + write surface)

| id | kind | legacy(file) | 대상 파일 (opensamguk) | 의존 | 골든(RNG) | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| TipTap | vue-component | `hwe/ts/components/TipTap.vue` | `web/game/components/common/TipTap.tsx`(신규) | npm `@tiptap/react` | N | 에디터 마운트 + 툴바(되돌리기/재실행/굵게/기울기/밑줄) + HTML 추출 | 🔴 부재 | 리치 텍스트 에디터 래퍼. BoardArticle 글쓰기·물자원조 등에서 소비. legacy 툴바 항목 verbatim. |
| BoardArticle | vue-component | `hwe/ts/components/BoardArticle.vue` | `web/game/components/board/BoardArticle.tsx`(신규) | BoardComment·TipTap·`utilGame.getNPCColor`(✅) | N | 제목/본문/author_icon/댓글목록/댓글입력 렌더 | 🔴 부재 | author_icon 64px(스키마 결정 종속). j_board_article_add submit surface. |
| BoardComment | vue-component | `hwe/ts/components/BoardComment.vue` | `web/game/components/board/BoardComment.tsx`(신규, 또는 page.tsx CommentRow 유지) | — | N | 작성자/본문/날짜 단일 행 | 🟡 page.tsx에 인라인 가능 | 댓글 단일 행. 아이콘 없음(정상). |
| AuctionResource | vue-component | `hwe/ts/components/AuctionResource.vue` | `web/game/components/auction/AuctionResource.tsx`(신규) → `web/game/app/game/auction/page.tsx` | read: `AuctionController.kt`(GetActiveResourceAuctionList fid=35) · wire `auctionBid`/`auctionOpenBuyRice`(:259)/`auctionOpenSellRice`(:266) **기존** | N | 쌀 구매/판매 2섹션 8컬럼 테이블 + 인라인 입찰(min=시작가/max=마감가/step=10) + 등록 폼 + 최근20 로그 | 🔴 부재(auction page 22%) | 통화 접두사, 단가/마감가 컬럼, '경매 등록' 폼(매물타입·수량·기간턴·시작가·마감가). 입찰/등록 wire는 기존 → submit 배선만. |
| AuctionUniqueItem | vue-component | `hwe/ts/components/AuctionUniqueItem.vue` | `web/game/components/auction/AuctionUniqueItem.tsx`(신규) → auction/page.tsx | read: `Auction/GetUniqueItemAuctionList/Detail`(fid=15) · wire `auctionOpenUnique`(:273)/`auctionBid` **기존** | N | 익명 입찰(obfuscatedName), 유산포인트 잔여, min=ceil(최고*1.01)/max=remainPoint+confirm, 진행중/종료 목록 | 🔴 부재 | 아이템 툴팁, 주최자(익명), 종료일시/최대지연, 입찰자 목록. '금/쌀↔유니크' 모드 토글로 AuctionResource와 양립. |
| BettingDetail | vue-component | `hwe/ts/components/BettingDetail.vue` | `web/game/components/betting/BettingDetail.tsx`(신규) → `web/game/app/game/betting/page.tsx` | read: `BettingController.kt`(b_betting fid=10) · wire `placeBet`(:128) **기존** | N | 후보 선택 + 배당 순위 + 베팅 제출 | 🔴 부재(betting page 12%) | placeBet wire 기존 → submit 배선 + 상세 카드 렌더. |

---

## C2 — 선택풀/빙의 (Tier-0 Area2 의존)

> read(j_get_select_pool) + FE(select_general_from_pool.ts·select_npc.ts) + write(j_select/update_picked_general·j_select_npc).
> **핵심 게이트 분기**: 픽 경로 = RNG-bearing(`allStat^1.5` 가중추첨) → **골든 Y, `/parity-wave` 이관**. update/claim = 결정론.
> Tier-0 Area2(선택풀 read seam) 선결: SelectPoolRepository 스텁 → 실 read.

| id | kind | legacy(file) | 대상 파일 (opensamguk) | 의존 | 골든(RNG) | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| j_get_select_pool | php-ajax read | `hwe/j_get_select_pool.php` + `ts/select_general_from_pool.ts` | read: 신규 컨트롤러 `SelectPoolController.kt`(GET `/api/select-pool`) + DTO `SelectPoolPick`(uniqueName/generalName/imgsvr/picture/통무지/dex[5]/personal/special*/specialDomesticName·Info/specialWarName·Info) + 신규 `SelectPoolReadRepository` | **read 전무**(컨트롤러/DTO/repo/FE 0). `putInfoText()` 보강 + dex합 오름차순 정렬 | N (read) | GET 응답 {result,pick[],validUntil}; dex합 정렬; FE 카드 렌더 | 🔴 0% | (1) read 컨트롤러+DTO+repo 신규. (2) **특기 표시명/설명(`specialDomesticName/Info`,`specialWarName/Info`)=BLOCKED** — `:logic` iAction getName/getInfo 인스턴스화 부재(GetConstController에 이미 BLOCKED 명시). 해당 필드만 quarantine + 백로그. (3) validUntil 카운트다운. PossessionController `/claimable`는 npc=2 빙의 흐름(별개) — 혼동 금지. |
| select_general_from_pool.ts | fe-ts (289L) | `hwe/ts/select_general_from_pool.ts` + `select_general_from_pool.php` | FE: 신규 페이지 `web/game/app/game/select-pool/page.tsx` + 컴포넌트(카드그리드+생성폼) · api `lib/api.ts` | j_get_select_pool(read) + j_select/update_picked_general(write) | N(FE) | 카드그리드(이름·초상64·통무지·성격·특기2·dex 5병종·유효시간 카운트다운) + 생성폼(전콘여부·성격select·통무지 input·능력치조정 4버튼·범위/총합 안내·생성/리셋) + npcmode==2 게이트 + maxgeneral 초과 차단 | 🔴 부재(현 CharacterClaim은 빙의만) | 2-stage 흐름(선택 후 생성). hasGeneralID false→buildGeneral / true→pickGeneral(교체). 임관 권유 메시지 섹션(getInvitationList: 국가별 scoutmsg, 색상배경)도 동반. 상수 소스 존재(GameConst defaultStat 15/80/165, availablePersonality, personalityName). |
| select_npc.ts | fe-ts (436L) | `hwe/ts/select_npc.ts` + `j_get_select_npc_token.php` | FE: `web/game/components/game/CharacterClaim.tsx`(보강) 또는 신규 select-npc 페이지 · api | j_select_npc(write claim) + token GET | N(FE 표시) / 추첨 동작 BLOCKED | 빙의 카드(통무지+**성격/내정특기/전투특기 한글명**) + npcmode==1 분기 | 🟡 빙의 claim 동작 OK, 카드 필드 누락 | **즉시 조치**: ClaimableGeneral DTO에 `special`(내정특기명)/`special2`(전투특기명)/`personal`(성격명) 3종 추가(소스 존재: `GameConst.personalityNameOf`,`SpecialityHelper.domesticName/warName`, FrontInfoController 동일패턴) + `officerLevel` 잉여노출 **제거**. **BLOCKED**: NPC 풀 가중추첨(5명+select_npc_token+valid_until+pick_more+'다른 장수 보기' 재추첨+keepCnt) — `select_npc_token` 테이블 V1~V10 전무, 추첨 RNG 포팅 미확인. npcmode!=1 차단+maxgeneral 가드 — game_env가 world_state.config 미기재(BLOCKED). |
| j_select_picked_general | php-ajax write (생성) | `hwe/j_select_picked_general.php` | wire `selectPoolPick`(:296) **기존(필드만)** + 엔진 `SelectPoolHandler.handlePick`(스텁) + read seam `SelectPoolRepository`(스텁) | wire 필드 SET 존재, **핸들러 deny-only 스텁** | **Y (RNG-bearing `allStat^1.5` 가중추첨)** → **`/parity-wave`** | 골든 draw-for-draw(스탯검증·성격검증·build·동시성·member_log) | 🔴 스텁('미구현') | **`/parity-wave` 이관 필수**(골든 부재로 blocked). 추가발견: wire arg 키가 legacy POST 키(pick/personal/use_own_picture)와 달라 **별칭 fallback 권장**. legacy L11-12 strength/intel을 'leadership' 키에서 읽는 버그 → strict-패러티 vs divergence **골든으로 확정(날조 금지)**. |
| j_update_picked_general | php-ajax write (교체) | `hwe/j_update_picked_general.php` | wire `selectPoolUpdate`(:303) **기존** + `SelectPoolHandler.handleUpdate`(스텁) + `SelectPoolRepository.findPoolEntry`(스텁) | 결정론(추첨 없음) | **N** | info 적용·mark-then-swap 동시성·next_change 쿨다운·owner_name·2 로그·npcmode/장수존재 게이트 | 🔴 스텁('미구현') | 결정론 → 골든 부담 없음, 일반 close 가능. **wire 필드 시그니처 정정**: 현 update 분기가 leadership/strength/intel/personalityName/useOwnPicture를 파싱하나 legacy update는 `$pick`만 읽음(나머지는 신규생성 경로 인자) → divergence 제거. 상수 소스 존재(blocked 아님). |
| j_select_npc | php-ajax write (빙의) | `hwe/j_select_npc.php` (+`j_get_select_npc_token.php` 표시) | 현 impl: `GeneralPossessionService.claim()` + `PossessionController`(POST `/api/general/claim`, GET `/api/generals/claimable`) | 표시 필드는 token GET에서 | N(claim 액션) / 추첨 BLOCKED | claim {result,reason}; 카드 표시 필드 정합 | 🟡 claim OK, 표시/추첨 갭 | §C2 select_npc.ts와 동일 — special/personal 3필드 추가 + officerLevel 제거(즉시). 토큰 추첨/keep/npcmode/maxgeneral = 테이블·KV 소스 선결(BLOCKED). penalty/npc-flip write 누락은 one-daemon-write 의도이식(DEFERRED, parityViolation 아님). |

---

## C3 — 저충실도 read 페이지 (blocked 낮은 것 우선)

> read=app/game-api controller+DTO 보강 + web/game page 정합. 대부분 **해석 소스가 Kotlin에 이미 존재**(getOfficerLevelText/getHonor/getDedLevelText/personalityNameOf/SpecialityHelper) → blocked 낮음.
> **우선순위(blocked 낮음→높음)**: ① a_genList/b_myGenInfo(헬퍼 이미 이식, 미사용) → ② a_npcList/a_kingdomList → ③ b_my* 카드 묶음 → ④ a_hallOfFame/a_emperior(BE empty 하드리턴, 테이블 부재=높은 blocked).

| id | kind | legacy(file) | 대상 파일 (opensamguk) | 의존 | 골든(RNG) | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| a_genList.php | php-page read (fid 30) | `hwe/a_genList.php` | FE `web/game/app/game/rankings/generals/page.tsx` · read `GeneralListController.kt`/`GeneralsController.kt` | 헬퍼 `GeneralListText`(officerLevelText/honor) **이미 이식, 미사용** | N | 15컬럼(얼굴/이름NPC색/연령/성격/특기2/Lv/국가/명성/계급/관직/통무지+부상보너스/삭턴/벌점) + 15종 서버정렬(기본=벌점 DESC) | 🔴 30%(raw 노출) | **blocked 최저 — 최우선.** (1) officer_level raw→`getOfficerLevelText`(헬퍼 이미 존재, 미사용=명백 버그). (2) experience→Lv(getExpLevel)/명성(getHonor), dedication→계급(getDedLevelText). (3) 누락 컬럼(연령/성격/특기2/삭턴/벌점) 추가. 벌점(refresh_score_total)=`general_access_log` 부재로 **BLOCKED**(P8). |
| b_myGenInfo.php | php-page read (fid 25) | `hwe/b_myGenInfo.php` | FE `web/game/app/game/my-generals/page.tsx` · read `MyController.kt` `/my-generals`(MyGeneralSummary) | 헬퍼 다수 이식(officerLevelText·honor·getDedLevelText·getBillByLevel·personalityNameOf·SpecialityHelper·calcLeadershipBonus) | N | 15컬럼+15종 정렬 셀렉터 | 🔴 25%(BE 9필드만) | a_genList과 쌍. BE `MyGeneralSummary` 9필드→봉록/명성/성격/특기/얼굴/계급한글/사관/벌점/통솔보너스/부상 확장. 거의 전 소스 존재. 벌점만 BLOCKED. |
| a_npcList.php | php-page read (fid 35) | `hwe/a_npcList.php` | FE `web/game/app/game/rankings/npcs/page.tsx` · read `RankingController.kt` | 소스 존재 | N | 12컬럼(희생장수NPC색/악령이름owner_name/Lv/국가/성격/특기2/종능sum/통무지/명성/계급) + 8단 정렬 | 🔴 35% | 누락 5컬럼(악령이름·Lv·성격·특기2·종능) 추가, 신설 2컬럼(병력/도시) 제거, 라벨 정정(명성/계급). |
| a_kingdomList.php | php-page read (fid 15) | `hwe/a_kingdomList.php` | FE `web/game/app/game/rankings/kingdoms/page.tsx`(또는 별도 '세력일람' 라우트) · read `RankingController.kt` | 소스 대체로 존재(작위 lv0-7 byte-identical) | N | ROSTER(국가별 색상헤더+성향/작위/국력+officer_level 12~5 수뇌직책표+외교권자/조언자+속령전체일람(수도cyan)+장수전체일람dedication DESC+재야섹션) | 🔴 15%(현재는 leaderboard, 의미 별개) | 현 impl은 '세력 순위' leaderboard로 **별개 화면**. legacy는 '세력일람' roster. 두 화면 공존 or roster 신규. 공통필드(국가명/색/작위/장수수/도시수/수도) 패러티 OK. lv8/9는 의도 divergence. |
| b_myKingdomInfo.php | php-page read (fid 22) | `hwe/b_myKingdomInfo.php` | FE `web/game/app/game/my-nation/page.tsx` · read `MyController.kt` `/my-nation-detail` | **계약 버그** 우선 | N | 8열 19필드(총주민/총병사/국력/국고/병량/세율/세금·세곡/지급률/수입지출금미/속령수/장수수/예산/기술력/작위/속령일람/국가열전) | 🔴 22%(계약 불일치 버그) | **먼저 계약 버그 수정** — FE는 {nation,generals,cities} 가정하나 BE는 {result,hasNation,nation:FrontNationInfo,cityCount,generalCount}만 반환 → 두 표 공백+pop/genNum/power 0/undefined. legacy엔 없는 장수표/도시표 제거 + 19필드 단일표로. |
| b_myCityInfo.php | php-page read (fid 22) | `hwe/b_myCityInfo.php` | FE `web/game/app/game/my-cities/page.tsx` · read `MyController.kt` `/my-cities` | 도시당 21 데이터포인트 | N | 도시별 카드(5행×최대10열): 헤더【지역|등급】도시명(국가색,수도cyan) + 주민/인구율/자금·군량·둔전수입 + 농업/상업/치안/수비/성벽 + 민심/시세/태수·군사·종사 | 🔴 22%(평면9컬럼) | 평면표→도시별 카드. number_format/소수자릿수/시세 null→"- " verbatim. BE 필드 확장. |
| b_currentCity.php | php-page read (fid 22) | `hwe/b_currentCity.php` + `cityGeneral.php` | FE `web/game/app/game/city/page.tsx` · read `web/CityDetailController.kt` `CityDetailResponse` | **BE 미emit** → 다수 blocked | N | 장수 상세 테이블+군사집계행+관직자행+도시선택 셀렉터+갱신시각+도시명행+장수명 CSV | 🔴 22%(헤더+게이지만) | 근본원인=`CityDetailResponse`가 장수리스트/관직자명/군사집계/셀렉터 데이터 미emit → **FE 단독 불가, BLOCKED 다수**. BE DTO 확장 선결. fog 마스킹은 visible=false 의도. |
| b_myPage.php | php-page read (fid 20) | `hwe/b_myPage.php` | FE 신규/보강 — 컨트롤바 18번('내 정보&설정')이 `/game`(GameChrome)로 잘못 라우팅(`control-bar-config.ts:64`) → 전용 페이지 필요 · read `MyController.kt` `/my-page` | C1-β set_my_setting/vacation(설정 패널 write) | N | 좌 정보카드(generalInfo+generalInfo2)+우상단 설정패널(토너먼트/환약/자동사령턴/수비/저장/휴가/즉시행동/화면모드/아이템파기/CSS)+4 기록섹션(개인/전투/장수열전/전투결과) | 🔴 20%(라우팅 오류) | (1) `/game/my-page` 전용 라우트 신설 + control-bar-config 18번 재배선. (2) generalInfo2(명성/계급/전투/계략/사관/승률/승리/패배/살상률/사살/피살)+숙련도 표시. (3) 설정 패널=C1-β write 소비. (4) 4 기록섹션(GetGeneralLog read 필요). |
| v_history.php | vue-page read (fid 35) | `hwe/PageHistory.vue` | FE `web/game/app/game/history/page.tsx` · read `HistoryController.kt` | **wire shape 불일치** + `utilGame.formatLog`(✅) | N | 4섹션(MapViewer 스냅샷+SimpleNationList 국가표+중원정세 로그+장수동향 로그) | 🔴 35%(2섹션, 색 깨짐) | (1) **wire shape 정합** — BE {result,months:[{year,month,profileName,map,nations}]} ↔ FE 기대 {firstYearMonth/.../record:{globalHistory,globalAction}} → record 항상 null. (2) `formatLog()` 적용(현재 raw `<R><B><1>` 노출). (3) MapViewer+SimpleNationList 2섹션 추가. SimpleNationList=신규 컴포넌트. |
| v_auction.php | vue-page read (fid 22) | `hwe/PageAuction.vue` | FE `web/game/app/game/auction/page.tsx` | C1 AuctionResource/AuctionUniqueItem 컴포넌트 | N | '금/쌀↔유니크' 모드 토글 두 화면 | 🔴 22% | C1 컴포넌트 2종 조립 + 모드 토글 셸. |
| a_hallOfFame.php | php-page read (fid 12) | `hwe/a_hallOfFame.php` | FE `web/game/app/game/rankings/hall-of-fame/page.tsx` · read `RankingController.kt` `RankReadService.hallOfFame()` | **`hall` 테이블 read 부재** | N | 24분류 상위10 카드(순위·64초상·국가명대비색·printValue int/percent)+시즌/시나리오 셀렉터 | 🔴 12%(emptyList 하드리턴) | **높은 blocked** — `RankReadService.hallOfFame()`=emptyList() 하드리턴, hall read entity/repository 부재(`hall` 테이블 schema.sql:257). read 시임부터 신규. 24 type 섹션·한글라벨·초상·대비색·ng_games 시즌 셀렉터. C3 후순위. |
| a_emperior.php / a_emperior_detail.php | php-page read (fid 25/8) | `hwe/a_emperior.php` / `a_emperior_detail.php` | FE `rankings/emperor/page.tsx` + `rankings/emperor/[id]/page.tsx` · read `RankingController.kt` emperor | **emperor 테이블 부재**(항상 404) | N | 왕조 일람 + 상세 | 🔴 8-25%(404) | **높은 blocked** — emperor 테이블 부재로 detail 항상 404. read 시임+테이블 선결. C3 최후순위. |

> C3 보조 read-api 갭(동일 컨트롤러 보강 흐름): `Global/GetRecentRecord`(메인 3피드, fid 10), `Global/GetHistory/GetCurrentHistory`(fid 15-25), `Global/GeneralList`(fid 45), `Auction/Get*AuctionList/Detail`(fid 15-35), `bestGeneral`/`a_bestGeneral`(fid 8), `a_traffic`(fid 15). 각 controller+DTO 보강으로 FE page와 동반 정합.

---

## C4 — 시뮬레이터 (날조 스텁 → 실 전투엔진)

> ctrl-simulator: `SimulatorController` 난수 스텁을 logic `war/*` 실 전투엔진에 연결. **BE 선결 필수**(FE는 BE 의존).

| id | kind | legacy(file) | 대상 파일 (opensamguk) | 의존 | 골든(RNG) | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| j_simulate_battle (BE) | php-ajax write/계산 | `hwe/battle_simulator.php` 계산부 + `logic war/*` | `app/game-api/.../controller/SimulatorController.kt`(스텁 교체) → `logic` `war/*` 전투엔진(`processWar` 재사용) · 신규 DTO 입출력 | `logic/war/*`(P4 전투엔진, G1 draw byte-match), `common/rng RandUtil(LiteHashDrbg(warSeed))` | **Y (전투엔진 = ONE RandUtil(warSeed) draw-for-draw)** | 시드 재현 + 1000회 반복 요약(준/받은 피해 min~max, 군량소모, 스킬) draw-for-draw vs PHP 골든 | 🔴 난수 스텁 + FE 키 불일치 | (1) **스텁 제거** — 현 `(100..500).random()` → 실 전투엔진. 전투 전체가 `processWar()`서 1회 생성한 `RandUtil(warSeed)` 1개로 진행(재시드 금지). (2) **FE↔BE 키 정합** — FE `{attackerGeneralId,defenderGeneralId}` ↔ 컨트롤러 `body["attackerId"]`(항상 에러 분기). 응답 필드도 전부 불일치(FE attackerWon/Damage… ↔ BE winner/damageDealt…). (3) 입력=수동 파라미터(국가성향/기술등급/규모/도시/수도/장수 전항목). (4) **`j_export_simulator_object`(read)** 동반 — 동국 장수 raw stat 객체 반환(타국=더미). |
| battle_simulator.ts / .php (FE) | fe-ts (8) / php-page (6) | `hwe/battle_simulator.php` + `hwe/ts/battle_simulator.ts` | FE `web/game/app/game/simulator/page.tsx`(전면 재작성) | j_simulate_battle(BE) + j_export_simulator_object | N(FE) / 라벨 BLOCKED 일부 | 전역설정+출병국/수비국 설정+장수 상세폼+요약테이블+로그 2분할 | 🔴 6-8%(최소 화면) | (1) **전역 설정 카드**(시작년 disabled/년/월/시드/반복횟수[1회 로그·1000회 요약]/전투/저장·불러오기 .json). (2) **출병국·수비국 설정**(성향+장단점·기술등급1~12·국가규모·도시규모·수도Y/N·수비/성벽). (3) **장수 상세 폼**(이름/직위/Level/통무지/명마·무기·서적/부상%·군량·도구/병종·병사·성격/훈련·사기·전특/숙련5종/수비여부/전투·승리·사살수/회피·필살·계략시도 확률). (4) **수비자 다중 add/정렬/복제/제거**. (5) **요약 테이블**(일시/횟수/페이즈/준·받은피해 min~max/양측 군량·스킬). (6) **마지막 전투 로그 + 상세 로그 2분할**. (7) 서버에서 가져오기 모달. **BLOCKED 라벨**: 성향/전특/성격/아이템 한글명(`iActionBundle` name=null), dex 라벨/색상(`getDexLevelList` 미노출) — 소스 노출 선결. 값 소스 대부분 존재(GameConst availableNationType/SpecialWar/Personality/allItems, GameUnitConst 병종, maxTrainByWar=110/maxAtmosByWar=150). |
| v_battleCenter.php / PageBattleCenter.vue | vue-page read | `hwe/v_battleCenter.php` + `hwe/PageBattleCenter.vue` + `ts/battleCenter.ts` | FE 신규 `web/game/app/game/battle-center/page.tsx` · read 신규 `BattleCenterController.kt` | general_record read seam | N | 감찰부(장수 행동 로그 뷰어, 전투 참가 장수 실시간 목록) | 🔴 현재 /coming-soon 리다이렉트 | (별개 단위지만 시뮬레이터 인접) read 컨트롤러+DTO 신규. legacy 타이틀 '감찰부'. `j_general_log_old`(generalAction/battleResult/battleDetail 페이지네이션 로그) read 동반. |

---

## 실행 순서 권고 (의존 위상)

1. **C1-α** (외교3+게시판2) — wire 기존, FE submit+DTO 정합만. **가장 빠른 패러티 회수**. 단 BoardArticle은 TipTap 컴포넌트 + author_icon 결정 선행.
2. **C3 ①②** (a_genList/b_myGenInfo → a_npcList/a_kingdomList) — 헬퍼 이미 이식·미사용, blocked 최저. raw 코드 노출 제거가 즉효.
3. **C2 즉시조치분** (select_npc.ts/j_select_npc 카드 special/personal 3필드 + officerLevel 제거) — 소스 존재, blocked 아님.
4. **C1-β** (set_my_setting/vacation/set_npc_control/myBoss) — 신규 wire+logic+handler+dispatcher 풀빌드. b_myPage(C3) 설정패널과 동반.
5. **C1 컴포넌트** (AuctionResource/UniqueItem·BettingDetail) + **C3 v_auction** — 묶어서.
6. **C3 ③** (b_my* 카드: myKingdom 계약버그→myCity→currentCity[BE DTO 확장]) + **v_history**(wire shape 정합).
7. **C2 픽 경로** (j_select_picked_general) — **`/parity-wave` 이관**(RNG 골든). update/claim은 결정론으로 일반 close.
8. **C4** (j_simulate_battle BE → battle_simulator FE → v_battleCenter) — 전투엔진 골든(RNG).
9. **C3 최후** (a_hallOfFame/a_emperior) — read 시임+테이블 부재로 blocked 최고.

## BLOCKED/Quarantine 누적 (소스 부재, 날조 금지)

- **iAction 한글 표시명** (`specialDomesticName/Info`·`specialWarName/Info`·nationType name·specialWar/personality/item name) — `:logic` iAction getName/getInfo 인스턴스화 부재. GetConstController에 이미 BLOCKED 명시. C2/C4 다수 라벨 차단.
- **select_npc_token 테이블** — V1~V10 전무(grep 0). C2 NPC 가중추첨/keep/valid_until 차단.
- **game_env (npcmode/maxgeneral)** — world_state.config 미기재. C2 npcmode 차단/정원 가드 차단(IdentityDto §2와 동일 원인).
- **general_access_log** — refresh_score_total(벌점 컬럼)·checkLimit·increaseRefresh 차단(P8 백로그). C3 a_genList/b_myGenInfo 벌점.
- **hall / emperor 테이블** — read entity/repository 부재. C3 a_hallOfFame(emptyList 하드리턴)/a_emperior(404).
- **zeroPolicy 파생** (`GameUnitConst.costWithTech/develcost` 포팅 미확인) — C1-β set_npc_control read 일부.
- **getDexLevelList(임계값+색상+이름)** 상수 미노출 — C4 시뮬레이터 숙련 라벨.

각 BLOCKED는 해당 행에 quarantine + 페이즈 백로그 기록, 인접 비-blocked 필드는 정상 close.
