# 省 분할을 지리 우선으로 다시 세운다 — spec

- 상태: DRAFT r2 — 독립 비평 1차(fix-required 8·should-fix 6) 반영
- 이슈: GH #806 / OPENSAM-278 · 선행: PR #804(ADR-LITE-058) · 관련: #777(縣 경제 입력), ADR-LITE-051·052
- 사용자 결정(2026-09-17): ① 넓이 균형을 포기하고 지리 우선 ② 큰 縣은 그 縣 안에서 省으로 다시 쪼갠다 ③ 판정 단계는 「뜻」만 옮기고 새로 쌓는다 — 비평 뒤 기계는 「사슬 중간에 새 단계를 끼우고 뒤 단계를 다시 굽는다」로 구체화(§4), 결과는 같다

## 1. 문제 (실측, main `57b66bbb`, han-tiles 1133)

`rebalance_province_areas` / `adapt_historical_city_seeds` 는 郡 안을 같은 넓이 덩어리로 나눈 뒤(`balanced_parent_labels`, 용량 가중 보로노이) 縣을 병목 매칭으로 덩어리에 짝짓고, 城 씨앗(`cities[].col,row`)을 그 덩어리로 옮긴다. `lon/lat` 은 그대로 남는다.

| 측정 | 값 |
|---|---|
| 城 실제 칸이 제 관할 안 | 651 / 1,131 (밖 480: COUNTY 464·STRATEGIC_SITE 10·EXTERNAL 6) |
| 씨앗 vs 경위도 투영 거리 p50/p90/p99 | 1.7 / 12.8 / 39.2 칸 (1칸 ≈ 5.2 km) |
| 8칸 초과 / 15칸 초과 | 228 (郡治 23) / 92 (郡治 10) |
| 최댓값 | 弋居 51.8 · 合浦 44.3 · 南昌 40.5 · 豫章郡 5縣 39–42 |
| 城 실제 칸이 제 郡(`parentOwner`) 안 | 1,106 / 1,131 (밖 25 중 7은 물·비소유 칸) |

결과: 鄴·安邑·始平·元氏가 실제 산지 위에 서 있고(#798 뒤에도 PLAIN+BASIN 0칸), 지형·표고·하천을 읽는 모든 縣 입력(#777)이 남의 땅을 읽는다. ADR-LITE-051 이 이미 「래스터 분할 자체가 성기다, 프로빈스 이설은 후속」이라고 적었다.

郡 기하는 이미 지리적이다. **다시 자를 것은 郡 안쪽 縣 경계뿐이다.**

## 2. 목표와 비목표

목표
- G1. 城이 있는 모든 縣의 실제 경위도 칸이 제 관할 안에 있다(예외는 원장 행에 사유 클래스와 함께 — §3 규칙 1).
- G2. 縣 관할 경계는 郡 안에서 「가장 가까운 縣의 실제 위치」로 정한다. 郡 경계(`parentOwner`)와 郡·縣·城 식별자는 바꾸지 않는다.
- G3. 省은 이동·점령 단위로 남는다. 상한을 넘는 縣은 제 관할 안에서 城 없는 省으로 쪼갠다. ADR-LITE-052 R1(소유가 바뀌면 그 縣의 省 전체가 함께 넘어간다)·R2(무소속 육지 0, RIVER 는 육지)를 지킨다. R1 은 연속성 규칙이 아니다 — 寄治 富平 같은 승인된 월경 관할(ADR-056)은 그대로다.
- G4. 고증 판정(郡 소속 판정·변경 51縣 위치·거점 73 좌표·접기 결정·동명이지)은 입력으로 보존한다.
- G5. 결정론: 같은 입력이면 바이트 동일. 배열 순서·dict 순서에 의존하지 않는다.

비목표
- 郡 경계 재작도, 城 추가·삭제, 시나리오 세력 배치 정책 변경, 지형 클래스 변경(#798 단계는 그대로 맨 위에 다시 얹는다).
- 넓이 균형(§6 끝 문단).

## 3. 분할 규칙

입력은 전부 커밋물이다: `han-tiles.json`(parentOwner·terrain·cities lon/lat·행 표), 판정 원장들. gitignored 원본(terrain-grid·han-places)은 쓰지 않는다 — 선례 `rebalance_han_tiles.py` 와 같은 조건.

0. **단위는 관할(jurisdiction)이다.** 분할기는 이 단계 입력 문서의 관할 1,072개(거점 분할·접기 전)를 자른다. 거점 73은 지금처럼 기증 縣에서 떼어 내고(carve), 외부 취락은 縣과 같은 규칙이다.
1. **씨앗 = 실제 위치.** 관할의 씨앗 칸은 seat 城의 `lon/lat` 투영 칸이다. 그 칸이 제 郡 마스크 밖이거나 비소유 칸이면 제 郡 육지에서 가장 가까운 칸으로 옮기고 사유 클래스와 이동 거리를 원장에 적는다. 실측 25곳의 사유는 셋이다 — (a) 물·격자 밖 7, (b) **사료 소속 郡 ≠ 220년 래스터 郡** 18(歷城↔高唐, 巨鹿·平鄉→廣平, 溫→河內 등; `county-misbinding-rebindings-v1` 의 `commanderyCorrections`·`commanderyCorrectionsNotChanged` 가 사료로 판정해 둔 것이 입력이다), (c) 씨앗 충돌(규칙 2).
1b. **대리 治所 관할.** `JURISDICTION-PARENT-xxxx-SEAT` 17곳(省 47·8,686칸, 朔方·西河·定襄 등)은 城이 없어 lon/lat 이 없다(프로토타입 실측: 씨앗 없는 관할 9 → 미배정 7,672칸). 씨앗은 그 관할 `seatPlaceId` 省의 현행 治所 점 칸을 그대로 쓴다(지어내지 않는다). 그 郡에 다른 縣이 없으면 郡 전체가 그 관할이고 규칙 5로만 쪼갠다. 런타임의 대리 治所 판정(`build_han_world.stand_in_seat_provinces`, `HanStrategicTopologyJson.standInSeatProvinces`, `HistoricalBattlefieldCatalog` 의 `startsWith("DIRECT-")`)이 「DIRECT 접두어 = 대리 治所 가능」에 기대므로, 새 城 없는 省 id 는 접두어를 가른다(규칙 6).
2. **같은 칸 충돌.** 실측 14칸·28城이 칸 좌표를 공유한다. 같은 칸을 공유하는 縣들은 (a) 같은 실체면 기존 판정을 따르고 — 출처는 `han-place-duplicate/merge-adjudications` 와 **`cityless-jurisdiction-fold-decisions-v1`**(杜/杜陵·鄄良/鄄城·益都/益·㡉縣/㡉侯國·贊/陰 5쌍은 여기에만 있다. 접기는 뒤 단계지만 그 판정은 이 단계의 입력이다: 접힐 관할은 씨앗을 받지 않고 대상 관할 영역에 든다), (b) 다른 縣이면 id 순으로 가장 가까운 빈 육지 칸에 하나씩 놓는다. 어느 쪽인지는 새 원장 `county-seed-collisions-v1` 에 행으로 적는다 — 자동 추정 금지.
3. **縣 영역.** 郡 마스크(`parentOwner == 郡` 이고 `owner >= 0`)의 각 칸은 씨앗까지의 거리가 가장 짧은 관할에 간다. **RIVER 는 소유된 육지다(3,421칸, ADR-052 R2) — 통과 가능하고 배정 대상이다.** 못 걷는 것은 마스크 밖(SEA·LAKE·OUT_OF_SCOPE·남의 郡)뿐이다. 거리는 마스크 안 **8-이웃 √2 가중 최단경로**(정수 비용 10/14 다익스트라)다 — 4-이웃은 맨해튼 거리라 경계가 45° 계단이 되고 대각 방향 縣이 √2배 불리하다(프로토타입 aspect 5 초과 10縣). 유클리드가 아닌 이유는 물 건너 조각을 만들지 않기 위해서다. 동률은 관할 id 사전순.
   郡 마스크는 연결이 아니다: RIVER 를 통과시켜도 42郡이 2성분 이상(최대 15), 씨앗 없는 성분이 59개(9,758칸; 朔方 4,891·西河 1,181·龜茲 679·邪馬壹 611+480·定襄 561 — 대부분 규칙 1b 가 씨앗을 주면 사라진다, P1 에서 재측정). 그래도 씨앗이 없는 성분은 같은 郡 관할 중 유클리드로 가장 가까운 관할에 가고, 그 관할은 **구조적으로 다성분**이 된다. 이것은 결함이 아니라 郡 래스터의 사실이므로 새 원장 `county-region-components-v1` 에 (관할, 성분, 칸 수, 사유 WATER_SEPARATED/PARENT_MASK_SPLIT)로 적고 Q3 의 예외로 삼는다. **조각 판정 단계는 폐기가 아니라 이 원장으로 재발행된다.**
4. **최소 넓이.** 縣 영역이 `min_area`(현행 8) 미만이면 이웃 縣에서 가장 가까운 칸을 빌려 채운다(현행 규칙 유지, 빌린 칸 수를 원장에 적는다). 실측 대상 ≤8칸 29곳.
5. **省 재분할.** 縣 영역이 `max_area`(현행 620, 실측 대상 66–68縣)를 넘으면 `n = ceil(area / max_area)` 개로 나누고, 근사 균형 분할이라 어느 조각이 상한을 넘으면 `n += 1` 로 다시 나눈다(상한을 만족할 때까지). 연결 성분이 여럿인 縣은 성분마다 따로 센다. 주의(사용자 확인 대상): 변경 광역의 省이 거칠어진다 — 居延 28省 → 약 11省. 城이 든 조각이 seat 省이고 나머지는 城 없는 省이다. 나누는 방법은 기존 `balanced_parent_labels`(연결성 보장)를 縣 마스크에 그대로 쓴다 — 균형 분할이 쓰이는 곳은 여기뿐이다. 城 없는 省의 `jurisdictionId` 는 그 縣이다(`assignmentBasis: WITHIN_COUNTY_SUBDIVISION`). 예상 省 수는 프로토타입에서 1,204(城 씨앗만)–1,271(씨앗 없는 관할 제외 전체)이고, 규칙 1b·거점 73을 넣어 P1 에서 다시 센다. 이 수치가 P1 중단 조건의 기준이다.
6. **省 id.** seat 省 id = 縣 id(현행과 같다). 縣 안 재분할로 생긴 城 없는 省 id = `SUB-{관할id}-{sha256(관할id + ':geo:' + ordinal)[:12]}`. **`DIRECT-` 접두어는 대리 治所 관할의 省에만 남긴다**(런타임 판정 의미 보존, 규칙 1b). 옛 `DIRECT-{郡id}-…` id 는 대부분 사라진다. `han-province-id-registry.tsv` 는 헤더뿐이고 읽는 코드가 없으므로 「은퇴 행」 기계는 실재하지 않는다 — 은퇴 id 목록은 이 단계 원장에 (옛 id → 새 관할) 표로 남기고 새 레지스트리는 만들지 않는다. 省 **인덱스**를 핀하는 `infra/.../map/han.json` runtime-province-identity(bound 751/unresolved 23)는 전량 재생성 대상이다(§5).
7. `cities[].col,row` 는 seat 省 안의, 실제 위치에 가장 가까운 칸이다(대부분 실제 위치 그 자체). `city-seed-reseats-v1`·`web/shared/src/iso/citySeedReseat.ts` 의 재배치는 필요가 없어지는지 다시 잰다.

## 4. 판정 단계를 다시 쌓는 법 (사용자 결정 ③)

**바탕 문서와 사슬(비평 F4).** 단계들은 독립 모듈이 아니라 `input/outputDocumentSha256`·칸 델타로 앞 문서를 바이트 그대로 복원하는 사슬이고, 감사 도구 3개(`audit_territory_disconnections`·`adjudicate_han_province_fragments`·`build_han_parent_reconciliation`)가 그 사슬을 타고 투영한다. 커밋된 han-tiles 에는 단계 결과(frontier 51城·平陰 leaveBehind·재결속 8縣의 교정 lon/lat·五原郡이 받은 parentOwner)가 이미 구워져 있다. 그래서 「뿌리부터 새로」가 아니라 **사슬 중간에 새 단계를 끼운다**:

```
base → 조각 판정 → 劇 이전 → 오배정 재결속 → 변경 51縣      (그대로 — 사슬·지문·판정 보존)
     → ★ 지리 재분할 partition_counties_by_location           (새 단계: owner·省 행·cities col/row·adjacency 교체)
     → 거점 73 분할 → 접기 → 저지 지형                          (새 바탕 위에 --prepare 로 다시 굽는다)
```

앞 네 단계의 roster·lon/lat·parentOwner 효과는 입력으로 살아남고, 그 칸 델타는 ★ 가 덮어쓴다(「뜻만 옮긴다」). ★ 는 다른 단계와 같은 계약을 갖는다: 결정 원장 + 산출 원장(`geometry.stages` 에 입력·출력 지문, 되돌리기는 **입력 owner·省 행 전체를 원장 옆 gz blob 으로 핀**), `peel()/reapply()/--check`, CI 단계. carve.peel 뒤에 ★.peel 이 오도록 앞 단계 도구·감사 도구의 peel 순서를 고친다(PR #804 에서 저지 단계를 끼운 것과 같은 방식, 지문은 배열 순서에 무관하게 — 그 CI 교훈). 영토 단절 감사의 투영 기계는 ★ 를 경계로 다시 짠다: ★ 앞 행은 옛 문서에 대해 그대로 성립해야 하고, ★ 뒤는 새 행이다.

| 현행 단계 | 「뜻」(보존할 입력) | 새 판에서 |
|---|---|---|
| jurisdiction parent adjudications·temporal·merge·duplicate | 어느 縣이 어느 郡인가, 같은 실체인가 | 입력 그대로. 분할 전에 적용 |
| 변경 51縣 (`frontier-counties-v1`) | 縣 목록·사료 위치 | 단계는 사슬에 그대로 남는다(8郡을 `balanced_parent_labels` 로 스스로 재분할하지만 ★ 가 덮어쓴다). CI `--check` 유지 |
| 오배정 재결속 (`county-misbinding-rebindings-v1` + `-adjudications-v1`) | rebindings 원장이 사료 판정의 **유일한 보관처**다: 8縣 `correctedPhysicalPlace` 좌표·인용, `leaveBehind`(平陰), `supersedesJurisdictionSeatRecovery`(南鄉郡), `hostParentRegionId`(五原郡이 南匈奴 땅을 받음 — G2/Q2 의 승인된 예외), `commanderyCorrections` 4 + `NotChanged` 8 | 단계는 사슬에 그대로 남는다. ★ 는 그 결과(교정 lon/lat·parentOwner)를 읽고, `commanderyCorrections*` 는 규칙 1(b) 예외의 입력이다 |
| 劇 이전 (`province-relocations-v1`) | 劇의 사료 좌표 | 단계는 사슬에 남는다. ★ 는 교정된 lon/lat 을 읽는다 |
| 조각 판정 (`province-fragment-adjudications-v1`) | 균형 분할의 부작용 땜 | 단계는 사슬에 남는다. 새 분할의 다성분은 `county-region-components-v1` 로 재발행(규칙 3) |
| 거점 73 분할 (`strategic-strongholds/passes-v1`) | 거점 좌표·근거 | 새 바탕에 `carve --prepare`. 「입력 그대로」가 아니다 — strongholds 행의 `tileAnchor{provinceIndex, provinceId, terrain}`(DIRECT id 6건)은 파생 필드라 재생성하고 그 원장 sha 가 carves `inputs` 에 핀돼 있다. 縣 중앙값이 171 → 약 66칸으로 줄어 `DONOR_TOO_SMALL` 이 늘 수 있다(거점 73 = 省 73 보장, P1.5 에서 센다). carve 의 `minimumFootprintCells: 4` 는 Q4 의 예외 클래스로 명시 |
| 시나리오 큐레이션의 DIRECT id | 「北方 5郡 유리」 UNALLOWLISTED_HOLE 7행(`scenario-province-claims-v1`·`tools/scenario/han_ownership.json`, `DIRECT-PARENT-0138-877c5fc0e884`) | 손으로 새 省을 다시 지목(근거는 그대로) |
| 郡 보급선의 SEA_ROUTE 13줄 | 사료 근거 뱃길(ADR-056, `CommanderySupplyLinkTest` 13줄 고정) | 은퇴 대상이 아니다. `from/toProvinceIndex` 만 새 인덱스로 |
| 접기 결정의 `territoryAdjudications` 4행 | verdict·근거 | componentKey·cellCount 재측정 후 재심사 |
| 접기 (`cityless-jurisdiction-fold-decisions-v1`) | 11 관할 접기·省 이관·郡 이동 | 입력 그대로, 새 바탕에 `--prepare`. 西安平 省 1304 이관은 「압록강 하구 省」을 새 id 로 다시 지목 |
| 저지 지형 (#798) | 저지 원장 | terrain 만 바꾸므로 그대로 다시 얹는다 |
| 영토 단절 원장 119행 | verdict·근거 | componentKey·cellCount 가 전부 바뀐다 → **재심사**. 옛 행의 근거는 같은 단위의 새 행으로 옮기되 자동 승계 금지(검토 표시) |
| 보급 단절 v3·郡 보급선 74개 | — | ADR-LITE-051 의 보급선은 성긴 래스터의 땜이다. 새 분할에서 절단을 다시 재고(기준선: scenario_1020 절단 102) 필요한 것만 남긴다 |

폐기되는 원장은 지우지 않고 `supersededBy` 를 달아 남긴다(근거 보존, ADR 추적).

## 5. 파급과 릴리스

- owner 가 전면 교체된다 → `adjacency.county/commandery`·`seatOwner`?(郡 수준, 실측 후 판정)·`_meta.counts`·수계 위상 핀·행정 감사·省→城 귀속 1,594행·시나리오 省 소유(15개)·`han-world-v3.json` `connections[]`·Kotlin 상수·1133 번들.
- **城 id 집합은 그대로다** → 새 릴리스 식별자는 불가능(리졸버가 city id 집합으로 고른다) → `han-world-v3-1133` 제자리 재핀, **월드 리셋 필수**(ADR-LITE-054·058 과 같은 대가). 省 id 가 바뀌므로 `province_control` 등 영속 pin 은 마이그레이션이 아니라 리셋이다.
- `connections[]` 가 바뀌면 경로·보급·AI 회귀 테스트의 고정 기대값이 움직인다. frozen-baseline 규칙(CLAUDE.md 5): 테스트를 지우지 않고, 바뀐 기대값마다 의도·근거를 적는다.
- han-tiles 통파일 해시 핀 8곳(`HanStrategicTopologyJsonTest.kt`·`test_han_tiles_contract.py`·시나리오 省 소유·1133 catalog·省→城 귀속·행정 감사·수계 원장·부모 재조정) + `Han1133Artifacts.CATALOG_SHA256`.
- **省 수·간선 수를 박은 코드**: main 코드 `HanStrategicTopologyJson.kt:56` `landCountByRoster`(`1133 to 1594`); 테스트 `HanWorldArtifactsResolverTest`·`HanStrategicTopologyJsonTest`(특정 DIRECT id 2건 포함)·`MapAdministrativeOwnershipTest`·`HanSpatialSupplyProviderTest`·`HanStrategicSupplyProviderTest`(간선 4275)·`provinceMap.test.ts`·`test_province_city_attribution`·`test_province_ownership_materializer`·`test_materialize_province_jurisdictions`·`test_adjudicate_han_province_fragments`·`test_audit_han_water_topology`·`test_han_tiles_contract`. 전부 의도 변경으로 고치고 근거를 적는다(frozen-baseline 규칙).
- 인덱스·id 키 원장: `han-commandery-supply-links-v1`(74행), `administrative-topology-audit-v1`(DIRECT 33종), `han-world-v3-battlefields.json`(provinceId ↔ ingressCityId), `infra/.../map/han.json` runtime-province-identity.
- 웹: ADR-056 의 `cityIconInsideProvince.test`(「밀기 끄면 489곳 빨강」)는 새 분할에서 밀기가 거의 무동작이 되어 적색 프로브가 이빨을 잃는다 — 프로브를 새 실측으로 다시 세운다. `docker/game-api.Dockerfile` 은 같은 han-tiles 를 `han`·`han-world-v2`·`han-world-v3` 세 맵 코드로 굽는다 → v2 계열 표시도 같이 바뀐다(범위에 포함).
- **1133 README 개정.** README 는 「경계가 바뀌면 제자리 재생성하지 말고 새 식별자를 등록하라」「省 1,594·인덱스 불변」이라고 적는다. ADR-058 재핀은 정체성 불변이 전제였다. 이번은 省 정체성이 바뀌는 첫 제자리 재핀이므로 ADR-LITE-059 가 그 문언을 명시적으로 개정한다(리졸버가 city id 집합으로 고르므로 새 식별자는 여전히 불가능).
- 재생성 순서는 메모(핀 사슬): 수계 원장 핀 → 재조정·감사·귀속·소유 → 월드 매니페스트 → 테스트 핀 → 번들.
- `build_han_world.py` 는 gitignored `junguozhi.json` 이 필요하다(메인 체크아웃에 있다). 없으면 중단하고 UNKNOWN 으로 적는다.

## 6. 게이트 (먼저 빨개지는 것을 보고 단다)

| 게이트 | 내용 | 적색 프로브 |
|---|---|---|
| Q1 제자리 | 城 있는 縣의 실제 칸 ∈ 제 관할. 예외는 원장 행뿐이고 클래스는 셋(물·격자 밖 / 사료 소속 郡 ≠ 220 래스터 郡 / 씨앗 충돌) | 씨앗 하나를 10칸 옮긴 문서 |
| Q2 郡 불변 | ★ 단계 입력과 출력의 `parentOwner` 가 같다(★ 는 郡을 안 건드린다) | 郡 칸 1개 변조 |
| Q3 덮개 | `assert_no_orphan_land`, 省당 연결 성분 1 — 예외는 `county-region-components-v1` 행(WATER_SEPARATED·PARENT_MASK_SPLIT) | 기존 + 성분 1개를 떼어 낸 문서 |
| Q4 넓이 | `min_area` 8 ≤ 省 ≤ `max_area` 620. **새로 배선하는 게이트다** — `validate_province_quality` 는 gitignored 입력 경로(`build_terrain_grid`)에서만 돌아 커밋본에는 넓이 게이트가 없다. 현행 커밋본이 이미 위반 11건(8칸 미만 4: ss-mengjin 5·ss-fancheng 7·DIRECT 2, 620 초과 7: frontier 縣, 최대 上殷台 1,568). 예외 클래스: carve footprint(`minimumFootprintCells: 4`)·원장 행 | P0 에서 현행 데이터의 빨강 11건을 먼저 본다 |
| Q5 결정론 | 두 번 돌려 바이트 동일 + cities[] 뒤섞은 입력에서 동일(PR #804 CI 교훈) | 순서 의존 주입 |
| Q6 그래프 | `validate_semantic_outputs`(최대 성분 ≥ 85 %, 고립 < 120 …) + 城 그래프 전수 도달(ADR-LITE-054) | 기존 |
| Q7 locality | `test_han_tiles_owner_locality` 의 50행 기준선(40칸 초과 소유)을 **새 실측으로 재기준** — 줄어드는 것이 목표다 | 기존(늘면 적색) |

`min/max_parent_median_ratio`(郡 중앙값 대비 넓이)는 결정 ①과 모순이지만 커밋본에는 애초에 걸려 있지 않다 — 생성기 경로의 정책값이라 이 작업은 건드리지 않고 ADR 에 「커밋본 게이트 아님」만 적는다. Q6 의 城 그래프 전수 도달은 `build_han_world.py` 안에 있고 gitignored `junguozhi.json` 이 필요해 CI 에서는 warning 뿐이다 — 로컬 증거로 남긴다.

수치는 전부 현행 값 또는 실측이다. 새 임계를 짓지 않는다.

## 7. 수용 기준

- A1. Q1: 651/1,131 → 예외 원장 행을 뺀 전부. 鄴·安邑·始平·元氏·南昌이 제자리.
- A2. 씨앗 vs 경위도 거리 p90 12.8칸 → 1칸 이하(예외 행 제외).
- A3. #777 입력 재측정: PLAIN+BASIN 0칸 관할 169 → 실측 보고(목표 수치 없음, 남는 것은 실제 산지·고원·사막과 #798 원장 밖 저지).
- A4. scenario_1020 보급 절단 102(ADR-051 기준선) → 실측 보고, 郡 보급선 74개 중 불필요해진 것 은퇴.
- A5. CI 5/5, 백엔드 게이트, tools/map·scenario 스위트. 1133 재핀 + 운영 문서 리셋 절차.
- A6. 독립 교차 비평 cleared.

## 8. 단계 (구현 계획은 별도 plan 문서)

- P0 계측·게이트: Q1·Q7 측정 도구와 적색 프로브(현행 데이터에서 빨간 것을 확인), 충돌 원장 초안.
- P1 분할기: `tools/map/partition_counties_by_location.py`(규칙 1–7) + 단위 테스트. 아직 han-tiles 를 안 쓴다 — 산출물을 scratch 에 내고 Q1–Q6 실측 보고.
- **P1.5 건식 실행(중단점).** scratch 의 ★ 산출 위에서 carve·fold·lowland `--prepare`, 영토 단절 감사·조각 판정·부모 재조정·귀속·소유·보급선·`build_han_world` 를 돌려 **죽는 도구, 새로 필요한 지문, 재심사 행 수, DONOR_TOO_SMALL 수, 바뀌는 connections 수를 전수로 센다.** 여기서 사용자 확인을 받는다. 이 프로젝트가 중간에 실패한다면 가장 유력한 원인은 P2 에서 지문 사슬이 첫 단계에서 끊겨 CI 게이트 5–8개가 동시에 빨개지고 되돌릴 중간 지점이 없어지는 것이다.
- P2 ★ 단계 커밋 + peel 순서 배선 + 뒤 단계 재적층. 단계마다 `--check` 가 초록이어야 다음으로 간다(중단 조건: 한 단계라도 `--check` 를 못 세우면 그 단계에서 멈추고 보고).
- P3 영토 단절 재심사, 보급 재측정, locality 재기준.
- P4 사슬·월드·시나리오·번들 재생성, Kotlin/웹 회귀.
- P5 문서(ADR-LITE-059, README 1133, 운영 리셋, roadmap), 비평, PR.

P1 끝에서 실측이 §1 의 타당성 수치와 크게 다르면(예: ≤8칸 縣이 29가 아니라 수백) 멈추고 사용자에게 돌아간다.

## 9. 열린 질문

- 규칙 3 의 거리: 기하 최단경로(채택) vs 통행비용(`LAND_COST`). 원 생성기는 통행비용으로 郡 경계를 잘랐다. 縣 경계에도 쓰면 능선·강을 따르지만 지형 오분류(#798 원장 밖)가 경계에 새겨진다. 후속 개선 후보로만 남긴다.
- `seatOwner`(郡 수준 legacy 격자)가 새 분할에서 달라져야 하는가 — legacy 780 경로 노드가 읽는다.
- 변경 광역의 省 입도(居延 28 → 약 11省)가 의도에 맞는가 — P1.5 보고에서 사용자 확인.
- ADR-051 의 「31省 이설로 절단 102 → 90」 실측은 A4 의 기대치를 낮춰 잡을 근거다. 보급선은 대부분 남을 수 있다.
