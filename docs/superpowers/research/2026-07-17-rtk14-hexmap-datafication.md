# RTK14 지도 원본 이미지 헥스 지형 데이터화

- **status:** `DONE (builder + tests green; wiki-grounded palette; visual QA PASS; RIGHTS WARN)`
- **scope:** research/tooling only — 결정적 헥스 지형 추출 빌더·테스트·방법 문서. runtime 소비 없음.
- **ticket:** OPENSAM-92·93·94·97·103 실행 계약 §13-5 (2026-07-17 사용자 지시) + 팔로업 지시("각 지도 이미지의 셀 색상은 위키에서 확인하고.")
- **owner lane:** `lane-map-datafy`
- **version-controlled artifacts (only):** `tools/rtk14/build_rtk14_hexmap.py`, `tools/rtk14/test_build_rtk14_hexmap.py`, 이 문서
- **RIGHTS WARN:** 원본 지도 이미지는 Koei IP이며 재배포 권리 미확인. 원본도 산출 JSON도 저장소에 커밋/번들하지 않는다.

## 1. 결론

1. `[사실]` **지형 색상은 RTK14 공식 wiki `地理` 페이지의 지형 효과표 "カラー" 열(지형별 CSS 스와치 hex)에서 verbatim으로 가져왔다.** 눈대중 관측은 그 wiki hex 주변 tolerance로만 쓰고, wiki 범례에 없는 색은 지형으로 발명하지 않는다. 팔로업 사용자 지시("셀 색상은 위키에서 확인")를 이 카라 범례로 충족했다.
2. `[사실]` wiki `カラー` 범례는 **22개 지형**을 정의한다: 平地/街道/砂地/湿地/森/密林/毒沼/低山/中山/高山/山道/浅瀬/川/大河/府/都市/関所/港/奔流/険山/崖/急流. 이 hex 집합이 팔레트 centroid이며(§4 표), 이미지의 지배색 대다수가 이 hex와 **정확히(L1=0) 일치**한다(高山 (151,72,7), 大河 (33,88,103), 中山 (228,109,10), 低山 (255,153,51), 森 (117,146,60), 湿地 (230,185,184), 街道 (255,204,0), 密林 (79,98,40), 川 (49,132,155), 毒沼 (112,48,160), 山道 (216,216,216)). 즉 이 지도는 wiki 카라 값으로 그대로 렌더된다.
3. `[사실]` **눈대중 초안 대비 wiki 검증이 잡아낸 교정:** ①`海`(SEA)는 wiki 카라 범례에 없다 → 삭제. 외곽 수역은 `大河`(#215867)/`急流`(#0f253f) 색으로 렌더되므로 그 라벨로 분류한다. ②이전에 CITY로 뭉쳤던 금색(#ffcc00)은 실제 `街道`(도로)다. ③이전에 일반 "적색 점 마커"로 UNKNOWN 처리하던 #ff0000은 실제 `府`(정부 거점)다(OPENSAM-102의 적색 소거점과 정합) — 더 이상 가림 처리하지 않고 `府`로 분류. ④추가 wiki 색 `関所`(#c00000)/`港`(#ff66cc)/`崖`(#632523)/`山道`(#d8d8d8)/`浅瀬`(#93cddd)/`奔流`(#9bc5ff)를 분리. ⑤`毒沼`는 wiki 표기(팬사이트의 `毒泉` 아님)를 채택 — **wiki가 이긴다**.
4. `[사실]` RTK14 지도(`PK.png`, `4181×4191`)는 헥스맵을 **offset-column(flat-top) square 타일**로 렌더한다. 타일 pitch는 x/y 모두 **19px**(autocorrelation). 열 중심 x=`9+19·col`, 짝수 열 y=`19·row`, 홀수 열은 `+9.5` 세로 오프셋. 전체 격자 **220열 × 최대 221행, 48,620 셀**.
5. `[사실]` 빌더는 결정적이다(같은 입력 → byte-identical, 이중 실행 SHA `0b18b00c…` 동일). 각 셀은 `{q,r,cx,cy,terrain,terrain_jp,kind,confidence}`(UNKNOWN 셀은 `observed_rgb` 추가)를 갖고, `cx,cy`는 OPENSAM-102 좌표 원장과 **동일 native-pixel 공간** 중심이라 join 키다(洛陽 target `(1890,1415.5)` → 셀 중심 `(1890.0,1415.5)` 정확 일치).
6. `[사실]` wiki 색 미매칭·저신뢰 셀은 `UNKNOWN`(1.3%, `observed_rgb` 기록), 지도 밖 백색은 `OUT_OF_BOUNDS`(15.8%)로 남겨 추측하지 않는다.

## 2. Source provenance & 근거 URL

| 항목 | 값 |
|---|---|
| 파일 | `PK.png` (RTK14 with PK, WIKIWIKI `地理` attachment) |
| SHA-256 | `dfe5049245e730ff1f81325157dedfcf1079786e33c6ad8b3970140954910a89` (실측 일치) |
| 크기 | RGB PNG `4181×4191`, `281,922` bytes |
| canonical 이미지 URL | `https://cdn.wikiwiki.jp/to/w/sangokushi14/地理/::attach/PK.png?rev=ab2f8e0ceae2aeb16f3291ffa88907d0&t=20201213195804` |
| **1차 색+타입 근거 (wiki 카라 범례)** | **`https://wikiwiki.jp/sangokushi14/地理`** — 지형 효과표 "カラー" 열(지형별 CSS 스와치 hex) |
| 2차 타입 집합 근거 (공식 매뉴얼) | `https://www.gamecity.ne.jp/manual/JuLnHe14/jp/3300.html` |
| 격리 원본 경로 | repo 밖 quarantined source (OPENSAM-102 고정; 파일 경로 미기재, sha256만 근거) |

- 색상 근거의 **정본은 wiki `地理` 페이지 카라 범례**다. 페이지 본문은 텍스트 추출 시 색을 드러내지 않지만("カラー" 헤더만 남음), 원시 HTML의 각 지형 행에 `style="background-color:#RRGGBB"` 스와치가 있어 hex를 verbatim 회수했다(§4).
- **RGB/hex 미문서 색은 발명하지 않는다.** wiki에 없는 이미지-기계적 색(순수 격자선 회색·지도밖 백색)은 지형이 아니라 sentinel/UNKNOWN으로만 처리한다.

## 3. 헥스 geometry (변경 없음)

- **측정:** 산악 밴드 스캔라인 autocorrelation → 두 축 pitch 19px(부-피크 38). 세로 격자선 위상 `x0=18.5` → 열 중심 `9+19·col`. 인접 열 `~pitch/2` 오프셋 → offset-column(flat-top).
- **파라미터(출력 header 기록, CLI override 가능):** `hex_size=19.0`, `origin_x=9.0`, `origin_y=0.0`, `orientation=flat` → `odd_col_dy=9.5`. `sample_radius=4`(9×9 윈도), `color_tol=60`(L1), `min_conf=0.5`.
- **좌표계:** `q`=열, `r`=열 내부 행(offset-column). join authoritative 키는 `cx,cy`(이 PNG의 presentation native-pixel 중심; world/투영 좌표 아님).

## 4. wiki `カラー` 색상 범례 → 지형 (verbatim)

wiki `地理` 지형 효과표 "カラー" 열에서 회수한 hex를 그대로 팔레트 centroid으로 쓴다. `wiki_effect`는 같은 표의 효과 열 verbatim. 출력 JSON `wiki_color_legend.legend`에 동일 표가 실린다.

| terrain_code | jp | wiki hex | RGB | kind | wiki 효과(verbatim) |
|---|---|---|---|---|---|
| PLAINS | 平地 | `#bdb76b` | (189,183,107) | terrain | 移動〇 火計〇 建設可能 |
| ROAD | 街道 | `#ffcc00` | (255,204,0) | infra | 移動◎ 火計〇 建設可能 |
| SAND | 砂地 | `#ffff99` | (255,255,153) | terrain | 移動〇 火計△ 建設可能 |
| WETLAND | 湿地 | `#e6b9b8` | (230,185,184) | terrain | 移動△ 火計△ 士気△ |
| FOREST | 森 | `#75923c` | (117,146,60) | terrain | 森戦・山越・南蛮で強化 |
| DENSE_FOREST | 密林 | `#4f6228` | (79,98,40) | terrain | 森戦・山越・南蛮で強化 |
| POISON_SWAMP | 毒沼 | `#7030a0` | (112,48,160) | terrain | ダメージ 解毒で回避 |
| MOUNTAIN_LOW | 低山 | `#ff9933` | (255,153,51) | terrain | 山戦・烏桓/鮮卑/匈奴/羌氐で強化 |
| MOUNTAIN_MID | 中山 | `#e46d0a` | (228,109,10) | terrain | 山戦・烏桓/鮮卑/匈奴/羌氐で強化 |
| MOUNTAIN_HIGH | 高山 | `#974807` | (151,72,7) | terrain | 山戦・烏桓/鮮卑/匈奴/羌氐で強化 |
| MOUNTAIN_PATH | 山道 | `#d8d8d8` | (216,216,216) | terrain | 山戦・烏桓/鮮卑/匈奴/羌氐で強化 |
| SHALLOWS | 浅瀬 | `#93cddd` | (147,205,221) | terrain | 山越で強化 |
| RIVER | 川 | `#31849b` | (49,132,155) | terrain | 山越で強化 |
| MAJOR_RIVER | 大河 | `#215867` | (33,88,103) | terrain | 火船設置可 水戦で強化 |
| GOVERNMENT | 府 | `#ff0000` | (255,0,0) | infra | 使役で強化 |
| CITY | 都市 | `#ffff00` | (255,255,0) | infra | (효과 없음) |
| FORT | 関所 | `#c00000` | (192,0,0) | infra | (효과 없음) |
| PORT | 港 | `#ff66cc` | (255,102,204) | infra | 使役で強化 |
| RAPIDS | 奔流 | `#9bc5ff` | (155,197,255) | terrain | 蒙衝・楼船のみ進入可 建築不可 |
| STEEP_MOUNTAIN | 険山 | `#333333` | (51,51,51) | terrain | 進入不可 |
| CLIFF | 崖 | `#632523` | (99,37,35) | terrain | 進入不可 |
| SWIFT_CURRENT | 急流 | `#0f253f` | (15,37,63) | terrain | 進入不可 |

**wiki 외 sentinel(지형 아님, 발명 아님):** `OUT_OF_BOUNDS` = 지도밖 백색 `#f8f8f8`. 순수 격자선 회색(#bcbcbc)은 sentinel로 두지 않는다 — 렌더된 `平地`(≈197,190,151)를 회색 쪽으로 잠식하기 때문. 격자 그물은 셀 중심 윈도에서 out-vote되고, wiki 색과 tol 밖인 회색은 UNKNOWN이 된다.

**분류 절차:** 전 픽셀 nearest-centroid 라벨·L1거리 1회 계산 → 셀 9×9 윈도에서 `color_tol` 이내 픽셀만 majority 투표. `confidence`=승자 비율. tol 이내 0개거나 `min_conf` 미달 → `UNKNOWN`(관측 RGB 기록). sentinel 지배 → `OUT_OF_BOUNDS`.

## 5. Run 통계 (원본 PK.png, wiki 팔레트)

- 총 셀 48,620, UNKNOWN 637(**1.3%**), OUT_OF_BOUNDS 7,674(15.8%). (눈대중 초안 UNKNOWN 2.5% → wiki 그라운딩으로 1.3%로 감소.)

| terrain | 셀 | % | terrain | 셀 | % |
|---|---:|---:|---|---:|---:|
| MOUNTAIN_HIGH | 4,871 | 10.0 | PLAINS | 4,847 | 10.0 |
| MAJOR_RIVER | 4,431 | 9.1 | STEEP_MOUNTAIN | 4,188 | 8.6 |
| MOUNTAIN_MID | 4,076 | 8.4 | MOUNTAIN_LOW | 3,851 | 7.9 |
| SAND | 3,770 | 7.8 | FOREST | 2,382 | 4.9 |
| WETLAND | 2,151 | 4.4 | ROAD | 1,750 | 3.6 |
| DENSE_FOREST | 767 | 1.6 | RIVER | 755 | 1.6 |
| MOUNTAIN_PATH | 745 | 1.5 | POISON_SWAMP | 383 | 0.8 |
| CITY | 352 | 0.7 | RAPIDS | 327 | 0.7 |
| CLIFF | 311 | 0.6 | GOVERNMENT | 242 | 0.5 |
| SWIFT_CURRENT | 102 | 0.2 | FORT | 8 | 0.0 |

- 산악 계열 합(低/中/高/険/山道) ≈ 36%는 서부 고지대 지배 삼국 지리와 정합. PLAINS 복원(10.0%)으로 중원 평지 재현. `PORT`/`SHALLOWS`는 0 — 항구·여울은 셀 중심에 잡히지 않는 소형 지물(§8 한계).

## 6. Visual QA verdicts (자가 판정, 원본 overview vs wiki-hex 분류 래스터)

| 지역 | 판정 | 근거 |
|---|---|---|
| 전체 overview | PASS | wiki-hex 분류 래스터가 원본 지형 배치를 충실 재현: 서부 산악 gradient·중원/동부 평지(복원)·동남 저습지·외곽 大河 수역·동남 奔流/川 수로·심남 밀림/독소·서북 사지·금색 街道 도로·황색 都市·적색 府. |
| 洛陽 주변 | PASS | 平地/森/大河/都市 정확. 街道 도로 라인 분리. magenta(UNKNOWN)는 근-흑색 해안 경계선에만. |
| 建寧(남만) | PASS | 毒沼(자주)·密林(암녹)·산악 정확. |
| 武威(서북) | PASS | 砂地 정확(스와치보다 밝게 렌더되나 tol 내). |
| 成都(서부) | PASS | 低/中/高山·険山 gradient·森 정확. |

magenta(UNKNOWN)는 근-흑색 지도 경계 outline(≈13,13,13; wiki 색과 tol 밖)에 집중 — 지형 아님이 올바른 결과.

## 7. 결정성·검증

- 이중 실행 byte-identical(run1==run2 SHA `0b18b00c…`). timestamp 없음, 셀 정렬 `(q,r)` 고정.
- 테스트: `python3 -m unittest discover -s tools/rtk14 -p 'test_build_rtk14_hexmap.py'` → **21 tests OK**. 전체 `test_*.py` → **29 tests OK**(기존 stats 8 무회귀).
- 커버리지: offset-column geometry·native-pixel 중심·열 커버리지; wiki hex 22종 자기분류·SEA 부재·미매칭 UNKNOWN·府/都市 infra 라벨·회색 sentinel 제거 회귀(平地 복원)·먼 회색 UNKNOWN·백색 OUT_OF_BOUNDS·저신뢰 UNKNOWN; 결정적 정렬·byte-stable 이중빌드·wiki_color_legend header(행수=22, hex↔rgb 일치)·UNKNOWN observed_rgb; tracked-repo fail-closed / gitignored·repo밖 허용.

## 8. Limitations / UNKNOWN

- **wiki 카라는 hex는 주되 개별 지형의 map RGB "legend swatch"이지, 매 픽셀 계측값이 아니다.** 이 지도는 대다수 지형을 카라 hex 그대로 렌더하지만, `平地`(≈197,190,151)와 `砂地`(≈255,255,204)는 스와치보다 밝게 렌더된다. 이는 wiki 색 주변 tolerance(≤60 L1)로 흡수했다(디렉티브 허용 범위: "eye-calibration은 wiki 색 주변 tolerance 조정으로만"). `平地`는 L1=59로 tol 경계에 가깝다.
- **`PORT`/`SHALLOWS` 미출현(0).** 항구·여울은 해안의 소형 지물이라 9×9 셀 중심 윈도에서 majority를 얻지 못한다. 팔레트에는 wiki 색으로 존재하나 이 해상도에서 셀 라벨로는 잡히지 않는다.
- **격자선 회색→WETLAND(tolerance 우연).** #bcbcbc 격자선은 `湿地` 스와치(230,185,184)와 L1=49로 우연히 가까워 픽셀 단위로는 WETLAND로 분류되지만, 격자선은 셀 중심에서 out-vote되어 WETLAND 총계를 부풀리지 않는다(2,369→2,151로 오히려 감소).
- **`海`(개활 해양) 색 부재.** RTK14 `地理` 카라 범례에 海가 없다 → 외곽 수역은 `大河`/`急流` 색으로 렌더되어 그 라벨로 분류된다. 이는 발명이 아니라 wiki 범례를 따른 결과다.
- **街道/山道 등 인프라의 지형 하부 미보장.** infra 색(街道/府/都市/関所/港)은 그 셀의 지물을 뜻하며 하부 자연지형은 마스킹된다.
- **native-pixel presentation 좌표만.** `cx,cy`는 이 PNG의 presentation 좌표이며 world/투영/역사 좌표·헥스 인접 그래프의 증거가 아니다.
- **런타임 소비는 별도 승인.** 이 데이터를 `MapJson`/`ScenarioJson`/`PhysicalPlace`에 넣는 것은 OPENSAM-103/105 계약의 별도 사람 승인 사항이다. 이 lane은 데이터화 도구까지만 닫는다.

## 9. 출력 경로 정책 (권리 보수성)

- `--source`/`--out`은 repo 밖 또는 gitignored 경로만 허용. tracked repo 경로 지정 시 **fail-closed**(git check-ignore 기반).
- 원본 이미지·산출 JSON·QA overlay는 저장소에 커밋하지 않는다. 버전 관리 대상은 스크립트·테스트·이 문서뿐.

## 10. Downstream

- OPENSAM-103 cutover spec이 content-layer 지형 데이터의 identity/version/rights 계약을 정의한 뒤, OPENSAM-105가 rights-cleared 상태에서 헥스 지형을 runtime에 매핑할 수 있다.
- `cx,cy`가 OPENSAM-102 101좌표(46 city + 55 small-base)와 동일 공간이므로, 도시/거점 셀 join과 지형 오버레이가 가능하다(권리 gate 통과 후). 특히 `府`(적색) 셀이 OPENSAM-102 적색 소거점과 정합하는지 교차검증에 쓸 수 있다.
