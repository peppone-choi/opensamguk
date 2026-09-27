# 2026-09-27 #917 — 경매 입찰·개설 명령과 조회 API 제거(A1)

## 범위

웹 소비자는 #985에서 지웠다(남은 것은 예약 경로 이름 목록과 주석뿐). 사람이 넣는 경매 입력과 조회 API를 지운다. 월별 중립 경매 등록(Q16)·만료 데몬·정산(`AuctionFinalize`)·recorder/flush·스냅숏 meta·엔티티·저장소·표 삭제는 A2(엔진 쓰기 쪽)로 남긴다. 정산이 남으므로 디스패처의 경매 저장소 매개변수와 `TurnRunService` 생성 조건도 A2에서 바꾼다.

- 삭제: `AuctionController`·`AuctionDto`·`AuctionCountReadRepository`(game-api), `AuctionBidHandler`·`AuctionOpenHandler`(engine), `AuctionBidValidator`(logic), 테스트 `AuctionControllerTest`·`AuctionBidHandlerTest`·`AuctionOpenHandlerTest`.
- 명령 경로: `CommandWireMapper`의 `auctionBid`·`auctionOpenBuyRice`·`auctionOpenSellRice`·`auctionOpenUnique` 코드·매핑, 공통 wire `AuctionBid`·`AuctionOpen*` 명령과 `AuctionBidOk/Fail`·`AuctionOpenResult` 결과·디코더 분기, 디스패처의 두 핸들러와 분기.
- 화면용 필드: FrontInfo `auctionCount`(웹 소비자 0).
- `HotColdCatalog`: `AuctionOpenHandler`(`auctionRepository.findByFinishedFalse`×2)·`AuctionBidHandler`(`findById`·`bidRepository.findByAuctionIdOrderByAmountDesc`·`findByFinishedFalseAndTypeValue`) 항목 제거. 가드는 호출·횟수 정확 일치다.
- 테스트 조정: wire 골든 corpus(명령 23→22, 결과 36→34), 명령 봉투 왕복은 `TroopJoin`으로, `Op127CrossWorldCohortIT`에서 경매 격리 단언 제거, `CommandWireMapperTest`에 은퇴 코드가 즉시 입력으로 못 들어온다는 단언 추가.
- naming lint: retired_reference 5844→5828.

## 검증

로컬 결과는 PR 본문에 적는다. CI(jvm-core·game-engine, 건너뜀 금지 가드)가 최종 판정한다.
