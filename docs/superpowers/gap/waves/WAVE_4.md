# WAVE 4 — read-page output parity (Next.js 렌더가 PHP/Vue grand truth의 필드를 실제로 그린다)

> **목표 (1줄):** WAVE 3가 채운 enriched read-DTO를 `web/game` read 페이지가 실제로 렌더하게 하고(general/nation 카드 + gauge bar + permission-tiered 장수일람), `web/game`에만 남은 누락 read surface(mojibake 라벨, 연감 map+nation-ranking, 내정보 log/record 섹션, standalone user_info, cached-map, 감찰부 battle center)를 채운다. PHP가 이긴다.

---

## 출처 (sources)

- 인벤토리: `docs/superpowers/gap/FE_OUTPUT_READ_GAP.md` (§1~§8, 218필드 중 153 missing), `docs/superpowers/gap/FE_STRUCTURE_GAP.md` (§3.2 감찰부 MISSING / cached-map PARTIAL / user_info PARTIAL, §3.3 PARTIAL read 페이지들)
- GAP_AUDIT 섹션: `docs/superpowers/GAP_AUDIT.md` §3 WAVE 4 (4a mojibake / 4b enriched 렌더 / 4c 누락 read surface), §2.4
- PHP grand truth: `legacy/devsam-core/hwe/func.php`(`generalInfo` L563, `generalInfo2` L762, `cityInfo` L153, `myNationInfo` L190), `legacy/devsam-core/hwe/b_myPage.php`(L218~L262 로그 4섹션), `legacy/devsam-core/hwe/func_history.php`(L136 `getGeneralActionLogRecent` / L176 `getBattleResultRecent` / L216 `getBattleDetailLogRecent` / L258 `getGeneralHistoryLogAll`), `legacy/devsam-core/hwe/ts/PageHistory.vue`(L21~L35 MapViewer+SimpleNationList), `legacy/devsam-core/hwe/ts/components/SimpleNationList.vue`, `legacy/devsam-core/hwe/ts/PageBattleCenter.vue`(L1~L60), `legacy/devsam-core/hwe/templates/mainCityInfo.php`
- **상위 의존:** WAVE 3 (read-DTO foundation) — 4b는 W3 DTO 필드를 소비한다. **본 스펙은 W3 미작성 상태를 전제로, W3 차단 항목과 W3 비차단 항목을 분리한다(아래 §완료/제외 + §병렬화).**

---

## 완료/제외 (이미 닫힌 부분 + 근거 file:line)

코드로 검증해 W4 범위에서 **제외**한다(추측 아님):

1. **연감 map+nation panel — 백엔드 준비 완료.** `HistoryController.kt:43-44`가 이미 `map = h.map`, `nations = h.nations`를 `yearbook_history` 행에서 내려준다. `web/game/lib/types.ts`의 `HistoryRecord`도 이미 `nations: unknown[]`, `map: unknown` 필드를 보유. **즉 4c 연감 패널은 W3 의존 없음** — 순수 FE 렌더 작업(`web/game/app/game/history/page.tsx`가 두 필드를 안 그릴 뿐). W4에서 즉시 닫는다.
2. **`GeneralBasicCard.tsx`는 「무력」을 이미 올바르게 렌더**(`web/game/components/game/GeneralBasicCard.tsx:42` = `label: '무력'`). mojibake `묠력`는 **메인 `page.tsx`의 인라인 카드(MyPageContent)**와 **`rankings/generals/page.tsx`에만** 존재 → 4a는 그 두 파일(+추가 발견분)로 한정.
3. **City now/max 필드 — DTO 준비 완료.** `FrontInfoController.kt:112-133`이 `FrontCityInfo`에 `populationMax/agricultureMax/commerceMax/securityMax/defenseMax/wallMax/trust`를 이미 내려준다. `/city`·메인 city 카드가 current만 그리는 게 문제 → now/max bar 렌더는 **W3 의존 없음**(단 `MyController`/`CityDetailController` DTO도 동일 필드 보유 여부는 4b 태스크에서 확인 후 진행).
4. **PR #26 founding seam, P6/P7 완료분은 read-output과 무관** — 본 웨이브와 disjoint, 건드리지 않음.

---

## W3 차단 vs 비차단 (핵심 게이팅 결정)

| 항목 | W3 차단? | 근거 |
|---|---|---|
| 4a mojibake | **비차단** | 문자열 리터럴 교체만, DTO 무관 |
| 4b-city now/max bar | **비차단** | `FrontCityInfo`가 이미 max 보유(`FrontInfoController.kt:120-130`) |
| 4c 연감 map+nation panel | **비차단** | `HistoryController.kt:43-44` 이미 내려줌 |
| 4c standalone user_info / cached-map | **비차단(부분)** | 기존 endpoint(`/api/my-page`, `/api/map/preview`) 소비로 라우트만 신설 |
| 4b general/nation enriched 카드 | **차단** | `FrontGeneralInfo`(`IdentityDto.kt:56`)에 train/atmos/picture/병종/items/내특·전특/성격/Lv/벌점/명성·계급/dex/전투통계 없음 → W3-3a가 채워야 함 |
| 4b permission-tiered 장수일람 (P1/P2) | **차단** | `GeneralRank`(`types/game.ts:171`)에 P1/P2 필드 없음 → W3-3b가 DTO+permission envelope 제공해야 함 |
| 4c 내정보 log/record 4섹션 + 감찰부 | **차단(별도, W3·엔진 양쪽)** | `general_record` 테이블이 마이그레이션에 부재(`infra/.../db/migration/`에 `yearbook_history`/`log_entry`만 존재, `general_record` 없음). PHP `func_history.php`는 `general_record(log_type in action/battleDetail/battleResult)`을 read. **테이블 + 엔진 produce + read endpoint + DTO가 전부 필요** → 본 웨이브에서 닫지 못함, **오픈 질문 OQ-1로 격리 + backlog**. |

---

## foundation-first 빌드 순서 (Tier-0 공유 확장점 먼저)

W4의 공유 아티팩트(creator → consumer 순서, 같은 파일 co-widen 금지):

- **Tier-0 (foundation, 먼저):**
  - **T0-A `GaugeBar` 공유 컴포넌트** (`web/game/components/game/GaugeBar.tsx`, 신규) — `(now, max, label)` → PHP `mainCityInfo.php` progress bar 패러티. 메인 city 카드·`/city`·국가 총주민/총병사·general exp/level이 모두 consume. **단일 신규 파일 → 누구도 co-widen 안 함.**
  - **T0-B `lib/types.ts` enriched-field 타입 확장** — W3가 추가할 `FrontGeneralInfo`/`FrontNationInfo`/`GeneralListItem(P1/P2)` 필드의 **TS mirror**를 한 번에 넓힌다(W3 DTO와 byte-mirror). consumer 카드/리스트가 동일 타입을 import. **단일 파일 = sequential creator, 절대 두 family가 동시 편집 금지.**
  - **T0-C `lib/format.ts` 라벨/색 헬퍼** (`formatInjury`/`formatRetireColor`/`officerLevelText`/`crewTypeName` 등) — PHP `func.php` `generalInfo()`의 색·라벨 규칙을 한 곳에. GeneralBasicCard·메인 카드·장수일람이 consume.
- **Tier-1 (consumer, 나중, 서로 disjoint):** 각 read 페이지가 T0 아티팩트를 import해 렌더만 한다. 페이지 파일은 서로 다르므로 병렬.

---

## 태스크 분해 표

게이트 표기: **FE 단위 = `web/game`의 vitest/RTL 컴포넌트 테스트 + `pnpm build`(타입체크) + `pnpm lint`**. PHP 골든 = N(읽기 출력은 RNG/로그 byte-parity 영역이 아니며, 로그-섹션 텍스트는 엔진이 이미 byte-match로 produce한 문자열을 그대로 v-html하므로 골든 재캡처 불필요).

| id | 변경 파일(disjoint) | 무엇을 (PHP 출처 file:line) | 게이트 (테스트 클래스 + 골든 Y/N) | 의존 |
|---|---|---|---|---|
| **T0-A** | `web/game/components/game/GaugeBar.tsx`(신규) | now/max progress bar — PHP `mainCityInfo.php` 도시 패널 bar(농업/상업/치안/수비/성벽/주민/민심 `x/x_max`). 비율 = `floor(now/max*100)` clamp[0,100]; 0/max→빈 bar. | `GaugeBar.test.tsx` · N | — |
| **T0-B** | `web/game/lib/types.ts` | W3 enriched 필드 TS mirror: `FrontGeneralInfo`에 picture/imageServer/experience/dedication/train/atmos/explevel/lbonus/crewtype/horse·weapon·book·item/specialDomestic·specialWar/personal/killturn/age/defenceTrain/refreshScore/honorText/dedLevelText/warnum·killnum·deathnum·killcrew·deathcrew·firenum/dex1~5; `FrontNationInfo`에 type/bill/rate/topChiefs/totpop·maxpop/totcrew·maxcrew/techCall·techLimit/strategicCmdLimit/surlimit/scout·war·secret 제한; `GeneralListItemP1/P2` + permission envelope. **W3 DTO와 정확히 일치(byte-mirror).** | `pnpm build`(tsc) · N | W3-3a/3b DTO 확정 |
| **T0-C** | `web/game/lib/format.ts` | `formatInjury`(0건강~위독 라벨+색), `formatRetireColor`(age), `officerLevelText`(현 `GeneralBasicCard.tsx:26` 인라인 → 추출·정본화), `crewTypeName`, `dexShort` — PHP `func.php` `generalInfo()` L563~ 색/라벨 규칙. | `format.test.ts` · N | — |
| **4a-1** | `web/game/app/game/page.tsx` | L92 `묠력`→`무력` (메인 인라인 general 카드, PHP `func.php` `generalInfo()` 무력 라벨) | `MainPage.test.tsx`(라벨 assert) · N | — |
| **4a-2** | `web/game/app/game/rankings/generals/page.tsx` | L59 헤더 `묠력`→`무력` **+ L90 sort 버튼 레이블 `묠력`→`무력`**(인벤토리 누락분, 본 스펙 발견) | `GeneralsRanking.test.tsx` · N | — |
| **4a-3** | (4a grep sweep) 그 외 `web/game/**/*.tsx`에 남은 `묠`/`묠력` 전수 grep 후 교체 | 메인+rankings 외 잔존분 제거(전수: `grep -rn 묠 web/game`) | `pnpm lint` + grep-clean(0건) · N | — |
| **4b-1** | `web/game/components/game/GeneralBasicCard.tsx` | enriched general 카드: T0-B 필드로 부상/훈련(+n)/사기(+n)/연령(은퇴색)/병종/명마·무기·서적·도구/내특·전특/성격/Lv+level bar(T0-A)/통솔보너스(+n)/벌점/picture 추가. generalInfo2 블록(명성·계급/전투·계략·사관/승률·승리·패배/살상률/dex1~5 bar) = **새 sub-section**. PHP `func.php` `generalInfo()` L563 + `generalInfo2()` L762 | `GeneralBasicCard.test.tsx`(enriched 필드 렌더 assert) · N | T0-A, T0-B, T0-C, W3-3a |
| **4b-2** | `web/game/app/game/page.tsx`(메인 카드 본문만; 4a-1과 같은 파일 → **4a-1 이후 sequential**) | 메인 general 카드를 인라인 stat-grid에서 `GeneralBasicCard` 소비로 전환 + nation 카드 enriched(type/bill/rate/topChiefs/총주민·총병사/techCall/제한 — T0-B) + city 카드 GaugeBar now/max(T0-A) | `MainPage.test.tsx` · N | 4b-1, T0-A, W3-3a |
| **4b-3** | `web/game/app/game/nation/page.tsx` | 국가 카드 enriched: 성향 type, 군주/참모(topChiefs), 총주민 totpop/maxpop + 총병사 totcrew/maxcrew(GaugeBar), 지급률 bill·세율 rate, techCall+techLimit 색, 전략/천도/모병/전쟁/첩보 제한, 국가 공지. PHP `func.php` `myNationInfo()` L190 | `NationPage.test.tsx` · N | T0-A, T0-B, W3-3a |
| **4b-4** | `web/game/app/game/city/page.tsx` | 도시 패널 패러티: 【지역\|등급】 header, 지배 국가 name+color(또는 공백지), 주민/농업/상업/치안/수비/성벽 GaugeBar now/max(T0-A), 민심 trust bar, 시세 trade%(또는 상인없음), 태수/군사/종사 officer names. PHP `mainCityInfo.php` + `cityInfo()` `func.php:153` | `CityPage.test.tsx` · N | T0-A, (officer names = W3-3c·3d) |
| **4b-5** | `web/game/app/game/rankings/generals/page.tsx`(4a-2와 같은 파일 → **4a-2 이후 sequential**) | permission-tiered 장수일람: P0+P1(P2 secret 조건부) 컬럼 — 명성/계급/관직/삭턴/벌점/Lv/성격/내특/전특/병종/dex + PHP 15-way sort selector. PHP `a_genList.php` + `GeneralListItemP0/P1/P2`(`ts/defs/API/General.ts`) | `GeneralsRanking.test.tsx` · N | T0-B, T0-C, W3-3b |
| **4c-1** | `web/game/app/game/history/page.tsx` | 연감에 **map**(MapViewer detail-map, `history.map`) + **nation-ranking panel**(SimpleNationList: capital/cities수/power/gennum/level/color/type). PHP `PageHistory.vue` L21-35 + `SimpleNationList.vue`. **백엔드 준비 완료**(`HistoryController.kt:43-44`). | `HistoryPage.test.tsx` · N | 4c-2(MapViewer prop) |
| **4c-2** | `web/game/components/game/MapViewer.tsx` | `isDetailMap`/`mapData`/`nations` prop 지원 추가 — 현재 자체 `/api/map/preview` fetch만 함(`MapViewer.tsx:78`). 연감이 월별 snapshot map을 prop으로 주입 가능하게(self-fetch는 prop 부재 시 기존 동작 유지). PHP `MapViewer.vue` is-detail-map | `MapViewer.test.tsx`(prop 주입 경로) · N | — |
| **4c-3** | `web/game/components/game/SimpleNationList.tsx`(신규) + `web/game/app/game/history/page.tsx`(4c-1과 같은 파일 → **4c-1 내부 통합**) | 신규 nation-ranking 테이블 컴포넌트(이름 chip + power/gennum/cities수 tooltip). PHP `SimpleNationList.vue` | `SimpleNationList.test.tsx` · N | — |
| **4c-4** | `web/game/app/game/user-info/page.tsx`(신규 라우트) | standalone user_info 페이지 — gateway `user_info.ts` 동등(내 정보 요약). 기존 `/api/my-page` 소비. PHP `hwe/ts/gateway/user_info.ts` | `UserInfoPage.test.tsx` · N | — |
| **4c-5** | `web/game/app/game/cached-map/page.tsx`(신규 라우트) | dedicated cached-map 페이지 — `PageCachedMap.vue` 동등. `MapViewer`(4c-2 prop) 임베드, `/api/map/preview` 소비. PHP `v_cachedMap.php`/`PageCachedMap.vue` | `CachedMapPage.test.tsx` · N | 4c-2 |
| **4c-6 (격리/backlog)** | — (스펙만, 미구현) | 내정보 log/record 4섹션(개인기록 action / 전투기록 battleDetail / 전투결과 battleResult / 장수열전 generalHistory, 각 24행 + "이전 로그 불러오기") + 감찰부(`PageBattleCenter.vue`). **`general_record` 테이블 부재**(마이그레이션에 `yearbook_history`/`log_entry`만). 테이블 마이그레이션 + 엔진 record produce(ChangeRecorder→JDBC) + read endpoint + DTO 필요 → **W4 범위 밖, OQ-1로 backlog.** | — · — | OQ-1 (W1/엔진 + W3) |

---

## 병렬화 그룹 (disjoint worktree family)

같은 파일을 두 family가 co-widen하면 merge conflict → 아래 family는 파일 disjoint하게 분할. 단일-파일 공유 아티팩트(`page.tsx`, `rankings/generals/page.tsx`, `history/page.tsx`, `lib/types.ts`)는 같은 family 안에서 creator→consumer **순차**.

- **Family F (foundation, 먼저 — 다른 모든 family의 선행):**
  - T0-A `GaugeBar.tsx`(신규), T0-C `lib/format.ts` — 즉시 병렬(서로 disjoint).
  - T0-B `lib/types.ts` — W3 DTO 확정 후. **단독 family, 단일 파일.**
- **Family A — mojibake + 메인/도시 카드** (`page.tsx`, `city/page.tsx`, `GeneralBasicCard.tsx`): 4a-1 → 4b-2(같은 `page.tsx` 순차), 4b-1, 4b-4. W3-3a 차단 부분(4b-1/4b-2 enriched) 외 4a-1·4b-4 도시 now/max는 **W3 무관 선행 가능**.
- **Family B — 장수일람** (`rankings/generals/page.tsx`): 4a-2 → 4b-5(같은 파일 순차). W3-3b 차단(4b-5).
- **Family C — 국가** (`nation/page.tsx`): 4b-3. W3-3a 차단.
- **Family D — 연감 + 지도 + 신규 라우트** (`history/page.tsx`, `MapViewer.tsx`, `SimpleNationList.tsx`신규, `user-info/`신규, `cached-map/`신규): 4c-2 → 4c-1/4c-3(history 통합), 4c-4, 4c-5. **W3 무관 — 가장 먼저 착수 가능**(연감 백엔드 준비 완료).
- **Family E — mojibake sweep** (4a-3): grep 잔존분, 다른 family와 파일 겹치면 그 family에 흡수.

**disjoint family 수 = 5** (F/A/B/C/D; E는 sweep으로 흡수). W3 비차단으로 즉시 병렬 가능한 family = D + A의 4a-1/4b-4 + B의 4a-2.

---

## 패러티 주의점

- **로그 byte-parity (해당, but 4c-6에 한정):** 4c 로그 섹션은 엔진이 이미 byte-match로 produce한 색/태그 마크업 문자열(`<Y1>【name】</>` 등)을 **그대로 `dangerouslySetInnerHTML`로 렌더**(현 `history/page.tsx:161`의 `globalHistory` v-html 패턴과 동일). FE가 문자열을 재가공하면 안 됨 — verbatim 출력. 4c-6은 본 웨이브 밖이지만 동일 규칙 적용.
- **라벨 byte-parity (4a/4b):** 표시 문자열은 PHP grand truth와 정확히 일치 — `무력`(NOT `묠력`), `통솔/지력/병종/내특/전특/명성/계급/숙련도` 등. T0-C 헬퍼에 정본화해 drift 방지.
- **rounding (4b city/nation gauge):** bar 비율 = PHP `floor(now/max*100)` 패턴 — `Math.floor`, NOT round. now>max 클램프 100, max=0 클램프 0(0-division 회피). PHP `mainCityInfo.php` bar 계산과 일치.
- **insertion-order (4c nation panel, 장수일람):** `history.nations`/장수 리스트는 서버가 준 배열 순서를 **재정렬 없이** 렌더(클라 sort는 PHP 15-way sort 키 선택 시에만, PHP 정렬 규칙 = stable). LinkedHashMap 의미 보존 — id로 re-key 금지.
- **flush/one-daemon-write (해당 없음):** W4는 전부 read-only 렌더. game-api는 JPA read만. 4c-6 backlog의 record produce는 엔진 측 ChangeRecorder→JDBC 경로(데몬 쓰기 규칙)로, FE가 아닌 W1/엔진 책임 — W4에서 write 경로 신설 금지.
- **재캡처 불필요(needsGolden=N):** 읽기 출력 패러티는 RNG draw 영역이 아님. 표시되는 로그 문자열은 엔진 골든이 이미 보증.

---

## 오픈 질문

- **OQ-1 (가장 큰 차단):** 내정보 log/record 4섹션 + 감찰부(`PageBattleCenter.vue`)는 `general_record` 테이블(log_type=action/battleDetail/battleResult + generalHistory)을 요구하나 **현 마이그레이션에 부재**(확인: `infra/src/main/resources/db/migration/`에 `yearbook_history`/`log_entry`만, `general_record` 없음). 이는 ① 테이블 마이그레이션(`Vnn__general_record.sql`), ② 엔진이 매 턴 record produce(`ChangeRecorder`→`JdbcFlushExecutor`, 데몬 쓰기 규칙), ③ read endpoint+DTO(W3급), ④ FE 4섹션+감찰부 — 4개 레이어 전부 필요. **W4 단일 read-output 웨이브로 닫을 수 없음.** → 별도 미니-웨이브(W1 엔진 + W3 DTO 결합)로 격리 제안, backlog 등재. (현 `4c-6`은 스펙-only.)
- **OQ-2:** T0-B `lib/types.ts` enriched 필드는 **W3-3a/3b의 DTO 필드명이 확정돼야** byte-mirror 가능. W3 spec 미작성 상태 → W4 4b 착수 전 W3 DTO 시그니처 동결 필요(creator→consumer 순서 강제). 임시로 W4는 W3 비차단 family(D, A의 도시/4a, B의 4a)부터 착수.
- **OQ-3:** 도시 패널의 officer names(태수/군사/종사)는 `FrontCityInfo`에 없음 → W3-3c/3d(ChiefCenter post holders / gauge now/max)가 도시 officer DTO를 제공하는지, 아니면 `CityDetailController`에 별도 추가인지. 4b-4의 officer-name 부분만 W3 차단.
- **OQ-4:** general portrait(picture/imgsvr)는 `MyController`(`MyController.kt:67`)가 이미 보유하나 메인은 `front-info`를 읽음. W3-3a가 `FrontGeneralInfo`에 picture를 추가할 때 imageServer prefix 규칙(opensam-images CDN)을 어디서 합성할지(서버 DTO vs FE format) — FE 렌더 4b-1에서 그릴 때 경로 합성 책임 위치 확정 필요.
- **OQ-5:** PHP 15-way sort(국가/통솔/무력/지력/명성/계급/관직/삭턴/벌점/Lv/성격/내특/전특/병종/병사)를 클라 정렬로 재현 시, 서버 정렬(stable)과 클라 정렬 stability를 어떻게 일치시킬지 — W3-3b가 서버 정렬 순서를 내려주면 클라는 토글만 할지, 클라가 전부 정렬할지. PHP는 서버 정렬 → 클라 재정렬 시 stable-sort 비교자 동일성 보장 필요(CLAUDE.md insertion-order 규칙).
