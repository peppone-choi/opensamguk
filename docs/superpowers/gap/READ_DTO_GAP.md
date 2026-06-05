# READ_DTO_GAP — PHP API response shapes vs Kotlin read DTOs

Dimension: **read-model / API response data-shape parity** (NOT command mutation — that is `PARITY_LEDGER.md`).

Sources compared, field-by-field:
- PHP contract: `legacy/devsam-core/hwe/ts/defs/API/*.ts` (TS response types) + the `j_*.php` / `sammo/API/**` builders + shared `hwe/ts/defs/index.ts` types (`NationStaticItem`, `SimpleNationObj`, `TurnObj`, `MapResult`, `CommandItem`).
- Kotlin: `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/*.kt` + the controller assembly in `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/*.kt`.

Status legend per response:
- **PRESENT** — Kotlin DTO exists and covers the PHP shape (field set ≈ equal, modulo cosmetic rename).
- **PARTIAL** — Kotlin DTO exists but DROPS fields PHP returns, or RENAMES the envelope so the legacy `hwe/ts/` client cannot bind.
- **MISSING** — no Kotlin equivalent endpoint/DTO at all.

> **Key structural divergence (applies to almost every list response):** PHP `hwe/ts/` ships a **column+row** wire format — `{column: keyof[], list: ValuesOf<>[][]}` (see `RawGeneralListP0/1/2`, `j_get_basic_general_list`, `j_get_city_list`'s `cityArgsList`+`cities`, `MapResult`'s compact tuples). Kotlin returns **named-object arrays** (`List<PublicGeneral>` etc.). This is a *deliberate, repo-wide* re-shape — the new `web/game` Next pages are built against the object form, NOT the legacy Vue client. So "renamed envelope" below is flagged as a gap **only** where it also drops data; the column→object reshape itself is intentional and not counted as a per-field miss.

---

## 1. GetMap / GetWorldMap  (`Global.ts` MapResult + `func_map.php getWorldMap` / `j_map.php`)  →  `MapPreviewDto.kt`  — **PARTIAL**

PHP `MapResult` (compact tuples):
- envelope: `result, version, startYear, year, month, spyList (Record<cityNo,remainMonth>), shownByGeneralList (number[]), myCity, myNation`
- `cityList[]` tuple = **`[city, level, state, nation, region, supply]`** (6 ints)
- `nationList[]` tuple = **`[nation, name, color, capital]`**

Kotlin `MapPreviewResponse`: `serverName, year, month, mapCode, width, height, cities[], nations[]`
- `MapPreviewCity` = `{id, name, level, nationId, x, y}`
- `MapPreviewNation` = `{id, name, color}`

| PHP field | Kotlin | Gap |
|---|---|---|
| city `state` | — | **MISSING** (city visual state / 공성·함락 marker driver) |
| city `region` | — | **MISSING** (region used for map grouping/coloring) |
| city `supply` | — | **MISSING** (보급 cut → 도시 회색 표시) |
| city `name` | `name` | present (PHP map sends name via separate cityConst, Kotlin inlines it — OK) |
| nation `capital` | — | **MISSING** at nation level (Kotlin only has it on `FrontNationInfo`) |
| `spyList` (정찰 잔여개월/도시) | — | **MISSING** — no fog-of-war / 첩보 surface |
| `shownByGeneralList` (아국 장수 소재 도시) | — | **MISSING** — drives "내 장수 있는 도시" highlight |
| `startYear` | — | **MISSING** |
| `version` | — | MISSING (cache versioning) |
| `myCity` / `myNation` | — | MISSING (Kotlin carries elsewhere; map response itself lacks it) |
| — | `x`, `y` (per city) | Kotlin ADDS (from `scenario/cities_1010.json`); PHP derives coords client-side from cityConst |

Note: `MapPreviewResponse` is scoped to the **gateway lobby preview**, not the in-game world map. There is **no full GetMap equivalent** for the game client yet — `spyList`/`shownByGeneralList`/`state`/`supply` fog & supply rendering is entirely absent.

---

## 2. GetGeneralList  (`Nation.ts` `GeneralListItemP0/P1/P2` + `RawGeneralList*` + `j_get_basic_general_list`)  →  `PublicGeneral` / `MyGeneralSummary` / rankings  — **PARTIAL (large field loss on the authed tiers)**

PHP returns **3 permission tiers**. `GeneralListItemP0` (public, ~30 fields) ⊂ `P1` (+~35 fields) ⊂ `P2`. Kotlin only models the **P0 public surface** (`PublicGeneral`, 10 fields) and a thin `MyGeneralSummary` (10 fields). There is **no P1/P2 DTO**.

`PublicGeneral` = `{id, name, nation, nationColor, officerLevel, leadership, strength, intel, crew, cityName}`.

Missing even from the **P0** surface (PHP P0 has these, Kotlin drops):
| PHP P0 field | Gap |
|---|---|
| `npc` | **MISSING** (NPC type — drives 회색/색 표시) |
| `injury` | **MISSING** |
| `explevel`, `dedlevel`, `dedLevelText`, `honorText`, `officerLevelText` | **MISSING** (계급/공헌 텍스트 라벨) |
| `gold`, `rice` | (intentionally absent at P0 — OK) |
| `killturn`, `picture`, `imgsvr`, `age` | **MISSING** |
| `specialDomestic`, `specialWar`, `personal` | **MISSING** (특기 아이콘) |
| `belong`, `lbonus`, `ownerName`, `bill` | **MISSING** |
| `reservedCommand: TurnObj[]` | **MISSING** (예약 명령 미리보기) |
| `autorun_limit`, `refreshScoreTotal` | **MISSING** |
| `troop`, `city` (id) | partial (`cityName` only, no `city` id, no `troop`) |

The **entire P1 block** (exp breakdown `leadership_exp/strength_exp/intel_exp`, `dex1..5`, `crewtype/crew/train/atmos`, `horse/weapon/book/item`, `warnum/killnum/deathnum/killcrew/deathcrew/firenum`, `experience/dedication`, `defence_train`, `recent_war`, `specage/specage2`) has **NO Kotlin DTO**. The `장수일람` page cannot render the detailed/owned columns.

Also missing envelope: PHP `RawGeneralList*` carries `troops[]`, `env{year,month,turntime,turnterm,killturn,autorun_user}`, `myGeneralID`, `permission`. Kotlin `/api/generals` returns a bare `List<PublicGeneral>` — **no env, no troops, no permission, no myGeneralID**.

---

## 3. GetNationList / NationInfo  (`Nation.ts` `NationItem`/`NationInfoFull`/`NationStaticItem` + `Global.ts` `SimpleNationObj`)  →  `FrontNationInfo` / `KingdomRank` / `NationFinanceResponse`  — **PARTIAL**

There is **no single GetNationList endpoint**; the nation surface is split across `FrontNationInfo` (front-info), `KingdomRank` (rankings), `NationFinanceResponse` (finance), and the `nation{}` block of front-info. Combined coverage vs PHP `NationItem` (= `NationStaticItem` + extras):

PHP `NationStaticItem` = `{nation, name, color, type, level, capital, gennum, power}`.
PHP `NationItem` adds `{gold, rice, bill, rate, secretlimit, chief_set, scout, war, strategic_cmd_limit, surlimit, tech}`.
PHP `SimpleNationObj` (diplomacy) = `{capital, cities[], color, gennum, level, name, nation, power, type}`.

| PHP field | Kotlin location | Gap |
|---|---|---|
| `type` (국가성향 raw key) | front-info `nation.type{raw,name,pros,cons}` only | **MISSING in FrontNationInfo DTO**; the rich `{raw,name,pros,cons}` object is in the PHP front-info but Kotlin `FrontNationInfo` has **no type field at all** |
| `gennum` | KingdomRank `genNum` | present (renamed) |
| `power` | KingdomRank `power` | present (proxy) |
| `bill` | NationFinance `bill` | present |
| `rate` (세율) | NationFinance `rate` | present |
| `secretlimit` | NationFinance `secretLimit` | present (renamed) |
| `chief_set` | — | **MISSING** (수뇌 설정 비트) |
| `scout` (금지) | NationFinance `blockScout` | present (bool) |
| `war` (금지) | NationFinance `blockWar` | present (bool) |
| `strategic_cmd_limit` | front-info `nation.strategicCmdLimit` | present in front-info only |
| `surlimit` | — | **MISSING** (항복/외교 제한) |
| `tech` | FrontNationInfo `tech` | present |
| `cities[]` (SimpleNationObj) | — | **MISSING** (diplomacy nation→cities membership) |
| front-info `nation.topChiefs` (11/12 → {officer_level,no,name,npc}) | — | **MISSING** — no 군주/참모 surface in any nation DTO |
| front-info `nation.diplomaticLimit` | — | **MISSING** |
| front-info `nation.impossibleStrategicCommand: [string,number][]` | NationFinance has `warSettingCnt` only | **MISSING** (불가 전략명령 목록 + 잔여턴) |
| front-info `nation.onlineGen`, `prohibitScout/prohibitWar`, `notice (NationNotice)` | — | **MISSING** (`notice` 국가방침 공지 객체 entirely absent) |
| front-info `nation.population{cityCnt,now,max}`, `crew{generalCnt,now,max}` | — | **MISSING** (인구/병력 집계 객체) |

---

## 4. GetCityList  (`j_get_city_list.php`)  →  (no dedicated DTO; `MyCitySummary` / `FrontCityInfo` / `MapPreviewCity`)  — **PARTIAL**

PHP `j_get_city_list` returns `{nations, cityArgsList:['city','nation','name','level'], cities: ValuesOf[][]}` — a thin 4-column public list. Kotlin has **no `/api/cities` public endpoint**; the closest are `MyCitySummary` (nation-scoped) and `FrontCityInfo` (single-city, very full). So the **public all-city 도시일람** list endpoint is **MISSING** (only nation-scoped `my-cities` exists).

`FrontCityInfo` is actually RICHER than `j_get_city_list` (carries agri/comm/secu/def/wall + max + trust + trade) — good. But it is single-city only. Gaps:
- No public city list (`/api/cities`) → **MISSING**.
- `FrontCityInfo` / `MyCitySummary` lack `state` and `supply` (present in `func_map.php` cityList) → **MISSING** (도시 상태/보급 표시).
- `MyCitySummary` drops agri/comm/secu/trust/trade that `FrontCityInfo` has — acceptable (summary tier).

---

## 5. GetFrontInfo  (`Global.ts` `GetFrontInfoResponse`)  →  `FrontInfoResponse`  — **PARTIAL (large global block loss)**

The single biggest read shape. Kotlin `FrontInfoResponse{global, general, nation, city, recentRecord}` covers the skeleton but the `global{}` block is heavily trimmed.

`FrontGlobalInfo` (Kotlin) = `{year, month, turnterm, scenario, scenarioText, generalCount, nationCount, cityCount, npcCount}`.

PHP `global{}` additionally carries (ALL **MISSING** in Kotlin):
`extendedGeneral, isFiction, npcMode, joinMode, startyear, autorunUser{limit_minutes,options}, lastExecuted, lastVoteID, develCost, noticeMsg, onlineNations, onlineUserCnt, apiLimit, auctionCount, isTournamentActive, isTournamentApplicationOpen, isBettingActive, isLocked, tournamentType, tournamentState, tournamentTime, genCount:[number,number][], generalCntLimit, serverCnt, lastVote:VoteInfo`.

These drive nearly every header gate (토너먼트/베팅/경매 활성 배지, 온라인 인원, develCost 내정비용, 정원, 마지막 투표). → broad **MISSING**.

`general{}` block: PHP returns full `GeneralListItemP1 & {permission, troopInfo{leader{city,reservedCommand}, name}}`. Kotlin `FrontGeneralInfo` = `{hasGeneral, generalId, name, nationId, officerLevel, permission, showSecret, L/S/I, injury, gold, rice, crew, cityId}` — **drops** the entire P1 stat/equip/exp surface AND `troopInfo`. → **PARTIAL**.

`nation{}` block: see §3 — Kotlin `FrontNationInfo` drops `type{}`, `topChiefs`, `population`, `crew`, `notice`, `impossibleStrategicCommand`, `diplomaticLimit`, `onlineGen`, `prohibit*`. → **PARTIAL**.

`city{}` block: PHP `city.officerList` (태수/군사/종사 2/3/4 → {name,npc}) → **MISSING** in `FrontCityInfo`. `nationInfo{id,name,color}` → Kotlin has `nationId` only (no name/color). `pop/agri/comm/secu/def/wall` are `[cur,max]` tuples in PHP vs flat `x`+`xMax` in Kotlin (reshape, OK). → **PARTIAL** (officerList missing).

`recentRecord`: PHP = `{history,global,general:[number,string][], flush*}`. Kotlin = `List<String>` and is **hard-coded `emptyList()`** in the controller → **effectively MISSING** (recent log feed never populated).

`aux.myLastVote` → **MISSING**.

---

## 6. ChiefCenter / GetReservedCommand  (`NationCommand.ts` `ChiefResponse` + `sammo/API/NationCommand/GetReservedCommand.php`)  →  `ChiefReservedResponse`  — **PARTIAL**

PHP `ChiefResponse` = `{result, lastExecute, year, month, turnTerm, date, chiefList:Record<lvl,{name,turnTime,officerLevelText,officerLevel,npcType,turn:TurnObj[]}>, troopList:Record<id,name>, isChief, autorun_limit, officerLevel, commandList:[{category,values:CommandItem[]}], mapName, unitSet}`.

Kotlin `ChiefReservedResponse` = `{result, nationId, maxChiefTurn, posts:[{officerLevel,title,reservedTurns:[{turnIdx,actionCode,brief}]}]}`.

| PHP field | Kotlin | Gap |
|---|---|---|
| `chiefList[].name / turnTime / npcType` | — | **MISSING** (who holds the post, 다음 턴 시각, NPC 여부) — Kotlin `ChiefPost` only has level+title+turns |
| `chiefList[].turn[].arg` (TurnObj has `action,brief,arg`) | `ChiefReservedTurn{turnIdx,actionCode,brief}` | **MISSING `arg`** — reserved-command argument payload dropped (can't re-render the command form) |
| `troopList` | — | **MISSING** (부대 목록 for 발령 commands) |
| `commandList` (the available 수뇌 command palette, `CommandItem` w/ possible/reqArg/compensation/info) | — | **MISSING ENTIRELY** — the chief-center cannot show which commands are issuable |
| `lastExecute, date, year, month, turnTerm` | — | **MISSING** (turn timing header) |
| `isChief, autorun_limit, officerLevel` | — | **MISSING** |
| `mapName, unitSet` | — | **MISSING** |
| — | `nationId, maxChiefTurn` | Kotlin-only (OK) |

This is the largest single-endpoint gap: only the reserved-turn skeleton survived; the command palette, post holders, troop list, and timing are all gone.

---

## 7. GetDiplomacy  (`Global.ts` `GetDiplomacyResponse`)  →  `DiplomacyMatrixResponse` + `DiplomacyConflictResponse` + F4 `DiplomacyLettersResponse`  — **PARTIAL**

PHP `GetDiplomacyResponse` = `{result, nations:SimpleNationObj[], conflict:[number,Record<number,number>][], diplomacyList:Record<src,Record<dest,diplomacyState>>, myNationID}`.

Kotlin splits into 3 endpoints. Coverage:
- `diplomacyList` (src→dest→state) → covered by `DiplomacyMatrixResponse` / conflict `matrix`. State masking (3~7→2) matches PHP `neutralDiplomacyMap`. **OK.**
- `conflict` → `DiplomacyConflictResponse.cities[]` (per-city map). **OK** (richer: adds cityName).
- `nations: SimpleNationObj[]` → Kotlin `DiplomacyNationInfo{id,name,color}` only. **MISSING** `capital, cities[], gennum, level, power, type` from `SimpleNationObj` → the diplomacy nation cards lose 수도/도시수/장수수/등급/국력/성향. → **PARTIAL**.
- `myNationID` → present (letters). **OK.**

The PHP single-call shape is split into 3; the FE must now stitch — acceptable, but the `SimpleNationObj` field loss is a real gap.

---

## 8. myInfo / my-page / my-* (no PHP TS type — `j_basic_info` / `j_myBossInfo`)  →  `MyPageResponse` etc.  — **PRESENT**

`MyPageResponse` is a Kotlin-native shape (no direct PHP TS contract; PHP uses `GeneralListItemP1` for self). Covers generalId/name/nation/city/officer/perm/L/S/I/injury/exp/ded/gold/rice/crew/train/atmos/picture/imageServer. Lacks the P1 equip/特기/dex/war-record block, but as a "my summary" tier this is acceptable. `my-boss` mirrors `j_myBossInfo`. → **PRESENT** (tier-appropriate; flag P1 detail only if 내정보 needs full sheet).

---

## 9. rankings  (no single PHP TS type — `v_*`/legacy ranking pages)  →  `RankingDto.kt`  — **PRESENT (with documented zero-fills)**

`BestGeneral, GeneralRank, KingdomRank, NpcGeneral` are well-formed and join nation/city correctly. `HallRecord, TrafficSummary, EmperorRecord` are **explicit zero-fill / empty** (spec OQ-1/2/5 — no source table yet), documented as NOT fabricated. → **PRESENT**; the empty boards are a known data-source backlog, not a shape mismatch.

---

## 10. Auction  (`Auction.ts`)  →  `AuctionDto.kt`  — **PARTIAL**

PHP has TWO auction shapes: **BasicResource** (buy/sellRice) and **UniqueItem**, each with rich bidder objects and list/detail variants (`ActiveResourceAuctionList`, `UniqueItemAuctionList/Detail`, `OpenAuctionResponse`).

Kotlin `AuctionResponse{id,type,finished,target,hostGeneralId,reqResource,openDate,closeDate,detail}` + `AuctionBidResponse{no,auctionId,generalId,owner,amount,date,aux}` — a flat single shape.

| PHP field | Gap |
|---|---|
| `hostName` | **MISSING** (only hostGeneralId) |
| `amount, startBidAmount, finishBidAmount` (resource) | partial — only generic `amount` on bid; auction-level amounts **MISSING** |
| `highestBid{amount,date,generalID,generalName}` | **MISSING** (no embedded highest bid) |
| `remainCloseDateExtensionCnt, availableLatestBidCloseDate` (unique) | **MISSING** (연장 횟수/마감) |
| `isCallerHost, isCallerHighestBidder` | **MISSING** (caller-relative flags) |
| `obfuscatedName, remainPoint` | **MISSING** (유산포인트 잔액 + 익명화) |
| `recentLogs[]` | **MISSING** (경매 최근 로그) |
| resource vs unique split | collapsed into one `type` string | **PARTIAL** (FE can't distinguish list layouts) |

---

## 11. Betting  (`Betting.ts`)  →  `BettingDto.kt` + (F4 it's referenced but no full DTO)  — **PARTIAL**

PHP `BettingListResponse`/`BettingDetailResponse` carry `BettingInfo{id,type,name,finished,selectCnt,isExclusive,reqInheritancePoint,openYearMonth,closeYearMonth,candidates:Record<SelectItem>,winner}`, plus `bettingDetail`, `myBetting`, `remainPoint`, `totalAmount`, year/month.

Kotlin `BettingItemResponse{id,bettingId,generalId,userId,bettingType,amount}` — only the **bet row**, no betting *definition* shape.

| PHP | Gap |
|---|---|
| `BettingInfo` (name/candidates/selectCnt/open-closeYearMonth/winner/...) | **MISSING ENTIRELY** — no betting-definition DTO; FE can't render the betting board/candidates |
| `bettingDetail:[string,number][]` (집계) | **MISSING** |
| `myBetting`, `remainPoint`, `totalAmount` | **MISSING** |
| `SelectItem{title,info,isHtml,aux}` | **MISSING** |

Only individual bet records are exposed; the betting market view is absent.

---

## 12. Vote  (`Vote.ts`)  →  F4 `VoteSummary` + `VoteDetailResponse`  — **PARTIAL (close)**

PHP `VoteInfo{id,title,opener,multipleOptions,startDate,endDate,options[]}`, `VoteDetailResult{voteInfo,votes:[number[],number][],comments:VoteComment[],myVote,userCnt}`, `VoteComment{id,voteID,generalID,nationName,generalName,text,date}`.

Kotlin `VoteSummary{id,title,openerName,multipleOptions,startAt,endAt,closed}` + `VoteDetailResponse{...,body,options:VoteOptionResult[],userCnt,myVote,comments}`.

| PHP | Kotlin | Gap |
|---|---|---|
| `votes:[number[],number][]` (옵션조합별 집계) | `options:[{index,text,count}]` | reshape; PHP supports **multi-option combination tallies**, Kotlin flattens to per-option counts → **PARTIAL** loss for `multipleOptions>1` |
| `VoteComment.generalID/voteID` | dropped (only name) | minor **MISSING** (id linkage) |
| `body` | Kotlin adds | OK |
| otherwise | | **mostly PRESENT** |

---

## 13. Message / Mailbox  (`Message.ts`)  →  `MessageDto.kt` + `MailboxController`  — **PARTIAL (heavy)**

PHP `MsgItem`/`MsgPrintItem`/`MsgResponse`/`MailboxItem` are RICH: typed `src/dest:MsgTarget{id,name,nation_id,nation,color,icon}`, `option{action,invalid,deletable,overwrite,hide,silence,delete}`, `msgType`, render flags (`colorType,defaultIcon,allowButton,last5min,invalidType`), `sequence`, `latestRead{private,diplomacy}`, and the diplomacy-action embedding (`MsgActionType: scout|noAggression|cancelNA|stopWar`).

Kotlin `MessageResponse{id,mailbox,type,src:Int,dest:Int,time,validUntil,message}` — `src`/`dest` are **bare ints, not MsgTarget objects**.

| PHP | Gap |
|---|---|
| `src/dest: MsgTarget{name,nation,color,icon,...}` | **MISSING** — only numeric ids; FE can't render sender card |
| `option{action,deletable,overwrite,silence,...}` | **MISSING ENTIRELY** (diplomacy-action buttons, 삭제/무효 상태) |
| `msgType` enum (private/public/national/diplomacy) | partial (`type` string) |
| `sequence, latestRead, last5min, invalidType, allowButton` | **MISSING** (읽음 동기화 + 버튼 게이트) |
| `MsgResponse` per-type bucketing | **MISSING** (Kotlin returns flat list) |
| `MailboxItem{mailbox,color,name,nationID,general:[id,name,nation][]}` | partial in MailboxController — verify field coverage |

---

## 14. InheritPoint  (`InheritAction.ts`)  →  `InheritPointController` + F4 `InheritPointResponse`  — **PRESENT (rich)**

Kotlin `InheritPointResponse` is FULLER than the thin `InheritAction.ts` (which only types `inheritBuffType`, `InheritPointLogItem`, `InheritResetStat`). Kotlin covers items/currentInheritBuff/cost table/availableSpecialWar/availableUnique/logs/currentStat. → **PRESENT**. (PHP's real shape lives in `v_inheritPoint.php` Vue state; Kotlin built directly against the page need — appears complete.)

---

## 15. const / global-menu / tournament / troops / board / npc-policy / history

- **GetConst** (`Global.ts GetConstResponse`): PHP returns `{gameConst, gameUnitConst, cityConst, cityConstMap, iActionInfo, iActionKeyMap, version}` — a huge static bundle. Kotlin `GameConstResponse{mapName,mapWidth,mapHeight,maxTurn,officerLevelText}` — **PARTIAL/MISSING-heavy**: no unit consts, no city consts, no iActionInfo (command metadata), no region/level maps. FE 특기/병종/도시상수 tables have no source. → **PARTIAL (large)**.
- **GetMenu** (`Global.ts GetMenuResponse`): Kotlin `GlobalMenuResponse`/`MenuNode` mirror the typed union well → **PRESENT**.
- **Tournament**: no PHP TS type in `defs/API`; Kotlin `TournamentResponse` is page-driven → **PRESENT** (verify against `v_*` tournament view if a parity gap surfaces).
- **Troops** (`troops` block exists inside General/Chief responses): Kotlin `TroopsResponse{troops:[{troopLeader,name,nation,leaderName,members[],memberCount}]}` is richer than PHP's `Record<id,name>` → **PRESENT**.
- **Board** (`j_board_get_articles`): Kotlin `BoardResponse{secret,title,articles[{...,comments}]}` → **PRESENT** (verify author/nation/color fields if needed).
- **NpcPolicy**: PHP `NationPolicy` (index.ts) has ~25 typed numeric fields (reqNationGold, CombatForce, SupportForce, DevelopForce, req*Gold/Rice ×8, min*, properWarTrainAtmos, cureThreshold). Kotlin `NpcPolicyResponse` uses **untyped `Map<String,Any?>`** for default/current policy → shape-opaque; data may pass through but FE loses the typed contract → **PARTIAL** (no field guarantees).
- **History** (`Global.ts HistoryObj/GetHistoryResponse`): Kotlin `HistoryResponse{months:[{year,month,profileName,map:Map,nations:Map}]}` uses untyped maps; PHP `HistoryObj` has typed `map:MapResult, global_history[], global_action[], nations[{capital,cities,color,gennum,level,name,nation,power,type}]`. → **PARTIAL** (untyped + missing global_history/global_action feeds).

---

## Roll-up

| # | Response | Status |
|---|---|---|
| 1 | GetMap / world map | PARTIAL (fog/supply/state/region/spy missing) |
| 2 | GetGeneralList P0/P1/P2 | PARTIAL (P1/P2 tiers absent; P0 thin) |
| 3 | GetNationList / NationItem | PARTIAL (type/topChiefs/notice/population/limits missing) |
| 4 | GetCityList (public) | PARTIAL→MISSING (no public city list) |
| 5 | GetFrontInfo | PARTIAL (global block heavily trimmed; recentRecord empty) |
| 6 | ChiefCenter / ReservedCommand | PARTIAL (commandList/chief holders/troopList/arg missing) |
| 7 | GetDiplomacy | PARTIAL (SimpleNationObj fields missing) |
| 8 | myInfo / my-* | PRESENT |
| 9 | rankings | PRESENT (documented zero-fills) |
| 10 | Auction | PARTIAL (bidder/highestBid/host/remainPoint/logs missing) |
| 11 | Betting | PARTIAL (no BettingInfo/candidates/detail) |
| 12 | Vote | PARTIAL (multi-option combo tallies) |
| 13 | Message / Mailbox | PARTIAL (MsgTarget/option/sequence missing) |
| 14 | InheritPoint | PRESENT |
| 15a | GetConst | PARTIAL (unit/city/iAction consts missing) |
| 15b | GetMenu | PRESENT |
| 15c | Tournament / Troops / Board | PRESENT |
| 15d | NpcPolicy / History | PARTIAL (untyped maps) |

**Counts (18 audited responses):** PRESENT 6 · PARTIAL 11 · MISSING(-ish) 1 (public city list).

**Top field-loss themes feeding FE gaps:**
1. **ChiefCenter `commandList` + chief holders + `troopList` + `arg`** — the 수뇌 command palette and post roster are entirely gone.
2. **GetFrontInfo `global{}` block** — ~25 header gates (tournament/betting/auction active, online counts, develCost, generalCntLimit, lastVote) all missing → the game header can't gate menus.
3. **GeneralList P1/P2 tier** — no DTO for owned/detailed columns (exp, dex, equip, war-record); 장수일람 detail view unrenderable.
4. **Message MsgTarget/option** — src/dest are bare ints; diplomacy-action buttons & sender cards impossible.
5. **Map fog/supply** — `spyList`, `shownByGeneralList`, city `state`/`supply`/`region` absent → no fog-of-war or supply-cut rendering.
6. **Betting market** — no `BettingInfo`/candidates DTO; only bet rows.
7. **GetConst static bundle** — unit/city/iAction consts missing → 특기·병종·도시 상수 tables have no client source.
8. **Nation `notice`/`topChiefs`/`population`/`impossibleStrategicCommand`** — nation header surface incomplete.
