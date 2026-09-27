# 결손 縣 재심사 · 합성 좌표 교체 · 簡體 읽기 수정 계획 (2026-09-27)

근거 원장: `data/curated/han/gap-county-source-recheck-v1.json` (생성기가 읽지 않는다).
출발점: PR #984 의 `data/curated/han/map-design/source-fix-candidates-v1.json`.

## 0. 이번 PR 에 든 것 (han-tiles 무변경)

- `tools/map/append_all_gap_counties.py`: 簡體 원문을 그대로 hanja 에 넣던 읽기를 `reading_form()` /
  `korean_reading()` 으로 바꿨다. 새 행과 `--check-readings` / `--refresh-readings` 가 같은 규칙을 쓴다.
- `tools/map/tests/test_append_all_gap_counties_readings.py`: 읽기 형태 단위 검사 + 원장의 남은 오독 13행을
  `PENDING_*` 로 고정(재생성 전까지 초록, 재생성하면 목록을 지워야 초록). 적색 프로브: 글자표에서 `广` 을 빼면 2건 적색.
- 판정 원장과 이 계획.

`gap-counties-v1.json`·`han-tiles.json`·월드·번들은 **바꾸지 않았다.** 아래 1–4 단계는 사용자 승인 뒤에 한다.

## 0.5 판정 요약

**동명 6건**(main 원장의 IN_OTHER_COMMANDERY 는 26건이다. #984 원장의 「27건」은 main 과 다르다)

| 郡國志 | 이름 대조가 문 것 | 판정 | 근거 요지 |
|---|---|---|---|
| 中山國 漢昌 | 巴郡·漢昌郡 漢昌 (1,038 km) | 허위 부재 — 지도 44996 魏昌县 | 讀史 卷014 「章帝…改曰漢昌。魏主丕改曰魏昌」, TGAZ 漢昌 76–219·魏昌 220– 같은 점 |
| 南陽郡 酇 | 沛國 酇 (448 km) | 허위 부재 — 지도 45748 赞县 | 晉書 順陽郡 「酂」, TGAZ 酂 139–216·贊 220–222 같은 점, 0.0 km |
| 山陽郡 髙平 | 安定郡 高平 (947 km) | 실결손, 220 존속 | 晉書 高平國 「高平」, TGAZ hvd_45198 ↔ 兩城鎮 1.9 km |
| 鴈門郡 卤城 | 漢陽郡 鹵城 거점 (924 km) | 실결손, 존속 미확인 | 漢書 代郡 「鹵城」, 讀史 卷040, 晉書 雁門郡 부재, TGAZ 23–214 ↔ 大營鎮 1.1 km |
| 上郡 候官 | 張掖屬國 候官 | 실결손, 좌표 UNKNOWN | 校勘記 「候官…皆官名，非城名也」(張掖屬國) |
| 南陽郡 成都 | 蜀郡 成都 | 실결손, 좌표 UNKNOWN | 漢書·晉書 남양 쪽에 없음 |

**합성 좌표 교체 4곳**(#984 의 4곳에서 葭萌 을 빼고 太丘 를 넣었다)

| 縣 | 좌표(lon, lat) | 가리키는 것 | 교차 | 현재 합성 칸과 |
|---|---|---|---|---|
| 漁陽 泉州 | 117.05500, 39.34528 | 黃莊街道 중심(城上村) | 나무위키 4.5 km | 229 km |
| 廣漢屬國 阴平道 | 104.67775, 32.94432 | 文縣 縣 좌표 | 讀史 卷002 「陰平，今陝西文縣也」 | 233 km |
| 陳國 寧平 | 115.32525, 33.66413 | 寧平鎮 중심 | CHGIS 0.1 km | 52 km |
| 沛國 太丘 | 116.28903, 34.11170 | CHGIS 漢 太丘縣 점 | GeoNames 0.5 km | 46 km |

葭萌 은 옮길 것이 아니라 중복이다(蜀漢 이 漢壽 로 고침, 지도 44587). 廣漢縣(广汉)은 沱牌鎮(옛 柳樹鎮) 후보와
讀史 卷071 「射洪縣東南百里」 가 맞는지 미검증이라 보류했다.

**합성 城 중복 23곳** — 기존 판정 원장이 이미 「지도에 다른 표기로 있다」고 한 16곳이 2026-09-23 일괄 배치 때
다시 城으로 들어갔고, 이번에 7곳(南〈糸言糸〉=南䜌, 葭萌=漢壽, 平陵=始平, 沙=涉, 脩=蓚, 山茌=茬, 充國=西充國·南充國 분할)을
더 찾았다. 방법: TGAZ 漢代 점에서 1 km 안에 있는 지도 城 중 郡國志 어느 행에도 쓰이지 않은 城.
이 방법은 TGAZ 레코드가 있는 행만 덮으므로 23은 하한이다.

## 1. 사용자 결정이 필요한 것

| # | 결정 | 추천 | 영향 |
|---|---|---|---|
| D1 | 합성 城 23곳이 기존 城과 같은 縣이다(허위 부재·개명·이체자). 합성 城을 은퇴시키고 기존 城에 郡國志 행을 붙일 것인가 | 은퇴 | 城 23개 감소(D2 와 합치면 1,447 − 23 + 4). 새 릴리스 판·시나리오·경로 노드·리셋 |
| D2 | 동명 6건 중 실결손 4건(山陽 高平·鴈門 鹵城·上郡 候官·南陽 成都)을 城으로 넣을 것인가 | 2026-09-23 방침대로 넣는다. 高平·鹵城은 출처 좌표, 候官·成都는 합성 칸 | 城 수 +4 |
| D3 | 葭萌 은 #984 의 「昭化로 이동」 대신 D1 의 중복(漢壽 44587)으로 처리 | D1 과 함께 | #984 원장 정정 |

D1·D2 는 城 id·수를 바꾸므로 동결 판(`Archive1447*CityConst`)을 덮지 않고 새 variant 로 가야 한다.

## 2. 승인 뒤 실행 순서

1. **무변경 프로브.** 아무것도 안 바꾸고 꼬리 5단계를 다시 구워 `han-tiles.json` sha 가
   `bc25b1af…`(재핀 전 main) 로 돌아오는지 본다
   (partition → carve → fold → reclassify(`--source-is-upstream`) → refine_korea_places, `/usr/local/bin/python3`).
   2026-09-27 이 세션에서는 han-tiles 를 다시 쓰는 일이라 권한 판정에서 막혀 돌리지 못했다.
2. **원장 수정.**
   - `append_all_gap_counties.py --refresh-readings` (13행), 테스트의 `PENDING_*` 비우기.
   - 합성 좌표 교체: 泉州·阴平道·寧平·太丘 의 `coordinates` 를 원장 좌표로, `positionStatus` 는 `APPROXIMATE`,
     출처 URL·TGAZ id 를 `coordinateEvidence` 에. `coordinateBasis` 는 기존 어휘를 쓴다(太丘·寧平 `READY_TGAZ`,
     泉州 `READY_NAMU`). 웹 출처만 있는 阴平道 만 새 값이 필요하다 — 새 값을 넣기 전에 소비자(carve·
     `measure_province_seat_offset`·경로 노드 원장·`StrategicTopologyJson`)가 받는지 본다.
     `syntheticPlacement` 는 지우지 말고 `supersededSyntheticPlacement` 로 남긴다.
   - D1 승인 시: 중복 23행을 `retiredAsDuplicate` 로 옮기고, `build_junguozhi_county_gaps.py`·`audit_county_coverage.py`
     가 함께 읽는 개명·이체자 별칭 입력(郡國志 행 → 기존 관할 id)을 만든다. 漢昌→魏昌(44996)·酇→赞(45748) 포함.
     두 도구의 IN_OWN 수가 같아야 한다(`test_agrees_with_audit_county_coverage`).
   - D2 승인 시: 4행을 ABSENT 로 되돌리는 동명 가드(郡 중심 400 km 초과 IN_OTHER 는 IN_OTHER 로 세지 않는다)를 빌더에 넣고
     `append_all_gap_counties.py` 로 추가한다. 高平·鹵城 은 preferred 점을 원장 좌표로.
3. **꼬리 재구움.** 1 과 같은 5단계. 각 단계 `--check`. 城 수·관할 수·parentRegions 수를 기록하고
   1133 이 보이면 북동 확장 층이 빠진 것이다(refine_korea_places 누락).
   - `carve_strategic_site_provinces.py` 는 원장 좌표에서 6칸(`MAXIMUM_DISPLACEMENT`) 안에 앵커를 세우고,
     donor 省이 작으면 `DONOR_TOO_SMALL` 로 **조용히 뺀다.** 좌표를 바꾼 4곳이 excluded 목록에 없는지 확인한다.
     阴平道 는 X025 표지점(게임 城 아님)에서 2칸이다.
   - `measure_province_seat_offset.py` 의 Q1b 는 `SYNTHETIC_COMMANDERY_CELL` 만 빼므로 좌표를 바꾼 4곳이 측정에 들어간다.
   - 좌표를 바꾼 4곳은 省 경계를 새로 가른다(泉州 → 潞县 省, 寧平 → 汝南 宜祿 省, 太丘 → 沛國 酇 省, 阴平道 → 广武 省).
     이웃 省 모양 규칙(좁은 구역 금지·구역 안 구역 금지) 검사를 같이 본다.
4. **핀 사슬.** 순서 고정:
   `water-topology-adjudications-v1.json` base sha 교체 → `build_han_water_topology.py --write` →
   `build_han_parent_reconciliation.py --write` · `audit_han_admin_topology.py` · `build_province_city_attribution.py` ·
   `build_scenario_province_ownership.py` → `build_han_world.py --target han-world-v3` →
   `append_gap_county_route_ledgers.py`(경로 노드 원장) → 테스트 핀(`test_han_tiles_contract.py`, `StrategicTopologyJsonTest`) →
   릴리스 번들(D1·D2 면 새 variant, 아니면 1447 두 판의 blob·catalog·`CATALOG_SHA256`).
   끝으로 옛 sha 를 저장소 전체 grep 해서 0건(2026-09-27 실측 13개 파일) → `check_han_tiles_coupled.py --check --include-slow`
   → CI 와 같은 지도 fast 파일 목록 unittest 전체.
5. **리셋.** 운영 반영은 별도 승인. 초기화 기대 城 수가 바뀐다.

## 3. 합성 城 219곳(검증된 4곳 제외) 좌표 확보 규모

로컬 자료만으로 잰 값이다(나무위키 원장·TGAZ 이름 조인·TGAZ 원본 전수).

| 갈래 | 수 | 다음 일 |
|---|---:|---|
| A 기존 城과 같은 縣(중복) | 22 | 좌표가 아니라 D1 |
| B1 로컬 후보가 郡 중심 200 km 안 + 漢代 TGAZ 레코드도 근접 | 27 | 행마다 同名異地·0 km 검사 후 채택 |
| B2 로컬 후보 근접, 축 하나 | 16 | 웹 1건씩 교차 |
| C 로컬 후보가 200 km 밖 | 17 | 同名異地 심사(廣漢 白水처럼 진짜일 수 있다) |
| D 근처에 후대 TGAZ 레코드만 | 17 | 讀史方輿紀要 로 기준점 인정 여부(南安·定陽 선례) |
| E 멀리 떨어진 동명 레코드만 | 23 | 사실상 F |
| F 로컬 좌표 없음 | 97 | 웹·문헌 조사. 상당수는 UNKNOWN 으로 남을 것 |

같은 방식(웹 + TGAZ 교차)으로 곧바로 갈 수 있는 것은 B1·B2 43곳, 심사가 붙는 것이 C·D 34곳,
좌표 자체를 찾아야 하는 것이 E·F 120곳이다.

## 4. 城 id 목록 (2026-09-27 main `c7ff8430b` 기준)

**D1 은퇴 23곳** — 타일 id(월드 id 이름) → 남길 城 타일 id(월드 id 이름)

| 郡國志 | 은퇴 | 남김 |
|---|---|---|
| 河南尹 新城 | gc-g0000-018 (1399 신성(河南尹)#1399) | 82841 新成县 (857 신성(河南尹)#857) |
| 河東郡 绛 | gc-g0002-011 (1401 강(河東郡)) | 95423 绛邑县 (854 강읍) |
| 京兆尹 霸陵 | gc-g0004-002 (1403 패릉) | 70647 霸城县 (859 패성) |
| 左馮翊 高陵 | gc-g0005-001 (1405 고릉) | 70741 高陆县 (860 고륙) |
| 右扶風 平陵 | gc-g0006-003 (1410 평릉) | 70715 始平县 (862 시평(右扶風)) |
| 梁國 碭山 | gc-g0009-004 (1423 탕산) | 42794 砀县 (871 탕) |
| 魯國 魯國 | gc-g0012-001 (1429 노국(魯國)) | 45180 鲁县 (874 노(魯國)) |
| 魏郡 沙 | gc-g0013-011 (1431 사(魏郡)) | 87150 涉县 (877 섭) |
| 鉅鹿郡 陶 | gc-g0014-001 (1432 도) | 87061 廮陶县 (879 영도(鉅鹿郡)) |
| 鉅鹿郡 南〈糸言糸〉 | gc-g0014-015 (1433 남〈멱언멱〉) | 87055 南䜌县 (888 남련) |
| 河閒國 易 | gc-g0018-003 (1436 역(河閒國)) | 87297 易城县 (993 역성(河閒國)) |
| 勃海郡 脩 | gc-g0021-008 (1437 수(勃海郡)) | 87600 蓚县 (890 수(河閒國)) |
| 泰山郡 山茌 | gc-g0026-006 (1443 산치) | 45127 茬县 (897 치(泰山郡)) |
| 東海郡 郯 | gc-g0030-001 (1446 담) | 85649 郯城县 (907 담성) |
| 東海郡 合鄕 | gc-g0030-010 (1448 합향) | 45299 合城县 (906 합성) |
| 廣陵郡 輿 | gc-g0033-009 (1452 여(廣陵郡)#1452) | 42698 舆国 (909 여(廣陵郡)#909) |
| 東萊郡 惤 | gc-g0039-003 (1461 현) | 85376 㡉侯国 (917 견(東萊郡)) |
| 豫章郡 廬陵 | gc-g0053-005 (1469 여릉) | 40885 高昌县 (960 고창) |
| 巴郡 充国 | gc-g0055-012 (1471 충국) | 44564 西充国县 (1000 서충국) · 44570 南充国县 |
| 廣漢郡 葭萌 | gc-g0056-008 (1473 가맹) | 44587 汉寿县 (976 한수(巴郡)) |
| 蜀郡 八陵 | gc-g0057-009 (1476 팔릉) | 96436 蚕陵县 (978 잠릉) |
| 鴈門郡 汪陶 | gc-g0085-005 (1585 왕도) | 95061 浧陶县 (992 영도(鴈門郡)) |
| 上谷郡 下落 | gc-g0090-008 (1603 하락(上谷郡)#1603) | 87638 下洛县 (996 하락(上谷郡)#996) |

**D2 추가 4곳** — 원장 id / 발급될 타일 id (월드 id 는 재구움 뒤 확정)

| 郡國志 | 원장 id | 타일 id | 위치 |
|---|---|---|---|
| 山陽郡 髙平 | hhs:111:山陽郡:004 | gc-g0028-004 | TGAZ hvd_45198 (116.71867, 35.15031) |
| 鴈門郡 卤城 | hhs:113:鴈門郡:011 | gc-g0085-011 | TGAZ hvd_95106 (113.77709, 39.29486) |
| 上郡 候官 | hhs:113:上郡:010 | gc-g0080-010 | 합성 칸 |
| 南陽郡 成都 | hhs:112:南陽郡:033 | gc-g0041-033 | 합성 칸 |

## 5. 실행 기록 (2026-09-27, 사용자 승인 D1·D2·D3)

- 무변경 프로브: 꼬리 5단계가 main 의 han-tiles sha 를 그대로 재현했다(추적 파일 변경 0).
- 원장: `apply_gap_county_recheck.py`(은퇴 23·좌표 4) → `append_all_gap_counties.py --refresh-readings`(12행) →
  `append_all_gap_counties.py --corpus-root …`(동명 실결손 4). 추가 도구는 기존 행을 다시 정렬하지 않게 고쳤다
  (원장 순서 = carve 省 발급 순서).
- 이름 대조: `junguozhi-county-aliases-v1.json`(별칭 24 · 동명 거부 4)을 `build_junguozhi_county_gaps.py`·
  `audit_county_coverage.py`·`build_officer_native_county.py` 가 함께 읽는다. 결과 ABSENT 3 · IN_OTHER 21 · IN_OWN 1156.
- 경로 노드: `retire_gap_county_route_ledgers.py`(키 23 → `gap-county-duplicate-retirements-v1`, 번호 예약) →
  `append_gap_county_route_ledgers.py`(1621–1624). 은퇴 城이 맡던 郡 治所 4곳(東海·鉅鹿·左馮翊·魯國)은
  같은 縣인 기존 城의 w2 claim 이 `COMMANDERY_SEAT` 를 이어받는다(월드 isSeat 147 유지).
  「늦게 붙은 합성 城 번호가 모든 append 보다 뒤」 계약은 발급 순서 기준으로 좁혔다.
- 새 판: `han-world-v3-1428`(4배 격자). 1447·1447-map4 는 무결성만 보는 동결 판이 됐다. Kotlin
  `WorldMapVariant.V3_1428`·동결 상수·번들 로더·판별기·`PhaseBoundary`·`StrategicTopologyJson` 배선, Dockerfile 2곳,
  리셋 기본값 1428.
- HWIHA 시드: `scenario_990002` 는 생성 테스트로 다시 만들었고, `scenario_3190` 은 파일럿 입력 없이
  `promote_3190.py --rewarehouse` 로 창고만 새 행정 縣 1282곳에 다시 맞췄다(재고 21곳·자금 169,000 그대로).
- 남긴 것: 南陽 酇(贊縣 省 45748 접기 해제)은 별도 결정. 별칭 대상 城에 郡國志 행정 단위를 붙이는 재결속
  (route node rebinding)은 하지 않았다 — 행정 축 CANONICAL 1127 → 1108, 행정 축 郡 치소 101 → 97.
