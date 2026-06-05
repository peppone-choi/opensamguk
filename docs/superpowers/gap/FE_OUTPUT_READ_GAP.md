# FE OUTPUT / READ-INFO PARITY GAP — displayed fields PHP/Vue vs Next.js

**Dimension:** Frontend OUTPUT-INFO (read/info pages) — does the Next.js page *render* the same
information/fields that the PHP+Vue grand truth renders?

**Scope:** READ pages only (메인 / 내정보 / 도시 / 국가 / 장수일람 / my-nation·my-cities·my-generals / 연감).
Mutation/command coverage is the PARITY_LEDGER's job — not repeated here.

**Method:** for each page I read the Vue component + the `j_*.php` feeder + `hwe/ts/defs/API/*.ts`
type (the contract) on the PHP side, then the matching `web/game/app/game/.../page.tsx` + the
`game-api` controller/DTO that feeds it, then diffed displayed fields.

**Grand truth files referenced (PHP):**
- `legacy/devsam-core/hwe/func.php` — `generalInfo()` L563, `generalInfo2()` L762, `cityInfo()` L153, `myNationInfo()` L190
- `legacy/devsam-core/hwe/templates/mainCityInfo.php` — city panel
- `legacy/devsam-core/hwe/ts/defs/API/General.ts` · `Nation.ts` · `Global.ts` (`GetFrontInfoResponse`, `GeneralListItemP0/P1/P2`)
- `legacy/devsam-core/hwe/b_myPage.php` · `j_server_basic_info.php` · `a_genList.php` · `b_currentCity.php`
- `legacy/devsam-core/hwe/ts/PageHistory.vue`

**This repo (Kotlin+Next):**
- `app/game-api/.../controller/FrontInfoController.kt` · `MyController.kt` · `GeneralsController.kt`
- `web/game/app/game/page.tsx` · `my-info`(page.tsx) · `city/` `nation/` `generals/` `my-nation/` `my-cities/` `my-generals/` `history/` `rankings/*`
- `web/game/components/game/GameInfo.tsx` (dashboard header)

Legend: ✅ present · 🟡 partial (field shape exists but breakdown/derived markup dropped) · ❌ missing.

---

## 1. 메인 대시보드 (PageFront / GameInfo header + 내정보 card)

PHP contract = `GetFrontInfoResponse` (`ts/defs/API/Global.ts`) — by far the richest single read payload.
Next renders it in two parts: `GameInfo.tsx` (the `global` header strip) + `page.tsx` MyPageContent (the
general/nation/city cards). The Kotlin feeder is `FrontInfoController.kt` (`/api/front-info`).

### 1a. `global` header (GameInfo.tsx vs `GetFrontInfoResponse.global`)
| field (PHP) | Next |
|---|---|
| scenarioText, year, month, turnterm, npcMode | ✅ |
| onlineUserCnt, apiLimit, generalCntLimit, npcCount | ✅ |
| serverCnt, tournamentType/State/Time, auctionCount, lastVote | ✅ (GameInfo renders all) |
| onlineNations (per-nation online list) | ❌ not surfaced by FrontInfoController.global |
| develCost, noticeMsg, lastVoteID, isLocked, isBetting/TournamentActive | 🟡 GameInfo reads some optimistically; FrontInfoController.buildGlobal() emits **none** of them (only year/month/turnterm/scenario/counts) |
| genCount `[number,number][]` (per-nation gen count chart) | ❌ |
| extendedGeneral / isFiction / joinMode / startyear | ❌ (server_basic_info has them; front-info DTO drops them) |

### 1b. `general` card (page.tsx vs `general: GeneralListItemP1 & {permission, troopInfo}`)
The PHP `generalInfo()`+`generalInfo2()` panel is the canonical "내정보" surface. Next's main card shows
only: name, officerLevel(급), leadership, strength, intel, experience, devotion, crew, gold, rice. Missing:
- ❌ **injury (부상)** state + color (위독/심각/중상/경상/건강)
- ❌ **train (훈련)/atmos (사기)** + their bonus markup `(+n)`
- ❌ **age (연령)** + retirement-color, **killturn (삭턴)**, **turntime/실행 remaining 분**
- ❌ **crewtype (병종)**, **horse/weapon/book/item (명마·무기·서적·도구)** equip info
- ❌ **specialDomestic/specialWar (내특/전특)** + **personality (성격)**
- ❌ **explevel (Lv) + level bar**, **lbonus (통솔 보너스 +n)**
- ❌ **defence_train (수비 함/안함)**, **troopInfo (부대)**, **refreshScore (벌점)**
- ❌ generalInfo2 block entirely: **명성/계급 (experience/dedication honor text + bonus)**, **전투/계략/사관 (warnum/firenum/belong년)**, **승률/승리/패배 (winRate/killnum/deathnum)**, **살상률/사살/피살 (killRate/killcrew/deathcrew)**, **숙련도 dex1..5 (보병·궁병·기병·귀병·차병) bars+text+short**
- ❌ **picture / imgsvr (general portrait)** — FrontGeneralInfo omits picture
- note: `MyController.my-page` DOES carry train/atmos/picture/experience/dedication; the **main page.tsx uses front-info** which omits them, and the **my-info page** (below) only renders a subset.

### 1c. `nation` card (page.tsx vs `GetFrontInfoResponse.nation`)
Next shows: name, level, genNum, power, pop, gold, rice. Missing vs PHP contract:
- ❌ **type (성향 raw/name/pros/cons)**, **bill (지급률)**, **taxRate (세율)**, **tech (기술)** value
- ❌ **capital**, **crew now/max (총병사)**, **population now/max** split (Next has flat pop only)
- ❌ **topChiefs (군주/참모 lvl 11/12 names)**, **notice (국가 공지)**, **onlineGen**
- ❌ **diplomaticLimit / strategicCmdLimit / impossibleStrategicCommand / prohibitScout / prohibitWar**
- FrontNationInfo DTO carries only id/name/color/level/gold/rice/tech/capitalCityId → **most of the above never reaches the client.**

### 1d. `city` card (page.tsx vs `GetFrontInfoResponse.city`)
Next shows: name, level, agri, comm, secu, def, wall, trade, pop. Mostly ✅ but:
- 🟡 shows current value only — PHP renders **`[now,max]` pairs** (pop/agri/comm/secu/def/wall) with progress bars. FrontCityInfo DTO **does** carry the `*Max` fields, but page.tsx renders only the current number (no max, no bar).
- ❌ **trust (민심)** — present in DTO (`trust`) + PHP, NOT rendered in the main city card (it is on the `/city` page though).
- ❌ **officerList (태수2/군사3/종사4 names)** — DTO doesn't carry it on front-info.
- ❌ **nationInfo (지배 국가 name/color)** badge.

**메인 page totals: PHP fields ≈ 78, present ≈ 26, partial ≈ 4, missing ≈ 48.**

---

## 2. 내정보 (b_myPage.php / generalInfo+generalInfo2 vs web/game/app/game/page.tsx ＝ MyPageContent)

There is **no dedicated `/game/my-info` route** — `web/game/app/game/page.tsx` IS the 내정보 surface and it
reuses the main general card (§1b). `MyController.my-page` is a richer DTO than front-info (adds
train/atmos/experience/dedication/picture/imageServer) but page.tsx does not render those extras.

Beyond §1b's stat gaps, the PHP `b_myPage` page is far larger and includes whole sections with **zero** Next equivalent:
- ❌ **개인 기록 (generalAction log, 24 rows + 이전 로그 불러오기)**
- ❌ **전투 기록 (battleDetail log)** · **전투 결과 (battleResult log)** · **장수 열전 (generalHistory)**
- ❌ settings block: **토너먼트 수동/자동**, **환약 사용 기준 (경상~위독)**, **자동 사령턴 허용**, **수비 훈사 90/80/60/40/999**, **설정저장 N회 남음**, **징계 목록(penalty)**, **휴가 신청**, **개인 CSS**, **아이템 파기**, **500/1000px 모드**
- ❌ pre-open buttons: **가오픈 장수 삭제 / 사전 거병 / 접경 귀환 / 다른 장수 선택**
- (settings are mutations — tracked partly in PARITY_LEDGER — but the **displayed state** of each toggle is read-info and is absent.)

**내정보 page totals: PHP fields/sections ≈ 60, present ≈ 10, missing ≈ 50** (overlaps §1b general stats; counted once in summary).

---

## 3. 현재 도시 / 도시정보 (cityInfo + mainCityInfo.php / b_currentCity vs web/game/app/game/city/page.tsx)

PHP `mainCityInfo.php` panel fields → Next `/city`:
| PHP panel | Next |
|---|---|
| name, region\|level header (【지역 \| 등급】) | 🟡 name yes; **region + levelText label ❌** |
| 지배 국가 name + color (or 공백지) | ❌ |
| 주민 pop/pop_max + bar | 🟡 shows `pop` only, no max, no bar |
| 민심 trust + bar | ❌ (not in city page) |
| 농업/상업/치안/수비/성벽 `x/x_max` + bar | 🟡 current only, **no max, no bars** |
| 시세 trade % (or 상인없음) + bar | 🟡 raw number, no %/상인없음 text |
| 태수(4)/군사(3)/종사(2) officer names | ❌ |
| (b_currentCity) 도시 내 장수 목록 + 예약 명령 | ❌ entire general-list-in-city table absent |

**도시 page totals: PHP fields ≈ 22, present ≈ 1, partial ≈ 6, missing ≈ 15.**

---

## 4. 국가 정보 (myNationInfo / NationInfoFull vs web/game/app/game/nation/page.tsx)

Next `/nation` shows: nation id/level/power/gold/rice/tech/gennum/cityCount + 도시 목록(name·pop·trade) +
유산 버프 구매 UI. PHP `myNationInfo()` / `NationItem` contract is much wider:
- ❌ **성향 type (getNationType / getNationType2)** — 야망/명분 etc.
- ❌ **군주/참모 (officer lvl 12/11 names)** = topChiefs
- ❌ **총주민 totpop/maxpop**, **총병사 totcrew/maxcrew (sum leadership*100)**
- ❌ **지급률 bill %**, **세율 rate %**
- ❌ **기술 techCall + TechLimit color** (Next shows raw tech value only, no 숙련 call/limit)
- ❌ **전략 제한 strategic_cmd_limit (가능/N턴 + tooltip of impossible cmds)**
- ❌ **천도 제한 surlimit**, **모병 금지 scout 허가/금지**, **전쟁 금지 war 허가/금지**
- ❌ **spy / 첩보 secretlimit**, **chief_set**, **국가 공지 notice**
- 🟡 power shown but as flat number (PHP marks 재야 "해당 없음" cases)

**국가 page totals: PHP fields ≈ 26, present ≈ 7, partial ≈ 1, missing ≈ 18.**

---

## 5. 장수일람 (a_genList / GeneralListItemP0/P1/P2 vs web/game/app/game/generals/page.tsx)

PHP list is permission-tiered: P0 (public) → P1 (own nation) → P2 (full secret). Sort selector has **15
types** (국가/통솔/무력/지력/명성/계급/관직/삭턴/벌점/Lv/성격/내특/전특/병종/병사). The Next `generals` page
consumes only the **P0 public projection** (`GeneralsController` = id/name/nation/nationColor/officerLevel/
L/S/I/crew/cityName). Gaps:
- ❌ **P1 fields**: injury, explevel/dedlevel, gold, rice, killturn, age, **specialDomestic/specialWar/personal**, honorText, dedLevelText, bill, **reservedCommand (예약 명령)**, crewtype, train, atmos, **dex1..5**, warnum/killnum/deathnum/killcrew/deathcrew/firenum
- ❌ **P2 secret fields** entirely (no permission tiering on the Kotlin side — single public DTO)
- ❌ **refreshScoreTotal (벌점)**, **ownerName (소유자, 통일 서버)**
- 🟡 **sort selector** — Next has client search + nation filter; PHP's 15-way sort (명성/계급/관직/삭턴/벌점/Lv/성격/내특/전특/병종) is not reproduced.
- ✅ basic name/nation/L/S/I/crew/officer/city render.

**장수일람 totals: PHP fields ≈ 40 (P0+P1), present ≈ 10, partial ≈ 1, missing ≈ 29.**

---

## 6. my-nation / my-cities / my-generals / my-boss (MyController)

These are thin Kotlin-only convenience endpoints (no direct PHP page equivalent — PHP folds them into
메인/국가). They render correctly for what they carry, but inherit the same field poverty:
- **my-generals** (`MyGeneralSummary`): id/name/cityId/officerLevel/L/S/I/crew/npcState/mine — ❌ no injury/gold/rice/special/reservedCommand (same as §5).
- **my-cities** (`MyCitySummary`): id/name/level/region/pop/popMax/defense/wall — ❌ no agri/comm/secu/trust/trade/officer (same as §3).
- **my-nation-detail** (`FrontNationInfo`): id/name/color/level/gold/rice/tech/capital + cityCount/genCount — ❌ no type/bill/rate/topChiefs/limits (same as §4).
- **my-boss** (`MyBossResponse`): bossGeneralId/name/officerLevel only — PHP `b_myBossInfo.php` (567 lines) shows full boss general panel + nation strat/finance; ❌ ~95% missing.

**my-* totals: PHP-equivalent fields ≈ 30, present ≈ 14, missing ≈ 16** (mostly duplicates of §3-5; counted once in summary).

---

## 7. 연감 (PageHistory.vue / GetHistoryResponse vs web/game/app/game/history/page.tsx)

PHP `HistoryObj`: year/month, **map (MapResult)**, global_history, global_action, **nations[] (capital/cities/
color/gennum/level/name/power/type)**. Next `/history`:
- ✅ year/month selector (verbatim labels), 중원 정세 (global_history), 장수 동향 (global_action).
- ❌ **map** (the detail map render for that month — `MapViewer`/`SimpleMap`).
- ❌ **nations[] ranking panel** (`SimpleNationList`: per-nation capital/cities/power/gennum/level/color/type).

**연감 totals: PHP fields ≈ 12, present ≈ 5, partial ≈ 0, missing ≈ 7.**

---

## 8. 랭킹 (a_bestGeneral / a_kingdomList / a_npcList / a_hallOfFame / a_emperior vs rankings/*)

Next has the route skeletons (generals/best-generals/kingdoms/npcs/hall-of-fame/emperor/traffic).
The `rankings/generals` page renders headers 순위/장수/국가/직위/통솔/묠력/지력/경험/충성/병력 — same P0+ subset.
Not deep-audited per-column here (each is a thin ranking table off `RankingController`); the main gap is the
same **secret/derived columns** (벌점/숙련도/전투 통계) missing as in §5. Flagged as a follow-up audit slice.

---

## TYPO callout (load-bearing, parity-visible)
- `web/game/app/game/page.tsx` L92 and `rankings/generals` header L59 render **「묠력」** (mojibake) instead
  of **「무력」** for the strength stat label. PHP grand truth = 무력. This is a literal displayed-string
  parity break on the most visible stat.

---

## SUMMARY (dedup'd field counts across pages, excluding pure-mutation toggles)

| page | PHP fields | present | partial | missing |
|---|---|---|---|---|
| 메인 대시보드 | 78 | 26 | 4 | 48 |
| 내정보 (logs+settings state) | 40 | 4 | 0 | 36 |
| 도시 | 22 | 1 | 6 | 15 |
| 국가 | 26 | 7 | 1 | 18 |
| 장수일람 | 40 | 10 | 1 | 29 |
| 연감 | 12 | 5 | 0 | 7 |
| **TOTAL (deduped)** | **218** | **53** | **12** | **153** |

Biggest structural causes:
1. **`FrontInfoController` DTOs are skeletal** — `FrontGeneralInfo`/`FrontNationInfo`/`FrontGlobalInfo` carry
   ~30% of `GetFrontInfoResponse`. Even when `MyController` has richer data, the main page reads front-info.
2. **No permission tiering** on the general list → all P1/P2 fields (special/dex/war-stats/벌점/예약명령) absent.
3. **No now/max + bar rendering** — every gauge (city pop/agri/comm/secu/def/wall, nation pop/crew, general
   exp/level/dex) is shown as a flat current number, dropping max and the visual bar.
4. **Whole log/record sections** (개인 기록/전투 기록/전투 결과/장수 열전, 연감 map+nation ranking) have no Next surface.
