# 2026-06-19 In-Game Read 500 Review

Verdict: cleared

## Scope

장수 로그인 후 인게임 페이지를 순회하면 일부 기능이 500으로 죽는 문제를 실서버에서 재현했다. 문서 라우트(`/game/s1/*`)는 미들웨어 rewrite로 200을 반환했지만, 경매장과 사령부 read API가 실패했다.

## Evidence

- `legacy/devsam-core/hwe/sammo/API/Auction/GetActiveResourceAuctionList.php:41-45`: 자원 경매는 `ng_auction.type IN (AuctionType::BuyRice->value, AuctionType::SellRice->value)`와 `finished = 0`으로 조회한다. 값은 PHP enum value인 `buyRice`/`sellRice`다.
- `legacy/devsam-core/hwe/sammo/API/Auction/GetUniqueItemAuctionList.php:35-38`: 유니크 경매도 `AuctionType::UniqueItem->value`인 `uniqueItem`으로 조회한다.
- `legacy/devsam-core/hwe/sammo/API/NationCommand/GetReservedCommand.php:64-65`: 사령부 read는 `game_env`의 `turnterm`, `year`, `month`, `turntime`을 읽고, 명령 표시는 현재 장수의 환경을 통해 계산된다.
- `legacy/devsam-core/hwe/sammo/API/General/GetFrontInfo.php:171-180` 및 `:202-209`: 프론트 전역 정보는 `startyear` lowercase KV를 읽어 내려준다.

## Runtime Root Cause

- `GET https://sam.peppone.dev/api/game/api/auctions`가 500을 냈다. game-api 로그는 `operator does not exist: ng_auction_type = character varying`를 기록했다. JPA 파생쿼리가 PostgreSQL enum 컬럼에 varchar 파라미터를 바인딩했다.
- `GET https://sam.peppone.dev/api/game/api/nation/chief-reserved`가 500을 냈다. game-api 로그는 `world_state.config.startYear is missing`을 기록했다. 실제 시드/운영 데이터는 lowercase `startyear`, `world_state.start_year`, `meta.startYear` 중 하나에 시작년도를 갖는다.

## Fix

- `AuctionEntity`는 PHP 저장값(`buyRice`, `sellRice`, `uniqueItem`, `gold`, `rice`, `inheritPoint`)으로 변환하는 JPA converter를 사용한다.
- `AuctionRepository`는 PostgreSQL enum 비교를 native query `CAST(:type AS ng_auction_type)`로 수행한다.
- `PrecheckStateViewFactory`는 `config.startYear`, `config.startyear`, `world_state.start_year`, `meta.startYear` 순으로 시작년도를 읽는다.

## Verification Plan

- `AuctionRepositoryIT`가 실제 PostgreSQL enum 컬럼에서 경매 조회가 성공하는지 검증한다. Docker 미사용 환경에서는 프로젝트 규칙에 따라 skip된다.
- `CommandPrecheckServiceTest`가 lowercase/컬럼 시작년도에서 precheck가 AVAILABLE을 유지하는지 검증한다.
- `AuctionControllerTest`, `F4ReadControllersTest`, `AuctionBidHandlerTest`로 controller/engine 호출 계약을 고정한다.
- 배포 후 실서버 브라우저에서 `/game/s1/auction`과 `/game/s1/chief-center`를 다시 열어 500 응답이 사라졌는지 확인한다.
