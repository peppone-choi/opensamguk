# 전 RTK 시리즈 지도 비교·보충 리서치

- **status:** `RESEARCH DONE (research-only; 코드/시드/스펙 미변경; RIGHTS WARN)`
- **lane:** `lane-map-rtk-series`
- **계약 근거:** `docs/superpowers/plans/2026-07-17-opensam-92-93-94-97-103-execution-contract.md` §13 (사용자 2026-07-17 지시: "모든 RTK 시리즈의 지도를 가지고와서 비교하고 보충") + §2 OPENSAM-105 제외 결정
- **access date:** 2026-07-17 (모든 웹 출처)
- **소스 분류:** 모든 RTK 자료는 `GAME_REFERENCE`. 역사 증거가 아니며 재배포/번들 미승인.
- **산출물:** 이 문서 + [인접 원장 CSV](./2026-07-17-rtk-series-adjacency-ledger.csv). 이 두 파일 외 어떤 코드·시드·시나리오·스펙·문서도 만들거나 바꾸지 않았다.
- **downstream:** OPENSAM-102(좌표) → OPENSAM-103(cutover spec) → **OPENSAM-105(도시 runtime, 이 리서치의 소비자)**

---

## 1. 결론 (핵심 보충 성과)

1. **[사실][DIRECT]** OPENSAM-102가 `[UNKNOWN]`으로 남긴 **full adjacency**를, RTK14 커뮤니티 위키(wikiwiki.jp/sangokushi14)의 **46개 도시별 `隣接都市`(인접도시) 필드 전수 수집**으로 근거화했다. 46개 도시 페이지를 모두 열람해 **93개 도시-도시 간선**을 확정했다: **86개는 양방향 상호선언(HIGH), 2개는 상호선언이나 본문 이견/편도 가능(MED), 5개는 편도-only(LOW)**. degree 0 도시는 없다(46/46 연결).
2. **[사실][DIRECT]** OPENSAM-102가 `parent_city=UNKNOWN`으로 둔 **5개 이민족 거점(烏桓·鮮卑·羌族·山越·南蛮)**에 대해, RTK14 PK 버전의 도시별 `隣接都市` 필드가 각 거점의 **인접 후보 도시들을 명시**함을 확인했다(총 11개 도시-거점 인접행). 다만 각 거점이 **복수 도시**에 걸리므로 **단일 `parent_city`는 여전히 `[UNKNOWN]`**이다. 이는 "보충은 발명이 아니라 근거 수집"(§13-3) 원칙에 맞춘, 근거 있는 후보 집합이다.
3. **[사실]** RTK14가 지도 콘텐츠 기준(§13-1)이므로 인접 근거의 **1차(primary)는 RTK14의 per-edge `隣接都市`**로 고정했다. 타 시리즈는 (a) **로스터 교차확인**(RTK11 荊州 6도시 동일, RTK8R 51도시 계층, RTK9 州별 도시)과 (b) **특정 회랑의 corroboration**(RTK8R 呉↔建業·呉↔会稽)만 근거로 인용했다. 전 시리즈 per-edge 전수 대조는 로스터가 서로 달라 하지 않았고, 근거 없는 시리즈 태그는 붙이지 않았다.
4. **[사실]** 지도 구조는 시리즈별로 **지역제(RTK1~2) → 도시제 도입(RTK3) → 군주단위 턴제(RTK4~) → 3D 일매(一枚) 지도(RTK9·11·14)**로 진화했다. 이 축은 아래 §3에 시리즈별로 정리하고 출처를 붙였다.
5. **[사실]** runtime `MapJson`이 요구하는 **12개 값**(§7 코드 근거) 중 **connections(인접)는 이 리서치로 RTK14 근거화**됐고, **x/y는 OPENSAM-102 native-pixel로 측정됨(runtime scaling은 미확인)**, 나머지(numeric id·level·region 코드북·경제치 6종)는 여전히 `[UNKNOWN]`이다. §6에 필드별 판정을 남긴다.
6. **[사실]** 어떤 원본 지도 이미지나 raw 캡처도 저장소에 두지 않았다. 이 pass는 텍스트 `隣接都市` 필드 판독만 수행했고 이미지를 다운로드/번들하지 않았다. RTK 자료 재배포 권리는 미확인(`RIGHTS WARN`).

---

## 2. 조사 경계와 판정 규칙

| 분류 | 의미 |
|---|---|
| `DIRECT` | 소스 페이지의 `隣接都市` 필드 또는 표가 해당 간선/값을 직접 제공. 소스의 정확성·역사성·재배포 권리까지 뜻하지 않음. |
| `sym`(HIGH) | 간선의 양 끝 도시 페이지가 서로를 `隣接都市`에 상호선언. |
| `asym`(LOW) | 한쪽 도시만 선언. 편도 claim으로만 보존하며 역방향을 자동 생성하지 않음. |
| `disputed`(MED) | 필드엔 있으나 같은/상대 페이지 본문이 in-game 인접 처리에 이견을 제기(예: 洛陽-晋陽). conflict를 숨기지 않고 함께 기록. |
| `corroborated` | 서로 다른 시리즈의 소스가 같은 간선을 뒷받침(evidence_series에 2개 이상). |
| `[UNKNOWN]` | 어느 소스에서도 근거를 얻지 못함. 이미지 추정·대칭 날조로 채우지 않음. |

- 수집 방법: 각 RTK14 도시 페이지의 `隣接都市` 필드를 read-only fetch로 판독(browser-like fetcher). 위키위키는 강한 rate-limit(HTTP 429)을 걸어, 3건 이하 소배치 + 타 도메인 서베이 요청으로 간격을 두어 46개 전수를 완료했다.
- 대칭 원장 원칙: 간선은 **RTK14 도시 ordinal(001..046) 오름차순 `from < to`로 1회만** 기록한다(CSV). 이민족 거점 행은 `to`에 거점명(烏桓/鮮卑/羌族/山越/南蛮)을 둔다.
- 편도/이견 간선은 **RTK14가 field로 지지하는 한 포함**하되 confidence와 note로 방향·이견을 명시한다(§13 "시리즈 불일치 시 RTK14 지지 간선만 포함, 불일치는 명기").

---

## 3. RTK 시리즈 지도 구조 비교

각 넘버링 타이틀의 지도 단위와 인접/이동 그래프 존재 여부. 구조 분류는 출처가 있는 것만 단정하고, 세부 도시 수 등 미확인 값은 `추정`/`[UNKNOWN]`으로 표기한다.

| 타이틀(발매) | 지도 단위 | 인접/이동 그래프 | 도시 수 | 근거 |
|---|---|---|---|---|
| RTK1 (1985) | **지역/주(州)제** — 이동·커맨드가 지역 단위 | 지역 인접(명시적 도시 route 그래프 아님) | 58 city / 9 province (kongming) | kongming.net/1/states-cities-guide; ja.wikipedia 三國志シリーズ("第2作までは地域単位") |
| RTK2 (1989) | **지역/주제** | 지역 인접 | 추정 (미확인) | ja.wikipedia 三國志シリーズ |
| RTK3 (1992) | **도시제 도입** — `都市単位戦闘` 첫 도입, 도시가 맵에 개별 노출 | 도시 인접 | [UNKNOWN] | ja.wikipedia 三國志("都市単位戦闘は三國志IIIで追加") |
| RTK4 (1994) | 도시 노드, `都市単位→君主単位のターン制` 전환 | 도시 간 이동 경로 | [UNKNOWN] | ja.wikipedia 三國志 |
| RTK5 (1995) | 도시 노드 + 이동 경로(육로/수로/관문) | 도시 인접 route | [UNKNOWN] | 일반 서베이(미세부확인) |
| RTK6 (1998) | 도시 노드 + 이동 경로 | 도시 인접 route | [UNKNOWN] | 일반 서베이 |
| RTK7 (2000) | 도시 노드(장수 RPG) + 이동 경로 | 도시 인접 route | [UNKNOWN] | 일반 서베이 |
| RTK8 (2001) | 도시 노드(장수 RPG) + 이동 경로 | 도시 인접 route | [UNKNOWN] | 일반 서베이 |
| **RTK9 (2003)** | **3D 一枚(seamless) 맵, 반실시간** | 도시 연결(마스/경로) 그래프 | 州별 도시(青州·冀州… 등 그룹) | ja.wikipedia 三國志("3D一枚マップによる半リアルタイム制"); gamefaqs RTK9 City/Territory 州 그룹 |
| RTK10 (2004) | 도시 노드(장수 RPG) + 이동 경로 | 도시 인접 route | [UNKNOWN] | 일반 서베이 |
| **RTK11 (2006)** | **3D 一枚 grid 맵, 箱庭(sandbox) 내정** | 都市+港+関 연결 그래프 | 荊州 6도시(襄陽·江陵·長沙·武陵·桂陽·零陵) 확인, 총 ~40+ 추정 | ja.wikipedia 三國志11; sangokushi11.shiyo.info/toshi.html; daisangokushi-kouryaku(荊州 郡城 lv) |
| RTK12 (2012) | 도시 노드, 인접 적 영토 배치로 전투 개시 | 도시 인접 | [UNKNOWN] | 서베이(threekingdoms.fandom RTK12) |
| **RTK13 (2016)** | **3D 一枚 seamless 맵** | 都市+港+関 연결(육로/수로/관문) | [UNKNOWN] | gamecity 三國志13; PlayStation blog 三國志13; memo.medamayaki 지도 |
| **RTK14 (2020)** | **3D 一枚 hex 맵, 색칠(land-painting) 영토** | 都市 `隣接都市` 그래프 + 338 area | **46 city / 338 area** (기준) | ja.wikipedia 三國志14; koeitecmoamerica manual rtk14; wikiwiki sangokushi14 都市(46/338) |
| RTK8 Remake (2024) | **地方(6)→州→都市 계층** | 도시별 `隣接都市` 존재(전수 미감사) | **51 city** (파생 카운트) | koeitecmoamerica manual rtk8-remake 6100; wikiwiki sangokushi8r 都市一覧(OPENSAM-102 R8-O-MAP/R8-W-CITY) |

**구조적 결론.** adjacency 근거로 유용한 시리즈는 **도시-연결형 타이틀(RTK9/11/13/14 및 RTK8R)**이다. 그중 **RTK14는 도시별 `隣接都市` 필드가 텍스트로 공개**돼 per-edge 근거가 가장 풍부하고, 기준(§13-1)이므로 1차로 채택했다. RTK9/11은 一枚 맵의 연결 그래프를 갖지만 텍스트 per-edge 표가 아니어서 **로스터·회랑 corroboration**으로만 사용했다. RTK1~2는 지역제라 도시 간선 근거로 부적합하다. RTK3~8/10/12는 도시-경로형이나 이번 pass에서 per-edge 텍스트 소스를 확보하지 못해 구조 분류만 남긴다.

**지도 셀 색상 근거 경계 (사용자 지시 2026-07-17: "각 지도 이미지의 셀 색상은 위키에서 확인하고").** 이 비교의 어떤 간선·구조 분류도 **지도 이미지의 셀 색상을 육안으로 판독해 얻지 않았다.** 인접은 전부 각 도시 페이지의 `隣接都市` **텍스트 필드**(CSV `source_url` 열에 per-claim URL)에서, 지형·경로 코멘트(늪·고산·米倉道 등)는 위키 **본문 prose**에서 인용했다 — 색상 추정이 아니라 문서화된 텍스트다. §3 표의 `색칠(land-painting)`·`hex 맵` 같은 표현은 ja.wikipedia/위키위키가 문서화한 **맵 종류 서술**이지 per-cell 색상 판독이 아니다. 각 시리즈 맵의 **셀 색상·범례 의미**(RTK14 헥스 지형 색칠, 영토 소유색, 지형 타입색 등)는 이 lane의 범위가 아니라 **`lane-map-datafy`(RTK14 헥스 지형 데이터화)**로 이관한다. 그 색상·범례가 해당 시리즈 위키에 문서화되지 않는 한, 색상 기반 지형/경로 주장은 이 문서에서 **`[UNKNOWN]`으로 남기고 육안 추정으로 채우지 않는다.**

---

## 4. 46-도시 로스터 시리즈 교차

기준 로스터는 OPENSAM-102가 고정한 RTK14 46도시(`都市` source order 001..046)다. 시리즈 간 이름 변이(한자/일어독음/한글/영문)와 존재 여부 관찰:

- **RTK14 = 기준.** 46개 한자 도시명은 OPENSAM-102 §4 ledger에 fingerprint와 함께 고정. 본 리서치는 그 46개에 대해 인접을 채웠다.
- **RTK11 교차:** 荊州 군성 로스터가 **襄陽·江陵·長沙·武陵·桂陽·零陵**로 RTK14와 **동일 한자**임을 확인(daisangokushi-kouryaku 荊州; shiyo toshi). RTK11 로스터는 RTK14 46개의 부분집합에 가깝고 이름 변이는 관찰되지 않았다(둘 다 일어 한자 표기).
- **RTK8R 교차:** 51도시 계층(地方6→州→都市). `秣陵(建業)`처럼 **213년 개명(秣陵→建業) time-scoped alias**가 존재(OPENSAM-102 R8-W-JIANYE). 이름 하나를 영구 identity로 쓰면 안 됨을 재확인.
- **RTK9 교차:** 州 그룹(冀州: 南皮·平原·鄴 / 幷州: 晋陽 / 交州: 交趾 …)이 RTK14 도시명과 상당 부분 겹침(gamefaqs RTK9 City/Territory). 州 그룹은 RTK14의 大地域/州 grouping corroboration이 되나, RTK14 region integer 코드북과 1:1은 미증명.
- **한글/영문 변이:** RTK 자료는 일어 한자 표기가 주이며, 한글/영문은 pinyin 로마자(예: 洛陽=Luoyang, 建業=Jianye) 수준의 기계 변환만 관찰. opensamguk stable identity·alias reconciliation은 여전히 OPENSAM-103/105 계약 몫(`[UNKNOWN]`).

**주의(§102 계승):** native-pixel/도시명 근거는 opensamguk stable ID·alias·역사 위치를 확정하지 않는다. 로스터 교차는 "같은 도시가 어느 시리즈에 있나"까지이며, 간선 근거로의 승격은 §5의 evidence_series 규칙을 따른다.

---

## 5. 인접 원장(CSV) 통계와 방법

산출물: [`2026-07-17-rtk-series-adjacency-ledger.csv`](./2026-07-17-rtk-series-adjacency-ledger.csv). 헤더 `from,to,evidence_series,source_url,confidence,notes`.

### 통계

| 항목 | 수 |
|---|---:|
| 도시-도시 간선(총) | **93** |
| ├ HIGH(양방향 상호선언) | 86 |
| ├ MED(상호선언+이견 or 편도-plausible) | 2 (洛陽-晋陽, 永安-漢中) |
| └ LOW(편도-only) | 5 (北海-濮陽, 寿春-許昌, 交趾-雲南, 襄陽-梓潼, 零陵-建寧) |
| RTK14-primary 간선 | 93 / 93 (전부) |
| 2개 이상 시리즈 corroborated | 2 (建業-呉·呉-会稽 → +RTK8R; RTK13 태그는 per-edge locator 부재로 제거 — 아래 방법 5) |
| 이민족 거점 인접행(도시-거점) | 11 |
| degree 0 도시 | **0** (46/46 연결) |

### 근거화된 conflict(자동 대칭화 금지 대상)

- **洛陽-晋陽 (MED):** 양 페이지가 `隣接都市`에 상호선언하나 洛陽 본문이 "晋陽とは隣接扱いされていないのか"로 in-game 인접 처리에 이견. OPENSAM-102 §6이 지목한 바로 그 표본. 대칭 간선으로 확정하지 않고 이견을 함께 보존.
- **寿春→許昌 (LOW, asym):** 寿春은 許昌을 선언하나 許昌은 寿春 미선언. 許昌 페이지 코멘트가 "寿春→許昌은 汝南 영역을 경유"라 in-game 비인접임을 시사. 편도 claim으로만 보존.
- **濮陽→北海 (LOW, asym):** 濮陽만 선언(본문 "距離が遠く" rare). 北海는 平原·下邳만 선언.
- **雲南→交趾 (LOW, asym):** 雲南만 선언(본문 "독 늪 경로 비현실적, 建寧이 실질 경로"). 交趾는 建寧 경유.
- **建寧→零陵 (LOW, asym):** 建寧만 선언(본문 "고산 장거리"). 零陵은 交趾만 선언.
- **梓潼→襄陽 (LOW, asym+artifact):** 梓潼 필드가 襄陽을 냈으나 襄陽 미선언. 사천-형주 직결은 지리적으로 비현실적이고 둘은 永安을 경유. **fetch 오추출 가능성**이 높아 재확인 대상으로 표기(대칭 간선으로 승격 금지).
- **交趾-江州(비간선):** 交趾 페이지 코멘트가 "인접선이 안 뻗어 게임상 비인접"으로 명시 → 간선 미기록(원장에 없음).

### 방법 재현성

1. 입력 도시 URL은 OPENSAM-102 coordinate ledger의 `detail_url`(46개, `鄴`=`ｷﾞｮｳ`·`下邳`=`下ヒ` 반각 slug 포함).
2. 각 페이지의 `隣接都市` 필드를 verbatim 판독. 본문의 인접 이견 prose도 함께 채집.
3. 편도/양방향을 대조해 confidence 부여. **역방향 자동 생성 안 함.**
4. `from < to`(RTK14 ordinal) 정규화로 대칭 간선 1회 기록.
5. evidence_series는 RTK14를 1차로, 실제 corroboration이 있는 간선에만 RTK8R 추가. RTK13은 seamless-map 구조 근거(§3)로만 남기고, per-edge source locator를 확보하지 못해 新野-襄陽·江陵-武陵의 corroboration 태그를 제거했다(두 간선 모두 RTK14 양방향 HIGH라 사실 손실 없음).

---

## 6. `MapJson` 필수 필드 12개 판정

runtime 로더 근거: `infra/src/main/kotlin/opensamguk/infra/seed/MapJson.kt`.
- `MapData(width, height, cities)` — `MapJson.kt:7`
- `MapCityCoord(id, name, x, y)` — `MapJson.kt:9`
- `MapCityDetail(id, name, level, region, x?, y?, populationMax, agricultureMax, commerceMax, securityMax, defenceMax, wallMax, populationInit?, …, connections)` — `MapJson.kt:11-31`

계약 §2가 말한 **도시별 12개 값**(name 제외, optional Init 6종·map-level width/height 제외)에 대한 판정:

| # | 필드(코드) | 의미 | 이 리서치의 근거 | 판정 |
|---:|---|---|---|---|
| 1 | `id` (`MapJson.kt:12`) | 도시 numeric ID | 어느 시리즈도 opensamguk 정수 ID를 주지 않음 | **[UNKNOWN]** (assignment 문제; OPENSAM-103/105 몫) |
| 2 | `level` (`:14`) | 도시 규모/등급 | RTK11 州城 lv8/郡城 lv6, RTK14 府数 등 랭크 존재하나 v1 `level` 정수 코드북 대응 미증명 | **[UNKNOWN]** (후보 근거만 존재) |
| 3 | `region` (`:15`) | 지역 정수 | RTK14 大地域/州 grouping·338 area, RTK9 州, RTK8R 6地方→州 계층 corroborate. 그러나 v1 `region` integer 의미/코드북 대응 미증명 | **[UNKNOWN]** (구조적 근거는 있음, 정수 매핑은 아님) |
| 4 | `x` (`:16`) | 좌표 X | OPENSAM-102 native-pixel `x_px`(4181 기준) 측정됨 | **PARTIAL** — 픽셀은 근거, runtime scaling/projection은 `[UNKNOWN]` |
| 5 | `y` (`:17`) | 좌표 Y | OPENSAM-102 native-pixel `y_px`(4191 기준) 측정됨 | **PARTIAL** — 동상 |
| 6 | `populationMax` (`:18`) | 인구 상한 | RTK wiki 경제치→v1 cap 환산식 없음(§102 계승) | **[UNKNOWN]** |
| 7 | `agricultureMax` (`:19`) | 농업 상한 | 동상 | **[UNKNOWN]** |
| 8 | `commerceMax` (`:20`) | 상업 상한 | 동상 | **[UNKNOWN]** |
| 9 | `securityMax` (`:21`) | 치안 상한 | 동상 | **[UNKNOWN]** |
| 10 | `defenceMax` (`:22`) | 방어 상한 | 동상 | **[UNKNOWN]** |
| 11 | `wallMax` (`:23`) | 성벽 상한 | 동상 | **[UNKNOWN]** |
| 12 | `connections` (`:30`) | 인접 도시 ID 리스트 | **RTK14 `隣接都市` 전수 수집으로 근거화(93 간선, §5)** | **EVIDENCED (RTK14-primary)** — 단 ID join(필드1)과 2인 검토 후 `List<Int>`로 확정. 편도/이견은 자동 대칭화 금지 |

**추가로 `[UNKNOWN]`인 것(위 12 외):** `MapData.width/height`(관측 canvas 4181×4191이나 v1 image provenance slot 없음), `MapCityDetail`의 6개 `*Init`(nullable override, 소스 없음). 이들도 발명 금지.

**요지:** 12개 중 **connections 1개가 EVIDENCED로 이동**했고, **x/y 2개는 PARTIAL(픽셀만)**, 나머지 9개는 `[UNKNOWN]`. connections는 이 리서치의 최대 보충 성과다.

---

## 7. 5개 이민족 거점 `parent_city`

OPENSAM-102는 5거점 페이지가 "복수 외교 선행 도시"를 나열해 `parent_city=UNKNOWN`으로 뒀다. 본 리서치는 RTK14 **PK 버전 도시 페이지의 `隣接都市`가 거점을 직접 인접으로 명시**함을 확인해, **근거 있는 후보 도시 집합**을 보충한다(단, 단일 parent는 여전히 UNKNOWN).

| 거점 | RTK14-PK 인접/연관 도시 | 외교 트리거 | parent_city |
|---|---|---|---|
| 烏桓 | 襄平 (交烏) | 交烏: 북동 3도시 중 2 보유 | **[UNKNOWN]** (복수 도시) |
| 鮮卑 | 長安, 安定 (PK 인접) | — | **[UNKNOWN]** (복수 도시) |
| 羌族 | 天水(PK), 安定, 武威, 漢中(접경) | 交羌: {安定,天水,武威} 중 2 | **[UNKNOWN]** (복수 도시) |
| 山越 | 建業, 呉, 会稽, 柴桑 (PK 인접) | 交越: {建業,呉,会稽,柴桑,建安} 중 3 | **[UNKNOWN]** (복수 도시) |
| 南蛮 | 建寧, 雲南 (PK 인접) | 交南蛮: 建寧+雲南 보유 | **[UNKNOWN]** (복수 도시) |

→ 각 거점의 **인접 후보 도시들은 근거화**됐다(CSV 하단 11행). 그러나 "정확히 어느 1개 도시가 parent인가"는 어느 시리즈도 단정하지 않으므로 `parent_city`는 `[UNKNOWN]`을 유지한다. 이는 105에 넘길 때 "후보 N개 + 2인 검토 필요" 형태의 입력이 된다.

---

## 8. OPENSAM-105 잔여 블로킹(이 리서치 이후)

§2가 105를 막은 두 근거(full adjacency, MapJson 12값)의 해소 상태:

| 105 선행 조건 | 리서치 전 | 리서치 후 |
|---|---|---|
| full adjacency | `[UNKNOWN]` | **RTK14 근거화(93 간선)** — 단 numeric ID join + 2인 독립 전사 + 편도/이견 처리 필요 |
| MapJson `connections` | `[UNKNOWN]` | **EVIDENCED(RTK14)** — ID 확정 후 `List<Int>` 작성 가능 |
| MapJson `x/y` | `[UNKNOWN]` | **PARTIAL** — 픽셀만, scaling/projection 계약 필요(OPENSAM-103) |
| MapJson `id/level/region/경제치6` | `[UNKNOWN]` | **여전히 `[UNKNOWN]`** (id assignment·level/region 코드북·경제 환산식 무근거) |
| 5거점 `parent_city` | `[UNKNOWN]` | **후보 근거화, 단일 parent는 `[UNKNOWN]`** |
| source 권리/재배포 | `RIGHTS WARN` | **`RIGHTS WARN` 불변** |

**결론:** 105를 막던 두 축 중 **adjacency는 크게 해소**됐다. 남은 하드 블로커는 **(1) numeric city ID 배정 정본(103 계약), (2) level/region 정수 코드북, (3) 경제치(populationMax 등) 환산 근거, (4) x/y runtime scaling/projection 계약, (5) 이미지/텍스트 재배포 권리(LEGAL)**다. 이들은 발명 금지 대상이며 별도 계약·승인 없이 105를 열 수 없다. 다만 adjacency 근거 확보로 105는 "전면 UNKNOWN"에서 "5개 특정 블로커"로 좁혀졌다.

---

## 9. 출처 목록 (전 웹, access 2026-07-17)

**RTK14 도시별 `隣接都市` (1차, 46개 전수 — 대표 URL):**
- 都市 목록/카운트: https://wikiwiki.jp/sangokushi14/%E9%83%BD%E5%B8%82 (46/338)
- 地理(PK 지도): https://wikiwiki.jp/sangokushi14/%E5%9C%B0%E7%90%86
- 46개 도시 페이지: `https://wikiwiki.jp/sangokushi14/<도시명>` (OPENSAM-102 coordinate ledger `detail_url` 참조; `鄴`=`ｷﾞｮｳ`, `下邳`=`下ヒ` 반각 slug)
- 異民族 index: https://wikiwiki.jp/sangokushi14/%E7%95%B0%E6%B0%91%E6%97%8F (거점 개요; 도시 페이지 인접이 더 직접적)

**시리즈 구조/로스터(corroboration):**
- ja.wikipedia 三國志シリーズ: https://ja.wikipedia.org/wiki/%E4%B8%89%E5%9C%8B%E5%BF%97%E3%82%B7%E3%83%AA%E3%83%BC%E3%82%BA (지역제→도시제 전환, RTK9/11/14 一枚맵)
- kongming.net (RTK1 states/cities): https://kongming.net/1/states-cities-guide/ ; 시리즈 아카이브 https://kongming.net/ ; 지도 https://kongming.net/map/
- koeitecmoamerica manual RTK14: https://www.koeitecmoamerica.com/manual/rtk14wpk/en/3100.html
- koeitecmoamerica manual RTK8 Remake(全体図): https://www.koeitecmoamerica.com/manual/rtk8-remake/en/6100.html
- wikiwiki sangokushi8r 都市一覧: https://wikiwiki.jp/sangokushi8r/%E9%83%BD%E5%B8%82%E4%B8%80%E8%A6%A7
- RTK11 全都市データ: https://sangokushi11.shiyo.info/toshi.html (Shift-JIS; 접속표 없음) ; 荊州 郡城: https://daisangokushi-kouryaku.hatenablog.com/entry/2018/04/08/121351
- RTK9 City/Territory(州 그룹): https://gamefaqs.gamespot.com/ps2/919160-romance-of-the-three-kingdoms-ix/faqs/29190 (WebFetch 403; 서베이 스니펫으로 州 그룹만 확인)
- RTK13 지도/이동: https://www.gamecity.ne.jp/sangokushi13/system3.html ; https://blog.ja.playstation.com/2015/10/02/20151002_sangokushi13/

**권리 참조:** OPENSAM-102 §3 `LEGAL-WIKI`(wikiwiki policies/robots) — 공개 downstream grant 미발견. robots 준수는 license가 아님.

---

## 10. 권리·provenance 경계 (RIGHTS WARN)

- 본 pass는 **텍스트 `隣接都市` 필드 판독만** 수행. **이미지/raw HTML/MHTML를 저장소에 두지 않음.** OPENSAM-102가 격리한 `PK.png`(repo 밖)만이 좌표 근거이며 본 리서치는 그것도 재열람하지 않았다.
- WIKIWIKI/Koei Tecmo 어디에서도 opensamguk 공개 재배포 허락은 미확인. 수집 텍스트는 **research metadata only**이며 repo bundle·runtime allowlist·CDN·deploy로 승격하지 않는다.
- 이 원장은 **근거 수집**이지 승인이 아니다. OPENSAM-103/105가 stable ID·2인 전사·권리 clearance·좌표 versioning을 통과하기 전 `MapJson.connections`/`region`/`x/y`에 직접 삽입 금지(§102 §10 계승).
- 확인 불가 값은 이 문서 전반에서 `[UNKNOWN]`으로 남겼다. 이는 실패 은폐가 아니라 stop condition이다.
