# 바퀴 26 (W-4) AuctionBidHandler 적대 패러티 리뷰 — 2026-06-12

대상: `db80c05` "W-4: AuctionBidHandler 환불 복제/미달차감 수정" + 후속 경계 수정(working tree).
리뷰어: parity-reviewer 서브에이전트(grader-w26, agentId a06a1727a52a14060) — 2라운드 적대 채점.

## 라운드 1 — VERDICT: FAIL

환불 코어(R1 무효화 / R2 차액 / R3 조건부 환불)와 검증 문자열·연장 산식·KV seam 은
PHP(Auction.php:286-345,350-455 / AuctionBasicResource.php:233-254 / InheritancePointManager.php:227-254)
대조상 정합 확인. 결함은 전부 코어 밖 경계:

| # | 심각도 | 결함 | PHP 근거 |
|---|---|---|---|
| 1 | P0 | 유니크 입찰 래퍼 가드 2종 드랍 — '이미 가진 아이템이 있습니다.' / '1순위 입찰자인 경매중에 같은 부위가 있습니다.' | AuctionUniqueItem.php:140-226 |
| 2 | P0 | aux.generalName 실명 기록 — genObfuscatedName 격리 사유가 형제 AuctionOpenHandler 에 의해 반증됨 | Auction.php:305,317,:35-65 |
| 3 | P1 | aux.ownerName 드랍 | Auction.php:426/:316 |
| 4 | P1 | tryExtendCloseDate 기본값 양방향 불일치(자원=항상 true 하드코딩, 유니크=기본 false) | BidBuyRiceAuction.php:44 / BidUniqueAuction.php:41 |
| 5 | P1 | wall-clock 입찰 차단 2종 부재(closeDate<now / openDate>now) | Auction.php:359-366 |
| 6 | P1 | 유니크 finished 메시지 바이트 불일치('경매가 종료되었습니다.') | AuctionUniqueItem.php:143-144 |
| 7 | P2 | 유산 경로 부수효과 순서 스왑(INSERT→연장→차감→환불이 정본) | Auction.php:321-345 |
| 8 | P2 | isunited != 0 KV 무기록 가드 부재 | InheritancePointManager.php:241-244 |
| 9 | P2 | 환불 owner 를 stale 입찰행 스냅샷으로 해석(정본=환불 시점 현재 general 행 재해석) | Auction.php:218-226, IPM:260-267 |

## 수정 (전부 반영)

- `uniqueWrapperGuard()` 신설 — 보유 비구매성 가드(allItems cnt>0 ⟺ !isBuyable,
  `UpdateNationLevel.ownedNonBuyableCount` 정본 등가) + 1순위 중복 가드(동률은 no 최대 행 =
  convertArrayToDict 마지막 스캔 행 등가). finished 직후·_bid 진입 전(PHP 순서).
- `obfuscatedBidderName()` — AuctionOpenHandler 동형 hiddenSeed 풀 결정 디코드. 시드 부재 시
  '(상인)' 중립 placeholder(실명 폴백 금지 — 라운드 2 P2 반영).
- aux.ownerName = `general.meta["owner_name"]` / tryExtendCloseDate 경로별 고정 / wall-clock 2종 /
  유니크 finished 메시지 분기 / 유산 경로 INSERT→연장→차감→환불 재배열 / isunited KV 게이트
  (rank 는 별도 문장이라 게이트 밖) / `refundInheritPoint` 현재-owner 재해석(부재=전체 no-op,
  npc>=2 KV 스킵+rank 기록).
- 라운드 2 P2 반영: npc>=2 검증 currentPoint=0.0(IPM:109-116 `?? [0,null]` — null 도달 불가) +
  CommandWireMapper `extendCloseDate` 키 수용(Vue 정본 키, BidUniqueAuction.php:41).
- 회귀 테스트 14종 추가 — AuctionBidHandlerTest 33/33.

## 라운드 2 — VERDICT: PASS

> 직전 9건 전부 PHP 원문 대조로 해소 확인, 회귀 테스트가 각 결함을 핀. 잔존 P2 는 도달성 낮은
> 잠복/격리 후보 — 백로그 기록 권고, 머지 차단 아님.

잔존 P2 처리:
- npc>=2 검증 게이트 — **즉시 수정** (위 반영).
- hiddenSeed 부재 실명 폴백 — **즉시 수정** ('(상인)').
- wire `extendCloseDate` 키 — **즉시 수정** (intake 양키 수용).
- 1순위 가드 열린 경매 목록 DB-only(같은 런 pending 경매 INSERT 비가시) — **백로그**
  (LEDGER: 같은 런 open→bid 시나리오, AuctionOpenHandler 동일-아이템 검사도 같은 비대칭).

## 게이트 증거

- 변경 파일 ↔ 테스트 매핑:
  - `app/game-engine/src/main/kotlin/opensamguk/engine/auction/AuctionBidHandler.kt` ←
    `app/game-engine/src/test/kotlin/opensamguk/engine/auction/AuctionBidHandlerTest.kt` 33종.
  - `logic/src/main/kotlin/opensamguk/logic/auction/AuctionBidValidator.kt` ← 동 테스트의 검증
    문자열 byte-핀(즉시판매가/현재입찰가/1%/+10/부족 메시지) + :logic:test 2123 green.
  - `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandWireMapper.kt`
    (`extendCloseDate` 키 수용) ← CommandWireMapperTest 9/9 + ReservedCommandsControllerTest 6/6.
- 경매 패키지: AuctionBidHandlerTest 33/0/0 + 패키지 53 tests green (XML).
- 베이스라인 풀게이트(db80c05): common/logic/engine/game-api green, infra 는 Docker 기동 경합
  플레이크 2건 → 단독 재실행 green 으로 입증(AuctionFlushIT 1/0/0).
- 최종: `:app:game-engine:test` + `:app:game-api:test` --rerun-tasks green (푸시 전 확인).

Verdict: cleared
