# 엔진 mutation-path 재채점 — betting / auction / dispatcher / 외교메시지 수락·거절

- 일자: 2026-06-12 · 측정 전용(코드 무수정)
- 대상: `app/game-engine` 핸들러(betting/, auction/, run/TurnDaemonCommandDispatcher) + `app/game-api` DiplomaticMessageController
- 오라클(grand truth): `legacy/devsam-core/hwe/sammo/Betting.php`, `Auction.php`, `AuctionBasicResource.php`, `AuctionUniqueItem.php`, `DiplomaticMessage.php`, `Message.php`, `hwe/func_auction.php`, `hwe/sammo/API/Betting/Bet.php`, `API/Auction/Bid*.php`, `hwe/sql/schema.sql`

## 판정 기준

1. **검증 패러티** — PHP가 던지는/반환하는 모든 가드(순서 포함)가 Kotlin 핸들러에 존재하는가.
2. **부수효과 패러티** — 자원 증감, rank_data, UserLogger, Message 발송, KV 갱신이 PHP와 동일하게 기록되는가.
3. **로그 패러티** — 로그 push 여부·문구·scope가 PHP와 byte-동일한가. PHP에 없는 로그 push = "non-parity log push".
4. **INSERT vs insertUpdate** — PHP `insertUpdate`(ON DUPLICATE KEY) 의미를 flush SQL이 보존하는가.
5. **ChangeRecorder 채널 / one-daemon-write** — 데몬 변이가 전부 `world`/`recorder` 경유인가, JPA write가 없는가.
6. severity: **P0** = flush 크래시(턴 동결류)·자원 복제/소실·정산 키 오염, **P1** = 게임 규칙/권한/부수효과 누락(플레이 가시적), **P2** = 로그 문구/기본값/이론적 경로/죽은 코드.

---

## A. PlaceBetHandler ↔ `Betting::bet()` (P0-07 계열 — 검증·정밀 열거)

현재 impl: `app/game-engine/src/main/kotlin/opensamguk/engine/betting/PlaceBetHandler.kt` (33-84행이 본문 전체).
PHP 오라클: `legacy/devsam-core/hwe/sammo/Betting.php:100-183` + API 게이트 `hwe/sammo/API/Betting/Bet.php:19-36`.

| # | sev | 내용 | PHP 근거 | Kotlin 근거 |
|---|-----|------|----------|------------|
| A1 | **P0** | **INSERT-vs-insertUpdate**: PHP는 `insertUpdate('ng_betting', …, ['amount' => amount + %i])` — 동일 (general,betting,type) 재베팅 시 amount 누적. Kotlin flush는 plain `INSERT INTO ng_betting …` (ON CONFLICT 없음)인데 V7 스키마에 `UNIQUE (general_id, betting_id, betting_type)`가 그대로 있어 **재베팅 1건이 unique 위반 → JDBC batch flush 예외 → 틱 롤백(턴 동결류)** | Betting.php:162-166; schema.sql:644-656 (`UNIQUE by_general`) | JdbcFlushExecutor.kt:890-907 (`bettingInsertMany`, plain INSERT); V7__p6_messaging_economy.sql:40-49 (UNIQUE 2종); ChangeRecorder.kt:506-508 ("INSERT-only") |
| A2 | **P0** | **BettingInfo(KV) 미조회** — 베팅 존재(`해당 베팅이 없습니다`), `finished`, `closeYearMonth <= yearMonth`(마감), `openYearMonth > yearMonth`(미시작), `count(bettingType) != selectCnt` 가드 전부 부재. 종료/마감된 베팅에도 INSERT됨 | Betting.php:40-49(생성자 throw), 104-110, 114-128 | PlaceBetHandler.kt:33-51 (BettingInfo 로드 자체가 없음) |
| A3 | **P0** | **purifyBettingKey 미적용** — PHP는 `sort(SORT_NUMERIC)`+`array_unique`+후보(candidates) 검증 후 JSON 인코딩한 canonical key를 betting_type으로 저장(“key로 바로 사용하므로 중요함”). Kotlin은 입력 배열을 그대로 `jsonEncode` → `[2,1]`≠`[1,2]` 키 분열 ⇒ **정산(calcReward) betting_type 그룹 키 오염**(같은 선택이 다른 키로 흩어져 보상 산정 틀어짐) + 잘못된 후보 통과 | Betting.php:56-74 (purify), 131 (convertBettingKey) | PlaceBetHandler.kt:63 (`"betting_type" to jsonEncode(bettingType)`) |
| A4 | P1 | 유저별 누적 1000 한도 미적용 — PHP는 `SELECT sum(amount) … WHERE betting_id AND user_id` 후 `prev + amount > 1000`이면 거부 | Betting.php:135-139 | PlaceBetHandler.kt (해당 read/가드 없음) |
| A5 | P1 | 금 플로어 미적용 — PHP는 `gold < minGoldRequiredWhenBetting + amount`(= 500 + amount) 거부. Kotlin은 `gold < amount`만 | Betting.php:147-150; (Kotlin 상수는 존재: common GameConst.kt:311 `minGoldRequiredWhenBetting = 500`) | PlaceBetHandler.kt:49-51 |
| A6 | P1 | **유산포인트 베팅 분기 전무** — `reqInheritancePoint`면 금이 아니라 `inheritance_{userID}` KV `previous` 차감 + `UserLogger("{amount} 포인트를 베팅에 사용")` + `rank_data inherit_point_spent_dynamic += amount`. Kotlin은 무조건 금 차감 | Betting.php:141-144, 167-174 | PlaceBetHandler.kt:53-55 (gold만) |
| A7 | P1 | 금 베팅 시 `rank_data betgold += amount` 미적용 | Betting.php:179-181 | PlaceBetHandler.kt (rank 기록 없음) |
| A8 | **P0** | **non-parity 로그 push + enum flush 크래시** — PHP `bet()`은 성공 시 로그를 **전혀 남기지 않음**. Kotlin은 fabricated 문구를 push하며 scope=`"action"`/category=`"betting"` → PG enum `log_scope`(SYSTEM/NATION/GENERAL/USER)·`log_category`(HISTORY/SUMMARY/ACTION/…)에 `ACTION`(scope)/`BETTING`(category) 없음 → **베팅 1건 발생 시 로그 flush BatchUpdateException → 틱 롤백**. DatabaseHooks가 이를 "별개의 P6 flush 버그"로 자인하고 보존 중 | (PHP 로그 부재: Betting.php:100-183 전체) | PlaceBetHandler.kt:69-76; DatabaseHooks.kt:490-499 (NOTE + else→uppercase); V1__baseline.sql:3-4 (enum 정의) |
| A9 | P2 | API 레이어 `min amount 10` 미적용 — PHP Validator `rule('min','amount',10)`. Kotlin은 `amount <= 0`만(핸들러), 매퍼도 무검증 | API/Betting/Bet.php:27-30 | PlaceBetHandler.kt:44-46; CommandWireMapper.kt:128-134 |

> user_id 출처(P2 참고): PHP는 세션 `userID`, Kotlin은 `general.meta["user_id"]` (PlaceBetHandler.kt:62) — 의미상 호환이나 meta 미적재 시 null로 들어가 A4 한도 키가 영구 무력화됨.

## B. AuctionBidHandler ↔ `Auction::_bid()` / `bidInheritPoint()` / `AuctionBasicResource::bid()` / `AuctionUniqueItem::bid()`

현재 impl: `app/game-engine/src/main/kotlin/opensamguk/engine/auction/AuctionBidHandler.kt`.

| # | sev | 내용 | PHP 근거 | Kotlin 근거 |
|---|-----|------|----------|------------|
| B1 | **P0** | **자기-최고입찰 재상향 시 자기 환불(자원 복제)** — PHP는 `myPrevBid`가 현재 최고입찰과 동일 row면(`highestBid->no === myPrevBid->no`) 환불 없이 차액만 차감. Kotlin은 `highestBid != null`이면 **무조건** 이전 최고입찰자에게 전액 환불 → 내가 최고입찰자인 채 재상향하면 차액만 내고 이전 입찰액을 돌려받음(금/쌀/유산포인트 모두) | Auction.php:399-405 (myPrevBid 유효 판정), 450-452 (`highestBid !== null && myPrevBid === null`일 때만 refund); 동일 로직 인헤리트 경로 343-345 | AuctionBidHandler.kt:117-156 (BUY/SELL/UNIQUE 모두 무조건 refund), 226-234 (`refundGeneral`) |
| B2 | **P0** | **stale 이전입찰 기준 차액(이중 혜택)** — PHP는 내 이전 입찰이 이미 환불된 경우(`highestBid->no !== myPrevBid->no`) `myPrevBid = null` 처리 → 전액 차감. Kotlin은 환불 여부와 무관하게 내 역대 최고입찰을 `previousBidAmount`로 사용 → 환불받은 금액만큼 **영구 under-deduct** | Auction.php:399-403 | AuctionBidHandler.kt:81-86, 114 (`calculateMorePoint(amount, myPrevBidAmount)`) |
| B3 | P1 | `closeDate < now`(이미 끝남) / `openDate > now`(미시작) 가드 부재 — finished 플래그만 검사 | Auction.php:359-366 | AuctionBidHandler.kt:61-65 |
| B4 | P1 | 즉시거래가(finishBidAmount) 상한/하한 가드 + **즉구 시 `shrinkCloseDate`(+1턴 후 지급)** 부재 | Auction.php:368-376; AuctionBasicResource.php:244-251 | AuctionBidHandler.kt (둘 다 없음; AuctionBidValidator.kt에도 없음) |
| B5 | P1 | "자신이 연 경매에 입찰할 수 없습니다" (host 본인 입찰 금지) 가드 부재 | AuctionBasicResource.php:235-237 | AuctionBidHandler.kt:54-110 (host 비교 없음) |
| B6 | P1 | 자원 플로어 부재 — PHP는 `getVar(res) < morePoint + minReqRes`(minReqRes = `GameConst::$defaultGold`/`$defaultRice`). Kotlin validator는 `morePoint > 보유`만 | Auction.php:406-414 | AuctionBidValidator.kt:79-91 (플로어 없음) |
| B7 | P1 | **유니크 입찰 충돌 가드 전무** — 이미 비-구매 아이템 소지 거부 + "1순위 입찰자인 경매중에 같은 부위가 있습니다"(타 진행중 유니크 경매 cross-check) 부재 | AuctionUniqueItem.php:140-230 | AuctionBidHandler.kt:89-96 (`validateUniqueBid` 금액·포인트만) |
| B8 | P1 | 환불 부수효과 누락 — PHP `refundBid`는 (a) 유산포인트 환불 시 `rank inherit_point_spent_dynamic -= amount`, (b) 환불 대상에게 system Message 발송("상회입찰자가 나타났습니다"). Kotlin은 자원만 가산 | Auction.php:211-259 (refundBid 본문), 344, 451 | AuctionBidHandler.kt:148-155, 226-234 |
| B9 | P1 | **연장 semantics 역전** — PHP 자원경매는 매 성공 입찰마다 무조건 `extendCloseDate(…, force=true)`(연장권 카운트 비소모; `tryExtendCloseDate` 플래그는 aux에 저장돼 tryFinish 때 소비). Kotlin은 `tryExtendCloseDate==true`일 때만 연장하면서 **매 입찰마다 `remainCloseDateExtensionCnt - 1`**(PHP는 bid 경로에서 안 깎음) | Auction.php:439-448 (무조건 연장+force), 182-203 (force=false에서만 감소) | AuctionBidHandler.kt:177-200 (조건부 연장 + 193-196 무조건 감소) |
| B10 | P2 | 유니크 bid aux에 실명 노출 — PHP는 `genObfuscatedName`을 aux generalName으로 저장(신원 은닉), owner/owner_name도 기록. Kotlin은 `general.name` + `owner = null` | Auction.php:305-320 | AuctionBidHandler.kt:160-173 |
| B11 | **P0** | 입찰 로그 scope=`"action"`/category=`"auction"` → A8과 동일 enum flush 크래시 + PHP에 없는 fabricated 로그 | (PHP _bid는 성공 로그 push 없음) | AuctionBidHandler.kt:206-214; DatabaseHooks.kt:490-499 |
| B12 | P2 | `tryExtendCloseDate ?: true` 기본값 — PHP 유니크 API 기본은 `false`(`extendCloseDate ?? false`), 자원경매는 항상 true 하드코드 | API/Auction/BidUniqueAuction.php:40; BidBuyRiceAuction.php:44 | AuctionBidHandler.kt:58 |
| B13 | P2 | PHP `_bid`에 없는 시작가 검증 추가(divergence) — 첫 입찰 < startBidAmount를 Kotlin이 거부, PHP는 통과 | Auction.php:350-455 (start 검증 없음) | AuctionBidValidator.kt:55-58 |
| B14 | P2 | 동일-틱 stale read — bid/auction 조회가 JPA(DB)라 같은 틱에 recorder에 쌓인 미flush bid/경매가 안 보임(같은 틱 2건 입찰 시 둘 다 최고가 검증 통과·차액 오계산; 같은 틱 개설→입찰은 "경매가 존재하지 않습니다") | (PHP는 요청마다 즉시 DB 반영이라 미발생) | AuctionBidHandler.kt:61, 77; AuctionOpenHandler.kt:182 (in-memory id 선할당) |

## C. AuctionFinalizeHandler / AuctionExpiryDaemon ↔ `Auction::tryFinish()` / `finishAuction()` / `rollbackAuction()` / `processAuction()`

| # | sev | 내용 | PHP 근거 | Kotlin 근거 |
|---|-----|------|----------|------------|
| C1 | P1 | 마감 시 **입찰자 연장요청(aux.tryExtendCloseDate) 처리 전무** — PHP tryFinish는 최고입찰의 연장 플래그가 있으면 `extendCloseDate`(연장권 1 소모)+`extendLatestBidCloseDate` 후 마감 보류. Kotlin은 aux를 읽지 않고 즉시 마감 | Auction.php:471-486 | AuctionFinalizeHandler.kt:49-89 (aux 미참조); AuctionExpiryDaemon.kt:42-72 |
| C2 | P1 | **유니크 소유 제한 semantics 불일치** — PHP는 (a) `maxUniqueItemLimit` 연도 스케줄로 보유 가능 수 계산, (b) 아이템별 점유수(`count(*) FROM general WHERE slot=item`) 검사, (c) 제한 시 `closeDate` 연장 + `setHostAsNeutral()` + applyDB. Kotlin은 낙찰자 단일 슬롯만 보고 제한 시 `status=OPEN`으로 되돌릴 뿐 **closeDate를 안 늘려** 다음 틱마다 무한 재시도 | AuctionUniqueItem.php:237-331 (특히 250-281, 315-330) | AuctionFinalizeHandler.kt:144-184, 246-257 |
| C3 | P1 | finishAuction 실패 시 입찰자 앞 system Message 발송 누락 | Auction.php:495-522 | AuctionFinalizeHandler.kt (Message 채널 자체 미사용) |
| C4 | P1 | 자원경매 유찰/성사 **로그 byte-parity 전무** — PHP는 `pushAuctionLog` "(N)번 {res} 경매 <M>유찰</> …", 성사 시 host/bidder `pushGeneralActionLog` 2건 + 경매장 로그(마감가/최저가 ★ 태그 포함) + 유찰 Message. Kotlin은 fabricated 문구 1-2건 | AuctionBasicResource.php:128-158 (유찰), 189-224 (성사) | AuctionResultCalculator.kt:80, 99, 147, 159, 171 (fabricated); AuctionFinalizeHandler.kt:116-122, 227-235 |
| C5 | **P0** | finalize/expiry 로그 전부 scope=`"action"` → A8/B11과 동일 enum flush 크래시(만료 경매 1건 처리 = 턴 동결) | — | AuctionFinalizeHandler.kt:116-122, 160-167, 216-235; AuctionExpiryDaemon.kt:60-66 |
| C6 | P2 | 동률 최고입찰 tie-break 미보장 — PHP `ORDER BY amount DESC LIMIT 1`(동률 시 최선행 row). Kotlin `maxByOrNull`은 repo 정렬(amount DESC, 2차 기준 불명)에 의존 | Auction.php:86-95 | AuctionFinalizeHandler.kt:262-265; AuctionBidHandler.kt:239-242 |
| C7 | P2 | 유니크 습득 로그 4종(개인 action/history + 전역 action/history 【보물수배】 습득) + `UserLogger("유니크 %s 경매로 %d 포인트 사용")` 누락 | AuctionUniqueItem.php:337-351 | AuctionFinalizeHandler.kt:216-235 (fabricated 2건) |
| C8 | P2 | PHP `processAuction()`은 로그를 남기지 않는데 ExpiryDaemon이 "자동 종료" 로그 추가(non-parity push) | func_auction.php:61-90 | AuctionExpiryDaemon.kt:60-66 |

## D. TurnDaemonCommandDispatcher 바인딩

| # | sev | 내용 | 근거 |
|---|-----|------|------|
| D1 | P2 | `AcceptDiplomaticMessage`/`DeclineDiplomaticMessage` wire 명령 + Ok/Fail result 직렬화가 정의돼 있으나 dispatcher `when`에 미바인딩(`else -> null` 무음 드랍). 실 사용 경로는 game-api 컨트롤러(E 섹션)뿐 — 죽은 이중 표면. 발행되면 조용히 유실 | TurnDaemonCommand.kt:462-480; TurnDaemonCommandResult.kt:321-343, 605-606; TurnDaemonCommandDispatcher.kt:175-227 (분기 없음) |
| D2 | P2 | 월틱 Q16 `registerAuction` no-op — PHP는 매월 2회 `nextBool(1/(cnt+5))` 게이트로 중립 상인(BuyRice/SellRice) 경매를 개설. Kotlin은 `registerAuction = { _ -> }` → **중립 경매 영구 부재**. (Q16이 monthlyRng 마지막 소비자라 월내 draw 디싱크는 없음 — PostUpdateMonthly.kt:369-405 주석 검증) | func_auction.php:7-59; MonthlyPostUpdateHook.kt:162-167 |

검증: PlaceBet/AuctionBid/AuctionFinalize + intake 계열(C2/troop/board/vote/message/auctionOpen/diplo/selectPool/buildNation/makeGeneral/claimNpc) 바인딩은 모두 존재(TurnDaemonCommandDispatcher.kt:175-227). 그 외 미바인딩(InstantRetreat/Vacation/DropItem/ChangePermission/Kick/Appoint/Tournament*/VoteReward/SetNationMeta/Adjust*/PatchGeneral 등)은 "not-yet-built" 문서화 상태(56-49행 KDoc)와 일치.

## E. DiplomaticMessage 수락/거절 ↔ `DiplomaticMessage::agreeMessage()` / `declineMessage()`

현재 impl: `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/DiplomaticMessageController.kt`.

| # | sev | 내용 | PHP 근거 | Kotlin 근거 |
|---|-----|------|----------|------------|
| E1 | P1 | **외교권자 가드 누락** — PHP는 `checkSecretPermission(general) >= 4` 아니면 "해당 국가의 외교권자가 아닙니다"로 거부(수락·거절 공통). Kotlin은 국가 일치만 검사 → 같은 국가의 **아무 장수나** 종전/불가침을 수락·거절 가능 | DiplomaticMessage.php:62-70 (mailbox + permission), 270 (decline도 동일 검증) | DiplomaticMessageController.kt:74-81, 137-141 (nation 비교만) |
| E2 | P1 | **즉시실행 → turn-reserve 치환의 3중 divergence** — PHP는 `buildNationCommandClass('che_*수락')`을 `hasFullConditionMet()` 사전검사 후 `NoRNG`로 **즉시 run**, 실패(INVALID) 시 메시지를 invalidate하지 않음(재시도 가능). Kotlin은 slot 0에 turn-reserve 후 **즉시 메시지 invalidate** → (a) 플레이어의 기존 slot 0 예약 명령을 덮어씀, (b) 엔진에서 deny돼도 제의 이미 소실, (c) 수락이 장수 턴 1개를 소비(PHP는 턴 비소비) | DiplomaticMessage.php:75-98 (noAggression run), 147-209 (agreeMessage 흐름: 실패 시 invalidate 없음) | DiplomaticMessageController.kt:101-113 (reserve turnIdx=0 → save) |
| E3 | P1 | **game-api JPA write** — `messageRepository.save(msg)`(validUntil 변경)는 api 프로세스의 직접 DB 쓰기. 아키텍처 원칙 "JPA = read/precheck only (game-api)"와 충돌(데몬 ChangeRecorder 채널 밖 상태 변경; message가 INSERT-only 채널이라 현재 lost-update는 없으나 write-truth 이원화) | (아키텍처 규약: CLAUDE.md one-daemon-write 절) | DiplomaticMessageController.kt:111-113, 143-145 |
| E4 | P2 | 수락 성공 후속 효과 누락 — PHP는 `msgOption['used']=true` + 【외교】 결과 메시지 2건(national + diplomacy, `delete`/`silence`/`deletable:false` 옵션) 발송 | DiplomaticMessage.php:209-242 | DiplomaticMessageController.kt:111-121 (validUntil만) |
| E5 | P2 | 로그 패러티 누락 — 검증 실패 시 "{reason} {외교명} 실패", 거절 시 양측 `pushGeneralActionLog` 2건("<D>{src국}</>의 {외교명} 제안을 거절했습니다." / "<Y>{dest국}</>{josa} {외교명} 제안을 거절했습니다.") | DiplomaticMessage.php:175, 196-199, 273-279 | DiplomaticMessageController.kt (로그 채널 없음) |

검증(정합 확인): destNationID = 메시지 본문 `src.nation_id`(제의국) 유도, destGeneralID(`src.id`) argTest 가드(누락·≤0·==self 거부), validUntil 만료 가드, 불가침 year/month(1-12) 범위 가드는 PHP `checkDiplomaticMessageValidation` + `che_*수락` argTest 의미와 정합 — DiplomaticMessageController.kt:69-99, 162-207에서 실재 확인.

## 닫힌 항목 검증 결과 (정상 포팅 확인)

| 항목 | 판정 | 근거 |
|------|------|------|
| AuctionOpenHandler 자원경매 검증 7단계(순서·문구) | ✅ byte-일치 | AuctionBasicResource.php:22-56 ↔ AuctionOpenHandler.kt:108-157 (메시지 문자열 동일, float 비교 보존) |
| 자원 차감이 openAuction INSERT **이후** + `increaseVarWithLimit(-amount, 0)` 미러 | ✅ | AuctionBasicResource.php:89 ↔ AuctionOpenHandler.kt:182-191 (`coerceAtLeast(0)`) |
| 유니크 개설 검증 순서(minPoint→포인트→isBuyable→동일아이템→동일host→availableCnt) + detail 구성(amount=1/startBid/remainCnt=1/availableLatest) | ✅ | AuctionUniqueItem.php:23-105 ↔ AuctionOpenHandler.kt:204-295 |
| 유니크 개설 【보물수배】 전역 history 로그 + Josa('라') | ✅ byte-일치 (단, scope="global"→SYSTEM 변환 경유로 flush 안전) | AuctionUniqueItem.php:125-130 ↔ AuctionOpenHandler.kt:320-329 |
| 유니크 host 난독명(genObfuscatedName, hiddenSeed 결정적 풀) | ✅ 개설 경로 | Auction.php:35-65 ↔ AuctionOpenHandler.kt:425-429 (입찰 경로는 B10 미적용) |
| ng_betting/ng_auction/ng_auction_bid 스키마 = PHP 스키마 미러(UNIQUE 2종 포함) | ✅ | schema.sql:644-656 ↔ V7__p6_messaging_economy.sql:40-49 (단 UPSERT 미구현 → A1) |
| ChangeRecorder 채널 분리(auctionUpserts emit-order / auctionBidInserts INSERT-only·outbid 비삭제 / bettingInserts) — PHP도 outbid row를 삭제하지 않음 | ✅ 채널 설계 정합 | Auction.php:321, 432 (insert만) ↔ ChangeRecorder.kt:134-141, 486-508 |
| 엔진 핸들러 one-daemon-write — betting/auction 핸들러의 모든 변이는 `world.updateGeneral`/`recorder.*` 경유, JPA는 read 전용(AuctionRepository/AuctionBidRepository 조회만) | ✅ (api측 E3는 별도) | PlaceBetHandler.kt:54-66; AuctionBidHandler.kt:117-204; AuctionFinalizeHandler.kt:100-257 |
| dispatcher placeBet/auctionBid/auctionFinalize 바인딩 + intakeCodes(placeBet/auctionBid/auctionOpen*) 존재 | ✅ | TurnDaemonCommandDispatcher.kt:82-84, 177-179, 211-214; CommandWireMapper.kt:43-66 |
| DiplomaticMessageController FIX #1/#4(a)(b)/#5 주석의 가드 실재 | ✅ (E1·E2가 잔존 갭) | DiplomaticMessageController.kt:69-99 |

### 참고 — 오라클 인용 정확성 (P2, 문서 위생)

Kotlin KDoc이 인용하는 PHP 심볼 `AuctionBidder::bid()`(AuctionBidHandler.kt:28), `AuctionFinalizer::finalize()`(AuctionFinalizeHandler.kt:23), `AuctionWorker::processExpiredAuctions()`(AuctionExpiryDaemon.kt:16), `Auction::validateBid()`/`UniqueAuctionBidder::validateUniqueBid()`(AuctionBidValidator.kt:16), `Auction::rollback()/finish()`(AuctionResultCalculator.kt:45)는 **legacy에 존재하지 않는 클래스/메서드**다(grep 0건; 실제는 `Auction::_bid`/`tryFinish`/`finishAuction`/`rollbackAuction`/`func_auction.php processAuction`). 가짜 오라클 인용은 후속 패러티 작업의 근거 추적을 오염시킨다.

## 집계

- **P0 = 6**: A1(ng_betting INSERT vs insertUpdate→unique 위반 flush 크래시), A2(BettingInfo 가드 전무), A3(purifyBettingKey 미적용→정산 키 오염), A8+B11+C5(scope="action"/category="betting|auction" enum flush 크래시 — 동일 근원 1건으로 계상), B1(자기환불 자원 복제), B2(stale 이전입찰 under-deduct)
- **P1 = 18**: A4 A5 A6 A7 B3 B4 B5 B6 B7 B8 B9 C1 C2 C3 C4 E1 E2 E3
- **P2 = 12**: A9 B10 B12 B13 B14 C6 C7 C8 D1 D2 E4 E5 (+오라클 인용 위생 1건 비계상)
