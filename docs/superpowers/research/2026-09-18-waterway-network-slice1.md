# 수로 망 1차 — 長江·黃河 구간과 나루·항구 증거 원장 (#826)

- 정본 설계: `docs/superpowers/specs/2026-09-05-water-transport-and-naval-topology-design.md`
- 원장: `data/curated/han/waterway-network-adjudications-v1.json`
- 산출물: `data/map/han-waterway-network-v1.json` (`tools/map/build_han_waterway_network.py --write|--check`)
- 상태: **NON_ACTIVATING** — 어떤 런타임도 읽지 않는다. 간선은 전부 `PROPOSED_NOT_ACTIVATED`.

## 왜 별도 산출물인가

`han-water-topology-v1.json`·`water-topology-adjudications-v1.json`·`han-strategic-topology-manifest-v1.json` 은
런타임 로더(`HanStrategicTopologyJson`) 입력이고 1133 동결 번들(`catalog.json` → `Han1133Artifacts.CATALOG_SHA256`)에
해시로 물려 있다. 바이트가 바뀌면 `StrategicTopology.contentHash` 가 바뀌어 1133 에 핀된 월드가 초기화 전까지
로드되지 않는다. 그래서 이번 판은 그 세 파일을 건드리지 않고 오버레이로 쌓았다. 런타임 파일로 접어 넣는 일
(activation blocker 해소, 비용·용량 판정, 번들 재핀, 월드 초기화)은 다음 판의 별도 승인 사항이다.

## 모델

- 구간(`RIVER_REACH`): 계통 성분(長江 1,661칸·黃河 707칸) 위에서 상자로 자른 RIVER 칸의 8-연결 조각. 이름·분기점·
  흐름 방향은 사료 인용이 있을 때만 적고 없으면 `null`/「미판정」이다. 상자 안 지류 토막은 분리하지 못했다(`geometryReview`).
- 노드: 나루(strongholds `role=FERRY`) 또는 城. 칸은 원본 그대로이며 옮기면 빌더가 죽는다.
  - `CROSSING` → `FERRY` 간선(양안 省). 양안 省은 사료가 아니라 소유 격자에서 읽는다(`bankRule`). 빌더는 두 둑 칸이
    구간을 빼면 서로 다른 육지 성분에 속하는지 검사한다.
  - `PORT` → `EMBARK`/`DISEMBARK` 한 쌍. 칸이 **제 구간** 물 칸에서 1칸 이내여야 한다 — 내륙 항구는 만들 수 없다.
- 흐름(`RIVER_UP`/`RIVER_DOWN`): 맞닿은 두 구간 + 방향 사료가 있을 때만.
- `blocked`: 나루 37곳은 노드 아니면 blocked 에 정확히 한 번 나온다. 거리는 빌더가 다시 잰 값과 같아야 한다.
- 비용·용량·계절은 증거가 없어 적지 않았다.

## 1차 결과

구간 8(長江 4 + 沔水 1 + 黃河 3) · 노드 9 · 도하 5(孟津·蒲阪津·郖津·陝津·漢津) · 항구 5(漢津·樊口·濡須口·江州·夷陵) ·
흐름 2(武昌→彭蠡→建業, 江表傳 「水道溯流二千里」) · blocked 34(물길 없음 19 · 역할 미입증 9 · 省이 물에 안 닿음 1 ·
범위 밖 1 · 증거는 있으나 칸이 물/구간에서 먼 城 4: 建業·江陵·夏口·廣陵).

## 게이트와 적색 프로브

`tools/map/tests/test_build_han_waterway_network.py` — 원장을 깨뜨려 빨개지는 것을 본다: 산출물 드리프트, 내륙 항구,
남의 구간 항구, 칸 옮기기, 같은 둑 양안, 한 省 양안, 둑 칸 소유 불일치, 출처 없음·모르는 출처, 나루 인용 드리프트,
나루 누락·거리 위조, 먼 나루 밀반입, 떨어진 구간 잇기, 활성화 시도, 기준 핀, 구간 기하.
CI 는 `tools/map/tests/test_*.py` 글롭과 `check_han_tiles_coupled.py`(키 `waterway-network`)로 자동 실행한다(1–2초).

## 한계

- `SHILIAO_QUERY` 출처의 인용문은 CI 가 재검증하지 못한다(말뭉치 미커밋). 水經注는 색인에 없다.
- 柴桑은 배·수군 문구를 찾지 못해 원장에 넣지 않았다.

## 2026-09-18 후속 — 거점 원장 정정과 활성화 판정

- **鸇陰口**: `strategic-strongholds-v1` 의 `supports` 를 `FERRY_CROSSING_IN_EVENT` → `INTERCEPTION_AT_SITE` 로 고쳤다.
  三國志 卷15 魏書·張既傳 원문은 「旣至金城，欲渡河…遂渡河。賊七千餘騎逆拒軍於鸇陰口，旣揚聲軍從鸇陰，乃潛由且次出至武威」 —
  渡河는 金城에서 끝났고 鸇陰口는 적이 막은 자리이며 張既는 그곳을 피했다. 게이트
  `test_ferry_crossing_support_quotes_a_crossing` 가 같은 실수를 막는다(되돌리면 빨개진다).
- **`tileAnchor.provinceId` 는 낡은 것이 아니다.** 1차 보고는 82879 같은 값이 `ss-*` 省 이전 것이라 오류라고 봤으나,
  원장 공식(`tileAnchorBasis`)대로 다시 계산하면 **65행 전부 지금 값 그대로** 나온다. 앵커는 설계상 거점 省 분할
  **전** 문서 기준이다 — `carve_strategic_site_provinces.py` 가 이 원장을 입력으로 칸을 떼므로, 분할 뒤 省(`ss-*`)을
  여기 적으면 입력이 제 출력에 기대는 순환이 된다. `test_tile_anchor_matches_han_tiles` 가 분할·접기 단계를 벗겨 낸 뒤
  독립 구현으로 다시 재어 이를 강제한다. 값은 바꾸지 않았다. `sha256AtBuild` 는 어떤 게이트도 읽지 않는 기록값이다.

## 2026-09-18 후속 2 — 강 뱃길을 城 연결로 (ADR-LITE-060)

타입 간선 활성화는 여전히 막혀 있다(위 판정). 사용자 결정으로 바닷길 13줄과 **같은 방식**을 택했다 — 항구 城끼리
`han-world-v3.json` `connections` + `seaRoutes`(`kind: "RIVER"`). 원장에 `portLinks` 가 생겼고, 빌더가 쌍을 유도한다
(검토된 PORT 끼리 · flowLinks 로 이어진 구간 위 · 물길 거리로 사이에 다른 항구 없음 · 원장 집합 = 유도 집합).
`build_han_world.v3_river_routes` 는 산출물의 portLinks 만 읽는다. 타입 간선은 그대로 `PROPOSED_NOT_ACTIVATED` 다.

- 추가 구간: `jiang-yiling-xiakou`(97칸) · `jiang-xiakou-wuchang`(31칸, 沔水 하류 토막 포함 — 沔口 합류 미판정). 구간 10 · 흐름 5.
- 뱃길 3줄: 江州↔夷陵(물길 92칸) · 夷陵↔樊口(89칸) · 樊口↔濡須口(63칸). 漢津은 沔水가 江 과 흐름으로 이어져 있지 않아 빠졌다.
- 출처: 晉書 卷42 王濬傳(成都 → 西陵 → 夏口·武昌 → 三山, 順流) · 三國志 卷47 「是歳，改夷陵爲西陵」 · 기존 江表傳·先主傳·張飛傳.
- 실측 효과: 5→1 · 7→1 · 6→1홉, 전체 城 쌍의 15.4% 가 짧아졌다(최대 9홉). 1133 재핀 — 월드 리셋 필요.
