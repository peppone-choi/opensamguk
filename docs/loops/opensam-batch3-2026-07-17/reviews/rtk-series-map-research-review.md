# RTK 시리즈 지도 비교·보충 리서치 독립 적대적 리뷰

- **reviewer:** `reviewer-103-spec` (재배정 — lane-map-rtk-series 구현자와 독립)
- **review date:** 2026-07-17
- **artifacts under review:**
  - `docs/superpowers/research/2026-07-17-rtk-series-map-comparison.md` — SHA-256 `8379a9bf12ddfe2dbfc7f6a1c39b6348ee2737aaf20f37810e7efc635107d8c5`
  - `docs/superpowers/research/2026-07-17-rtk-series-adjacency-ledger.csv` — SHA-256 `07eaf09c569dfa4ec2dfb1c233c68c0d69fcac46c0bf865fc041d2fdcc07442e`
- **review basis:** 계약 §13 items 2–4 (`docs/superpowers/plans/2026-07-17-opensam-92-93-94-97-103-execution-contract.md`) — 근거 수집이지 발명 금지, per-edge provenance, UNKNOWN 보존, RIGHTS WARN. 교차 근거: OPENSAM-102 좌표 ledger·research, `infra/src/main/kotlin/opensamguk/infra/seed/MapJson.kt`, wikiwiki.jp/sangokushi14 5개 도시 페이지 라이브 판독.
- **FINAL VERDICT:** `cleared` (fix-required 0, note 3)

---

## 1. 리뷰 범위

CSV 구조 규칙(6열·대칭 1회 from<to·evidence-less 태그·방향/이견 보존)·통계 재계산·웹 소스 대조(≥5 간선, 플래그 간선 포함)·문서↔CSV 정합·MapJson 필드 판정표 대 실제 코드·이민족 parent_city UNKNOWN·권리(이미지/raw 미저장·per-claim provenance). 구현자 산출물 2개 파일만 검토, 편집 없음.

## 2. CSV 구조·통계 재계산 (Python 로컬 파싱, 전수)

`notes`에 콤마가 없어(전 데이터행 콤마=5, 104/104) 6열 파싱이 깨끗하다.

| 검사 | 주장(문서 §5) | 실측 | 판정 |
|---|---|---|---|
| 헤더 6열 | `from,to,evidence_series,source_url,confidence,notes` | 동일 | ✅ |
| 데이터행 | 93 city-city + 11 tribal | 93 + 11 = 104 | ✅ |
| city-city confidence | 86 HIGH / 2 MED / 5 LOW | 86 / 2 / 5 | ✅ |
| MED 목록 | 洛陽-晋陽, 永安-漢中 | 동일(L9, L85) | ✅ |
| LOW 목록 | 北海-濮陽·寿春-許昌·交趾-雲南·襄陽-梓潼·零陵-建寧 | 동일(L18,29,68,75,84) | ✅ |
| corroborated ≥2 series | 4 | 4 (建業-呉·呉-会稽=+RTK8R; 新野-襄陽·江陵-武陵=+RTK13) | ✅ |
| tribal 후보행 | 11 | 11 (烏桓1·鮮卑2·羌族2·山越4·南蛮2) | ✅ |
| degree 0 도시 | 0 (46/46) | 46/46 edges에 등장, 누락 0 | ✅ |
| from<to (102 ordinal) 1회 | 대칭 1회 기록 | 위반 0 | ✅ |
| duplicate / reverse-dup | 없음 | 0 / 0 (자동 대칭화 흔적 없음) | ✅ |
| 발명된 도시명 | 없음 | 102 ledger 46 밖 도시명 0 | ✅ |
| evidence-less 태그 | 없음 | ev/url 공란 행 0 | ✅ |
| tribal 단일 parent 미발명 | UNKNOWN 유지 | 11행 전부 notes에 `UNKNOWN` | ✅ |

방향/이견 보존: LOW 5행 전부 `ASYM`(편도) 명기, MED 2행 `DISPUTED`/`ASYM+plausible` 명기, 자동 역방향 생성 없음. 이민족 태그 `RTK14-PK`는 tribal 11행에만(PK 전용 콘텐츠), city-city행에 오용 없음. 交趾-江州 비간선(코멘트상 게임 비인접)은 원장에서 정확히 제외.

## 3. 웹 소스 spot-check (라이브 wikiwiki 판독, 5개 페이지 / 7+ 간선)

| 간선 | ledger | 소스 판독 결과 | 판정 |
|---|---|---|---|
| **洛陽-晋陽 (MED disputed, L9)** | 양 페이지 상호선언 + 洛陽 본문 이견 | 洛陽 `隣接都市` = 陳留·長安·晋陽; 본문 "晋陽とは隣接扱いされていないのか…" verbatim 존재 | ✅ 정확 |
| **襄陽-梓潼 (LOW ASYM+ARTIFACT, L75)** | 梓潼 field에 襄陽, 襄陽 미선언; "likely fetch mis-extraction; re-verify" | 梓潼 `隣接都市` = 漢中·成都·江州·永安·**襄陽**(실재), 본문 間道 "非현실적 루트"; 襄陽 `隣接都市` = 江陵·新野·永安·上庸·江夏(梓潼 없음) | ⚠️ **소스 지지됨(오추출 아님)** → note N2 |
| 上庸/江夏/新野/江陵/永安-襄陽 (5× HIGH) | 襄陽 both-list | 襄陽 페이지가 5개 전부 선언; 永安 페이지가 襄陽 역선언 | ✅ 대칭 확인 |
| **永安-漢中 (MED ASYM, L85)** | 漢中 lists 永安, 永安 미선언 | 永安 `隣接都市` = 江陵·襄陽·武陵·江州·梓潼 (漢中 **없음**) | ✅ asym 정확 |
| 成都-梓潼/江州/建寧 (3× HIGH) | both-list | 成都 `隣接都市` = 梓潼·江州·建寧 | ✅ |
| 梓潼-漢中/成都/江州/永安 (4× HIGH) | both-list | 梓潼 페이지가 4개 선언, 각 역선언 확인 | ✅ |

**플래그 간선 판정(team-lead 지정):** 襄陽-梓潼는 소스가 **지지한다**(梓潼 `隣接都市` 필드에 襄陽 실재). fetch 오추출이 아니므로 `fix-required`(제거) 조건 아님. 원장은 이를 LOW ASYM 편도로 정확히 보존(대칭 승격 안 함) — 데이터 처리 정확. note의 "likely fetch mis-extraction" 추정만 재검증으로 반증됨(→ N2).

## 4. 문서↔CSV·MapJson 정합

- 문서 §1·§5·§7의 모든 카운트(93·86/2/5·4·11·degree0=0)가 CSV 실측과 일치.
- §7 이민족표: 天水/漢中만 羌族 adjacency 행 생성(安定·武威는 交羌 트리거 집합일 뿐 adjacency 아님 → CSV에 미생성, 발명 없음). 山越 4행(建業·呉·会稽·柴桑; 建安은 交越 트리거 집합이나 adjacency 행 미생성). 5거점 전부 단일 parent UNKNOWN. 정확.
- §6 MapJson 판정표: 필드 식별·판정(UNKNOWN/PARTIAL/EVIDENCED)은 실제 코드와 일치. connections=EVIDENCED(RTK14), x/y=PARTIAL(픽셀), 나머지 9=UNKNOWN — 코드·§102와 정합. **단 per-field 라인 앵커 4개 drift**(→ N1).

## 5. 권리·provenance

- 텍스트 `隣接都市` 필드 판독만; 이미지/raw HTML 저장소 미저장(§10). RIGHTS WARN·GAME_REFERENCE 유지, 계약 §13-4 정합.
- per-claim provenance: CSV `source_url` 열이 행마다 존재, access date 2026-07-17(문서 §6·§9). 재배포/번들 미승격. ✅

## 6. Findings

### N1 — `note` (§6 MapJson per-field 라인 앵커 off-by-one)
`2026-07-17-rtk-series-map-comparison.md` §6 표(L129-133): `level(:13)`·`region(:14)`·`x(:15)`·`y(:16)`로 표기했으나 실제 `MapJson.kt`는 `name`이 :13에 있어 `level=:14`·`region=:15`·`x=:16`·`y=:17`이다. 4개 필드가 한 줄씩 밀림(문서가 :13 `name`을 건너뜀). 필드 식별·판정·범위 인용(§6 L123 "MapJson.kt:11-31", `id=:12`·`populationMax=:18`·`connections=:30`)은 정확. 비-load-bearing 인용 오차. 수정 권장: level/region/x/y 앵커를 :14/:15/:16/:17로 교정.

### N2 — `note` (L75 襄陽-梓潼 "fetch mis-extraction" 추정 반증)
`adjacency-ledger.csv:75` note "likely fetch mis-extraction; re-verify 梓潼 page"는 본 리뷰의 라이브 재검증으로 반증됨 — 梓潼 `隣接都市` 필드가 襄陽을 **실제로 나열**하며(본문 間道 "비현실적 루트" 근거 동반), 襄陽은 역선언하지 않는다. 즉 濮陽→北海·雲南→交趾·建寧→零陵과 동일한 **실재 편도 소스 claim**이지 추출 오류가 아니다. 간선 자체는 LOW ASYM으로 정확히 보존(대칭 승격 없음)돼 데이터 무결성 위반 아님 — 보수적 플래깅은 안전한 방향. 수정 권장: note에서 "likely fetch mis-extraction"를 제거하고 다른 in-source ASYM 간선과 동일 처리(재검증 완료 반영). **fix-required 아님**(소스 지지됨).

### N3 — `note` (RTK13 corroboration 태그 per-edge locator 부재)
`adjacency-ledger.csv:72,77`의 `RTK14;RTK13` 태그(新野-襄陽·江陵-武陵)는 note가 "RTK13 map discussion corroborates"로만 서술하고 CSV `source_url`은 RTK14 페이지를 가리킨다. RTK8R corroboration(L55,57)이 OPENSAM-102의 검증된 R8-W-WU/R8-W-JIANYE 표본으로 추적 가능한 것과 달리, RTK13 corroboration은 per-edge 소스 로케이터가 없다(§9 RTK13 출처는 일반 지도/시스템 페이지). 두 간선의 **1차 근거 RTK14는 견고**(HIGH sym, 본 리뷰 新野-襄陽 확인)하므로 non-blocking. 수정 권장: RTK13 per-edge 인용을 붙이거나 RTK13 태그를 내린다.

## 7. 결론

CSV 구조·통계는 전수 재계산에서 100% 일치하고(6열·93+11·86/2/5·4 corroborated·46/46 degree>0·대칭 1회·중복/역방향 0·발명 도시 0), 방향/이견은 자동 대칭화 없이 보존됐으며, 이민족 5거점은 단일 parent를 발명하지 않고 UNKNOWN을 유지한다. 라이브 웹 spot-check(5페이지/7+간선)는 원장을 강하게 뒷받침한다: 洛陽-晋陽 MED는 필드+본문까지 정확, HIGH 표본은 both-list 대칭 확인, 永安-漢中 MED asym 정확. **team-lead가 지정한 襄陽-梓潼 플래그는 소스가 지지함을 확인**(梓潼 필드에 襄陽 실재) — fetch 오추출이 아니므로 fix-required 아니고, 원장은 이를 편도로 정확히 보존했다. 발명·근거 없는 태그·UNKNOWN 훼손·권리 위반 없음.

잔여 3건은 모두 non-blocking note(라인 앵커 drift, 반증된 추정 note 문구, RTK13 corroboration 로케이터 부재)이며 데이터 무결성이나 §13 items 2–4 요건을 위반하지 않는다.

**FINAL VERDICT: `cleared`** (fix-required 0 / note 3: N1 §6 라인앵커, N2 L75 mis-extraction 추정, N3 RTK13 locator). clearance는 문서 품질 판정일 뿐 — 이 원장의 `MapJson.connections`/`region`/`x/y` runtime 삽입은 OPENSAM-103/105의 stable ID·2인 전사·권리 clearance·좌표 versioning 통과 후에만 가능하다(§10 계승).
