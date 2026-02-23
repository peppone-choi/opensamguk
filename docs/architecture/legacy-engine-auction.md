# Legacy Auctions and Betting

This document covers the auction and betting systems: how auctions open/close,
what resources they trade, and how betting payouts are calculated. References
include `legacy/hwe/sammo/Auction*.php`, `legacy/hwe/func_auction.php`, and
`legacy/hwe/sammo/Betting.php`.

## Auction Types and Entry Points

- `Auction` (base class): shared bid logic and close-date extensions.
- `AuctionBuyRice`: sell rice for gold.
- `AuctionSellRice`: sell gold for rice.
- `AuctionUniqueItem`: bids use inheritance points for unique items.
- `processAuction()` in `legacy/hwe/func_auction.php` closes finished auctions.
- `registerAuction()` creates neutral (NPC-hosted) buy/sell auctions.

## Auction Data Model

Primary tables:

- `ng_auction`: auction metadata (type, target, host, close date, detail).
- `ng_auction_bid`: per-bid rows for each auction.

The `AuctionInfo` and `AuctionInfoDetail` DTOs are serialized into
`ng_auction` columns for structured access.

## Core Auction Flow

1. **Open**
   - `AuctionBasicResource::openResourceAuction()` validates amounts and
     host resources, then inserts `ng_auction`.
   - `AuctionUniqueItem::openItemAuction()` checks availability, reserves
     unique items, and posts a global history notice.
2. **Bid**
   - Bids are inserted into `ng_auction_bid` and can extend the close date.
   - Reverse auctions (when `detail->isReverse`) sort by lowest bid.
3. **Close** (`tryFinish()` from the concrete auction)
   - If no bids: `rollbackAuction()` returns resources to host and logs.
   - With bids: `finishAuction()` transfers resources, logs, and sends messages.
4. **Processing**
   - `processAuction()` scans auctions past `close_date` and finishes them.

## Unique Item Auctions

- Currency: inheritance points (`ResourceType::inheritancePoint`).
- Host identity is obfuscated using `genObfuscatedName()` and a deterministic
  shuffled name pool stored in `game_env.obfuscatedNamePool`.
- Limits and availability are enforced against `GameConst::$allItems` and
  existing ownership in `general` rows.

## Betting System

Betting is an independent KV-backed system:

- `Betting::openBetting()` stores `BettingInfo` in KV storage (`betting`).
- `Betting::bet()` inserts or updates `ng_betting` rows and charges either
  gold or inheritance points.
- `Betting::giveReward()` distributes payouts to winners (exclusive or shared).

Event actions controlling lifecycle:

- `OpenNationBetting`: opens a nation-strength bet and registers a
  `DESTROY_NATION` event to close it.
- `FinishNationBetting`: evaluates winners when only N nations remain.

## RNG Notes

- `Auction::genObfuscatedName()` uses `hiddenSeed + 'obfuscatedNamePool'` to
  shuffle the name pool once and reuse it from `game_env`.
- `registerAuction()` relies on an injected RNG to randomize neutral auctions.

## Open Questions / Follow-ups

- The exact schedule for `registerAuction()` is outside this file; it is
  likely called from monthly or timed maintenance.
- Unique-item auction close-date extension limits depend on
  `AuctionUniqueItem` constants and `turnterm`; verify scenarios that override
  auction timing.
