# 시나리오 체계 스펙 — 2026-07-18 (**v2.1**)

**변경 요지 (v2→v2.1, 인플레이스 교정):**
1. §8.2 정정: 251.1 은영전 콜라보는 정제층에 실재(4종 라벨) — 안전장치는 "부재"가 아니라 매니페스트 구동 생성(후보 풀 미포함) + 정제층 gitignore. [확정] 마커는 "IF 풀 제외" 결정에만.
2. §2.4 location-remap 12/12 재검증(che cities_1010.json 94도시 직접 대조): 剣閣/白水関/涪水関→자동, 虎牢関→사수, 小沛→패, 武威→서량, 綿竹関→면죽, 函谷関→함곡 등 전 타겟 che 실재 확정. 小沛 배치수 190→**197**.
3. tracked 문서 IP 정합: 본문 코에이 원문 라벨을 year_month+한글 통칭으로 교체 + §9 인용 예외 명기. §2.3 太守/都督 officer_level devsam 상수 대조를 파일럿 **선결 조건**으로 승격.

**변경 요지 (v1→v2, FIX-REQUIRED 반영):**
1. 생성기 입력을 raw officer-data → **정제층 `refined/rtk14-officers.json`**(id 10001–11000, 정규화 `scenarios[]`)로 전면 교체. 활성 술어를 status 기반(`status∈{君主,太守,都督,一般}`)으로 교정하고 9종 enum·officer_level 인코딩표 추가.
2. **넘버링 = 연도 기반 `scenario_3<year>` 단일 확정**(순번안 폐기). **매니페스트 시나리오 참조 = `year_month` 키**(코에이 조어 verbatim은 gitignored 정제층에만).
3. 신규 섹션 3종: **관(關) 노드 location-remap**(파일럿 게이트 "미해결 所在 0건") · **officer id 체계**(registry freeze) · **국가 코스메틱 비용 정직화**(자동 배치 vs 수작업 분리 + 기본값 자동생성).
4. 촉한멸망 개시 **263.8** 채택([확정 — 사용자, 2026-07-18]) · IF 풀에서 `251.1` 은영전 콜라보 명시 제외 · 매니페스트 `capital` 제거(`cities[0]=수도`) · 戦法 채움률 **907/1000**.
5. 후속 결정 편입: 전콘 2-파생 티켓 · 구버전 장수 이미지 폐기 티켓 · 크롭 확정치.

---

**상태: 정식 v2.1 — 적대 리뷰 2라운드 반영(BLOCKER 0), 2026-07-18 사용자 승인으로 이관.** 정본 위치: `docs/superpowers/specs/2026-07-18-scenario-system.md`.
원천 결정: `scratchpad/scenario-system-design-notes.md`. 형식 선례: `docs/superpowers/specs/2026-06-13-five-stat-rtk14-divergence.md`.
**[확정]**(사용자 결정) / **[제안]**(리뷰 대상) / **[UNKNOWN]**(미확인, 날조 금지)을 명시 구분.

> 본 v2의 모든 수치는 `refined/rtk14-officers.json`(2026-07-18 23:00 재생성본) 실측이다. 초안 v1·리뷰 노트의
> 근사치(예: "190 활성 ~196")는 재생성 이전 스냅샷이므로 본 실측치(190.1 활성 249)가 우선한다.

---

## 1. 배경 / 목표

**[확정]** devsam/core의 소수 시나리오를 오픈삼국 독자 라인업으로 **대체**한다. 정본 = RTK14 사실 시나리오
22종 + 가상 IF(백마장군의 위세 우선).

**[확정] 불변 원칙 — 엔진·패러티 코어 무변경.** 신설되는 것은 데이터·매니페스트·생성기 층뿐이다.
`scenario_*.json` positional tuple 스키마와 F1 `ScenarioImporter`/`ScenarioJson`/`ScenarioSeedRunner`는 **무변경**이
목표이자 게이트다. 5스탯 divergence의 IP·격리 규율(§9)을 계승한다.

**[확정·정직화] "공짜"의 실제 경계 — 자동 배치 vs 수작업 코스메틱.** 정제층 `scenarios[]`가 시나리오별
장수 **소속(faction)·소재(location)·신분(status)**을 담으므로 **장수 배치는 자동 파생**이다(무비용).
그러나 국가 **코스메틱**(색·초기 자금/군량·기술·규모·외교)은 원천에 없어 **수작업 큐레이션**이다. 22종 합산
약 370개 nation-block(예: 190 반동탁연합 = 활성 249명이 다수 세력으로 분산)이 수작업 대상이다. §2.2가 이
비용을 자동 기본값 + 얇은 오버라이드로 최소화한다.

---

## 2. 3층 아키텍처

**[확정]** 원천 → 정제 → 큐레이션 → 생성. 생성기 출력은 기존 `scenario_*.json` 스키마 그대로라 임포터가
무변경 소비한다.

```
① 원천 (gitignored)      ②' 정제층 (gitignored 데이터 / 빌더는 커밋)   ③ 큐레이션 (커밋)          ④ 생성 (커밋)
raw officer-data    ──▶   refined/rtk14-officers.json                manifests/<slug>.yaml   build_scenario.py
 wikiwiki 스크랩          refine_officers.py 출력 (1000명, id 10001+)   국가/색/도시/외교         정제층+매니페스트
                         scenarios[]=year_month/status/location/faction  location-remap.yaml   → scenario_3<year>.json
                         officer-id-registry.tsv (freeze)                                        │
                                                                                                 ▼
                                                            F1 ScenarioSeedRunner → JDBC seed (무변경)
```

### 2.1 정제층 — `refined/rtk14-officers.json` **[확정 스키마 — 실측]**

- **생성기 입력의 정본.** raw officer-data.json은 `refine_officers.py`의 입력일 뿐, 생성기는 접근하지 않는다.
- **five-stat 룰과 동일: 정제 데이터·raw는 gitignore, `refine_officers.py`/registry만 커밋.** RTK14 원수치는 코에이 IP.
- 구조: **길이 1000 리스트**, 원소 = officer 레코드. 레코드 실측 필드(曹操 id=10405 / 周瑜 id=10174 확인):

| 필드 | 타입 | 실측 예 | tuple 매핑 |
|---|---|---|---|
| `id` | int | 10405 | **G_PICTURE(2)** (= 초상 파일 키, §4) |
| `name_kanji` | str | 曹操 / 周瑜 / 賈詡 | → 한글 변환 → G_NAME(1). 카타카나 혼용은 정제층에서 이미 해소 |
| `name_reading` | str | ソウソウ | 메타/tie-break |
| `stats.leadership` | int | 98 | G_LEADERSHIP(5) |
| `stats.strength` | int | 72 | G_STRENGTH(6) |
| `stats.intelligence` | int | 91 | G_INTEL(7) (⚠️ 키는 `intelligence`) |
| `stats.politics` | int | 94 | G_POLITICS(14) |
| `stats.charm` | int | 96 | G_CHARM(15) |
| `birth` / `death` | int | 155 / 220 | G_BORN(9) / G_DEAD(10) |
| `ideology` / `policy` | str | 覇道 / 文事武備 | v2 (개성·정책 divergence, devsam-parity 아님) |
| `traits` / `formations` / `tactics` | str[] | [奸雄,英名,…] / [魚鱗,…] / [魏武の強,…] | v2. `tactics` 채움 **907/1000** |
| `portrait.{original,full_frame,face_crop}` | str | full_frame=`d8c1e479….png` | 초상 파이프라인(§4) |
| `scenarios` | list | (아래) | **소속 파생의 핵심** |

- **`scenarios[]` 각 row 실측 필드** (36개 시나리오 축):

| 필드 | 예 (曹操 190.1) | 의미 |
|---|---|---|
| `year_month` | `"190.1"` | **매니페스트 조인 키** (§9 IP — 라벨 아님) |
| `scenario` | `"<원문 라벨: gitignored 정제층 전용>"` | 코에이 원문 라벨(운영 산출물 유입 금지, §9) |
| `status` | `"君主"` | 신분 enum(§2.3) |
| `location` | `"陳留"` | 소재 도시(kanji) → location-remap(§2.4) |
| `faction` | `"曹操"` | 소속 세력(kanji, `null` 가능) → G_NATION |

- **원천에 없는 것(→ 시드 기본/후속)**: `相性`(affinity, G_AFFINITY) 정제층에 **없음** → tuple `0`으로 두고
  `ScenarioImporter`가 PHP RNG로 부여(임포터 기존 동작, `affinity>=900→999`). 인물관계(被親愛/被嫌悪)도
  정제층 미포함 → v2.

### 2.2 큐레이션층 — 시나리오 매니페스트 **[제안]**

정제층이 줄 수 **없는 코스메틱·외교·보정만** 사람이 채우는 얇은 파일. 시나리오당 1개.

- **포맷 = YAML** (손으로 쓰는 정책 데이터, 주석 필요). 배치: `tools/scenario/manifests/<slug>.yaml` (커밋).
- **비용 최소화 원칙 [제안]**: ① `nations[].lord`만 필수 — 나머지 코스메틱은 **자동 기본값**(색=결정적
  팔레트 자동 배정, `gold`/`rice`=규모별 기본치, `tech`/`ideology`=정제층 최빈값). ② 매니페스트는 기본값과
  **다른 값만** 오버라이드. 이론상 `nations: [{lord: 曹操}, {lord: 袁紹}, …]` + 외교만으로도 생성 가능.

```yaml
# tools/scenario/manifests/반동탁연합.yaml
code: scenario_3190          # ④ 생성 파일명/리소스 키 (§5 연도 기반)
number: 3190                 # ng_games.scenario 숫자 id
title: 동탁의 전횡과 반동탁연합   # 정식 제목 (자작, 코에이 조어 회피)
slug: 반동탁연합
year_month: "190.1"          # ← 정제층 scenarios[].year_month 조인 키 (원문 라벨 금지)
startYear: 190
map: che
life: 1
fiction: 0
const: { defaultMaxGeneral: 600 }

nations:                     # lord만 필수. 나머지 생략 시 자동 기본값.
  - lord: 董卓               # 정제층 name_kanji/faction 값. 이 값=faction 인 활성 장수를 자동 편입
    name: 동탁               # 국호/표시명(KO). 생략 시 lord 한글 변환
    color: "#8B0000"        # 생략 시 팔레트 자동 배정
    gold: 12000             # 생략 시 scale 기본치
    rice: 12000
    tech: 1500
    ideology: 명가
    scale: 8                # N_SCALE
    cities: [낙양, 장안, 홍농]  # cities[0]=수도(임포터 updateCapitals와 정합). 생략 시 소속 장수 소재 합집합
  - lord: 袁紹
    name: 원소

diplomacy:                   # 생략 시 전원 중립
  - [董卓, 袁紹, 개전]

overrides:                   # 파생이 틀릴 때만
  generals:
    - name: 조운
      force_nation: 공손찬
      force_city: 계
  exclude: []
```

- **매니페스트에서 제거된 것**: `capital`(→ `cities[0]` 규약), `source`(→ `year_month`), `appearances`(→ §7 열린 질문 확정 후).

### 2.3 활성 장수 필터 · 身分 enum · officer_level 인코딩 **[확정 술어 + 제안 인코딩]**

- **[확정] 활성 술어 = `status ∈ {君主, 太守, 都督, 一般}`.** 정제층은 등장 여부를 **status로** 인코딩하므로
  (`未登場` = 미등장) v1의 `登場 <= startYear` 비교는 status로 흡수된다 — 별도 등장연도 필드 불요.
- **[확정 — 실측] 身分 enum 9종** (전 시나리오 row 합산 빈도):

| status | 빈도(전역) | v1 처리 | officer_level 매핑 [제안] |
|---|---|---|---|
| `君主` | 455 | **활성**·국가 군주 | **12** (수뇌; devsam `chiefLevel..12` 확인) |
| `太守` | 967 | **활성**·태수 | [UNKNOWN] devsam 상수 대조 |
| `都督` | 134 | **활성**·도독(周瑜·陸遜류) | [UNKNOWN] devsam 상수 대조 |
| `一般` | 10059 | **활성**·일반 | 0 (임포터가 `officer_level==0`이면 나이 기준 재산정) |
| `在野` | 702 | **재야 풀**(nation_id 0) | 0 |
| `未登場` | 10738 | **제외**(§7 등장 예약 열린 질문) | — |
| `未発見` | 3815 | **제외**(탐색 발견 미구현) | — |
| `死亡` | 9067 | **제외** | — |
| `None`(status 없음) | 65 | **[UNKNOWN] 제외** + 파일럿 리포트 명시 | — |

> ⚠️ 리뷰 노트의 enum(7종)에는 `都督`·`None`이 누락돼 있었다. 실측은 9종. `都督`은 임명된 군직이라 활성
> 편입하되 officer_level은 UNKNOWN으로 두고 devsam 상수 대조 항목으로 남긴다(임의 배정 금지).
>
> **[선결 조건]** `太守`(967)·`都督`(134)의 officer_level devsam 상수 대조는 **파일럿(T4) 선결 조건**이다 —
> 미해결이면 해당 장수의 tuple 관직값(G_OFFICER_LEVEL)을 emit할 수 없어 생성 자체가 막힌다. T1/T3에서 확정.

- **[확정] faction 처리**: `faction == null`(활성인데 세력 없음, 드묾) → 재야(0)로 강등하고 파일럿 리포트에
  기록. `在野`는 status로 이미 재야이므로 faction 무시.
- **[확정] 국가 군주 판정**: `status == 君主 AND faction == name_kanji` 인 장수가 그 세력의 군주.
- **[제안] 활성 규모(실측, 참고)**: 184.2=137 / 190.1=249 / 200.1=304 / 219.7=370 / 263.8=167.

### 2.4 관(關) 노드 리매핑 — `location-remap.yaml` **[제안·신규]**

RTK14 `location` 중 che 94도시에 등가물이 없는 **관·특수 노드**가 있어 고정 도시 사전만으로는 미해결이
남는다. 큐레이션층에 **공용 리매핑 1개**를 둔다(시나리오 무관, 전역 공유).

- **[확정 — 실측] 리매핑 대상 노드**(활성 row 기준 배치 수): 小沛 **197** · 武威 171 · 陽平関 73 · 潼関 63 ·
  函谷関 62 · 綿竹関 61 · 虎牢関 58 · 白水関 51 · 涪水関 50 · 武関 44 · 剣閣 43 · 壺関 18 (총 12종).
- **[확정 — 실측] 리매핑 사전 (12/12 che 실재 확정).** che `cities_1010.json` 94도시 목록과 직접 대조. 12개
  타겟 전부 che에 존재(장안·한중·함곡·사수·자동·면죽·홍농·호관·패·서량). `tools/scenario/location-remap.yaml`:

```yaml
# JP 관/특수 노드 → che 도시(KO). 일반 도시는 city_map.json(코드 내 kanji→KO 사전) 처리.
# 우변은 전부 che 94도시 실재 확인 완료 (2026-07-18, cities_1010.json 대조).
潼関: 장안       # 동관은 che 미존재 → 최근접 장안
陽平関: 한중
函谷関: 함곡       # 함곡관 노드 = che '함곡'
虎牢関: 사수       # che는 '사수'(사수관 아님)
剣閣: 자동         # '재동' 아님 — che는 '자동'
綿竹関: 면죽       # che에 '면죽' 존재
白水関: 자동
涪水関: 자동
武関: 홍농
壺関: 호관
小沛: 패           # 소패 che 미존재 → '패'(대안: 하비)
武威: 서량         # 무위 che 미존재 → '서량'(대안: 천수)
```

- **[게이트] 파일럿 합격 조건에 "미해결 所在 0건" 추가**(§6.1). 매핑 없는 location은 UNKNOWN으로 실패
  처리, 사전에 등재 후 통과. (위 12종 외 신규 location 출현 시 동일 게이트로 포착.)

### 2.5 생성층 — `tools/scenario/build_scenario.py` **[제안]**

- **입력**: 매니페스트 + 정제층 `rtk14-officers.json` + `location-remap.yaml` + `city_map.json`.
- **출력**: `scenario_3<year>.json` — 기존 tuple 스키마. `ScenarioImporter` 무변경 소비.
- **변환 파이프라인**:
  1. 매니페스트 `year_month`로 정제층 각 officer의 `scenarios[]` row 조인.
  2. 활성 술어(§2.3) 필터. `在野`→재야 풀, 미등장/미발견/사망/None→제외.
  3. `faction`→세력 그룹핑(매니페스트 `lord`와 매칭). `status`→officer_level(§2.3 표).
  4. `name_kanji`→한글(§7-2 사전), `location`→한글(location-remap→city_map).
  5. stats/birth/death→tuple 인덱스(§2.1 표). affinity=0.
  6. 국가 tuple 조립: 매니페스트 코스메틱(+자동 기본값) + 소속 장수. `cities` 생략 시 소재 합집합, `cities[0]`=수도.
- **결정성**: id·이름·거리 정렬 tie-break으로 같은 입력→byte-동일 출력(refine·5스탯 빌더와 동일 규율).

---

## 3. 데이터 매핑 상세 (참조) **[확정 — 코드 실측]**

tuple 인덱스는 `infra/.../seed/ScenarioJson.kt` 상수와 1:1.

**General tuple** `[상성0, 이름1, 아이콘2, 세력3, 도시4, 통5, 무6, 지7, 관직8, 생9, 몰10, 성향11, 특12, 대사13,
정치14, 매력15]`. 14/15는 5스탯 divergence 기존 슬롯 — 충돌 없음, 규약 계승. 임포터 `decodeGeneral`이
누락 인덱스를 안전 기본값 처리(politics/charm 미제공 시 50, nationId 토큰=이름/id/`0`/`재야`).

**Nation tuple** `[이름0, 색1, 금2, 쌀3, 국호4, 기술5, 성향6, 규모7, 도시목록8]`.

---

## 4. Officer id 체계 · 초상 파이프라인 **[확정 — 실측·신규]**

- **id 밴드 = 10001–11000** (1000명 연속, 중복 없음). 레거시 아이콘 풀(0–9xxx)과 disjoint.
- **결정적 배정**: `refine_officers.py`가 `(name_kanji 유니코드 코드포인트, name_reading, portrait.full_frame)`
  3차 키로 정렬 후 10001부터 순번. **`officer-id-registry.tsv` = 라이브 컷오버 시 freeze**(go-live 후 영구,
  재배정 금지). 헤더: `id / name_kanji / reading / face_crop_file / full_frame_file / original_file`.
- **tuple 연결**: `id` → G_PICTURE(2). 서빙 초상 사본 = `<id>.png`(원본 해시 파일명과 분리).
- **초상 실측**: `portrait.original`=`<64hex>.bin`, `full_frame`/`face_crop`=`<16hex>.png`.
- **크롭 확정치**(설계노트): 세로형 C = ×2.1, 아이콘 L = ×2.0 (96×96). OPENSAM-97(전체 프레임 축소) 후속.

---

## 5. 시나리오 코드 / 넘버링 **[확정 — 연도 기반 단일안]**

- **`scenario_3<year>`** — 3xxx 밴드(빈 것 확인) + 개시연도(사실 22종 연도 유일). 예: 반동탁연합=`3190`,
  관도=`3200`, 한중왕 유비=`3219`, 촉한멸망=`3263`. 동년 복수 시 월 접미(현 22종은 연도 유일 → 불필요).
- **가상 IF = `3<year>` 동일 규칙 + 충돌 시 뒤 1자리 변형**: 백마장군의 위세(199.7)=`3199`.
- **회피**: `0–2`(공백)·`900–914`·`1010–1120`(devsam 역사)·**`2010–2904`(devsam 가상모드, 대량 점유)**·기존
  `2xxx` 전체. → v1의 순번안(3001–3022) **폐기**.

---

## 6. 검증 계획 **[확정 순서 + 제안 기준]**

**[확정]** 파일럿 3종(190·200·219) → 시드 E2E → 잔여 19종 일괄.

### 6.1 파일럿 3종 (3190 / 3200 / 3219)
- **[제안] 합격 기준:**
  1. **장수 수**: 국가별 장수 수 = 정제층 활성 필터 결과와 ±0 (190.1=249, 200.1=304, 219.7=370 기준).
  2. **소속 스팟체크**: 대표 5~10명(조조·원소·유비·손권·여포·주유)의 소속·소재가 정제층 row와 일치.
  3. **미해결 所在 0건** (§2.4): location-remap+city_map 미등재 location = 0.
  4. **이름 변환 무손실**: `name_kanji`→한글 fallback(미매칭) = 0 또는 전량 리포트(five-stat `fallback` 방식, UNKNOWN 명시).
  5. **스키마 유효성**: 출력 tuple이 `ScenarioJson.decode` 통과(파싱 예외 0).

### 6.2 시드 E2E (fresh DB)
- **[제안] 절차**: `SCENARIO_CODE=scenario_3190 SCENARIO_DIR=<gen> SCENARIO_SEED_ENABLED=true` + fresh
  PostgreSQL → `ScenarioSeedRunner.ensureSeeded` → row count 로그. `ScenarioImporterIT`(Testcontainers) 재사용.
- **[제안] 합격 기준:**
  1. **임포터 무변경**: `ScenarioImporter.kt`/`ScenarioJson.kt`/`ScenarioSeedRunner.kt` diff = 0.
  2. **멱등성**: 2회차는 `world_state>0`로 skip. 같은 입력→같은 row.
  3. **정합성**: nation/city/general/general_turn/diplomacy count가 매니페스트·파생과 일치, 고아 참조 0.

### 6.3 잔여 19종 일괄
- 파일럿 확정 생성기·사전으로 19 매니페스트→19 JSON 일괄. **[제안] 기준**: 전 19종 스키마 유효 + fallback·
  미해결 所在 전량 리포트 + 시나리오당 대표 3~5명 소속 스팟체크. 시드 E2E는 대표 3종만 재확인(스키마 동형).

---

## 7. 미확인 / 열린 질문 **[UNKNOWN — 날조 금지]**

1. **[UNKNOWN — PHP 오라클] 등장 예약(개시 후 후년 등장) 메커니즘.** 정제층 `未登場`(전역 10738 row)을
   시드 편입하려면 devsam이 "N년 후 등장" 예약 이벤트를 지원해야 한다. **확인 전 v1 = 미등장 제외**(활성만).
   `overrides.appearances`는 확정 후 추가. → 파일럿 착수 전 `legacy/devsam-core` 오라클 확인.
2. **[열린 질문] name_kanji→한글 사전 (`city_map.json`과 별개).** 정제층 `name_kanji`(로스터 정본, 카타카나
   해소됨)를 소스로 한자 독음 변환 + 동명이인 지문 배정(five-stat greedy 1:1 재사용). **파일럿 1차 리스크.**
3. **[해소 → §2.4] location-remap 매핑 확정** — 관 12종 → che 도시 12/12 실재 확정(cities_1010.json 대조).
   신규 location 출현만 파일럿 게이트("미해결 所在 0건")로 포착.
4. **[UNKNOWN] `太守`·`都督`·`None`의 officer_level.** devsam 상수 대조 필요(§2.3). 임의 배정 금지.
5. **[제안] 능력치 단일 소스.** 신 시나리오는 정제층이 통무지정매 전부 보유 → **정제층 단일 소스**(5스탯
   빌더 중복 제거). 통무지가 devsam 패러티 시나리오와 다를 수 있으나 신 시나리오는 오라클 없음 =
   RTK14 원수치 = divergence, 허용.

---

## 8. 시나리오 라인업

### 8.1 사실 22종 **[확정]** (제목=자작 이중 사건명 / year_month=정제층 조인 키 / active=실측)

| # | year_month | 정식 제목 | 슬러그 | code | active |
|---|---|---|---|---|---|
| 1 | 184.2 | 황건의 난과 도원결의 | 황건의 난 | 3184 | 137 |
| 2 | 188.6 | 사방의 반란과 대장군 하진 | 한조동란 | 3188 | 145 |
| 3 | 190.1 | 동탁의 전횡과 반동탁연합 | 반동탁연합 | 3190 | 249 |
| 4 | 191.10 | 연합의 와해와 두 원씨의 반목 | 연합와해 | 3191 | 248 |
| 5 | 194.6 | 여포의 습격과 소패왕의 등장 | 군웅할거 | 3194 | 293 |
| 6 | 196.6 | 헌제의 환도와 조조의 허창 천도 | 허창천도 | 3196 | 293 |
| 7 | 198.3 | 원술의 참칭과 여포 토벌전 | 여포토벌전 | 3198 | 319 |
| 8 | 200.1 | 원소의 남하와 관도대전 | 관도대전 | 3200 | 304 |
| 9 | 202.6 | 원소의 죽음과 하북의 분열 | 하북쟁란 | 3202 | 322 |
| 10 | 207.9 | 삼고초려와 와룡의 출사 | 삼고초려 | 3207 | 348 |
| 11 | 208.10 | 손유동맹과 적벽대전 | 적벽대전 | 3208 | 341 |
| 12 | 211.7 | 마초의 거병과 동관전투 | 동관전투 | 3211 | 357 |
| 13 | 215.8 | 유비의 익주 평정과 합비전투 | 합비전투 | 3215 | 353 |
| 14 | 217.7 | 한중쟁탈전과 정군산의 대치 | 한중쟁탈전 | 3217 | 365 |
| 15 | 219.7 | 한중왕 유비와 관우의 북상 | 한중왕 유비 | 3219 | 370 |
| 16 | 221.7 | 촉한의 건국과 이릉전투 | 이릉전투 | 3221 | 357 |
| 17 | 225.7 | 남중의 반란과 제갈량의 남정 | 남만정벌 | 3225 | 334 |
| 18 | 227.2 | 출사표와 제1차 북벌 | 출사표 | 3227 | 322 |
| 19 | 234.2 | 제5차 북벌과 오장원의 대진 | 오장원전투 | 3234 | 278 |
| 20 | 238.1 | 공손연의 자립과 요동정벌 | 요동정벌 | 3238 | 272 |
| 21 | 249.1 | 조상의 전횡과 고평릉사변 | 고평릉사변 | 3249 | 236 |
| 22 | 263.8¹ | 사마소의 야망과 촉한 정벌 | 촉한멸망 | 3263 | 167 |

> ¹ **[확정 — 사용자, 2026-07-18]** 촉한멸망 개시월 = **263.8**. 정제층 실측 라벨이 `263年8月 蜀漢の滅亡`
> 이고, 설계노트 카탈로그의 `263.5`는 원천과 불일치하는 전사 오기다(개시월은 원천 축을 따른다).
>
> 22종 year_month 라벨은 정제층에서 전수 확인(존재·active 위 표 실측). 코에이 원문 라벨은 정제층
> (gitignored) 내부에만 존재하며 매니페스트는 year_month만 참조(§9).

### 8.2 가상 IF **[확정 우선순위 + 제안 풀]**

- **v1 제작 = 백마장군의 위세 1개만**(가상 1호, 사용자 명시). 정제층 슬롯 `199.7`(하북의 공손찬 IF) 확인 →
  **사실 22와 동일 파이프라인**(매니페스트 `year_month: "199.7"`, 제목만 자작). 공손찬 역경 제패, 유비·조운 진영.
- 개변 IF 후보 풀(제안, 시기순): 창천과 황천(184+)/천하를 탐한 여포(197)/**백마장군의 위세(199★)**/의대조의
  성공(200)/소패왕은 죽지 않는다(200)/원가의 천하(201)/업성의 후계 다툼(209)/장강을 건넌 조조(209)/천하이분의
  꿈(211)/서량의 비상(212)/불패의 미염공(219)/지지 않는 별(234). 순수 가상: 도원, 갈라서다. **전부 자작명.**
- **[확정] IF 후보 풀에서 제외**: 251.1 은영전 콜라보(라인하르트·양 웬리 등장, 별도 라이선스 IP). ⚠️ 정정 —
  정제층에는 251.1 라벨이 **4종 실재**한다(은영전 콜라보 포함, 각 1000행). 안전장치는 "정제층 부재"가 아니라
  **매니페스트 구동 생성**(시나리오는 매니페스트 명시 year_month만 생성 → 251.1은 후보 풀에 없어 미생성) +
  **정제층 gitignore**다. 코에이 조어·타 IP 라벨은 tracked 산출물에 유입되지 않는다.
- 집결형(v3+, 로스터 쿼리): 군웅총집결/맹장전/지장전/성씨대전 — 별도 스펙.
- 우선순위: 사실 22 → 백마장군의 위세 → 개변 IF → 집결형 → 시즌 계보.

---

## 9. IP 원칙 / 격리 **[확정 — 5스탯 선례 계승 + 누수 차단]**

- **원천·정제 미커밋**: raw officer-data + `refined/rtk14-officers.json`(RTK14 원수치·초상·원문 라벨) = 코에이 IP →
  gitignore. 커밋되는 것은 `tools/scenario/`(refine_officers.py·build_scenario.py·매니페스트·location-remap·
  city_map·officer-id-registry.tsv).
- **[누수 차단] 코에이 조어 격리**: 매니페스트(tracked)는 시나리오를 **`year_month`("234.2")로만** 참조한다.
  원문 라벨(코에이 조어)의 조인은 **gitignored 정제층 내부에서만**. tracked 운영 산출물(매니페스트·코드·
  생성물)에 원문 유입 금지.
- **[인용 예외] 본 스펙 문서**: 이관 시 tracked이나, 스키마 예시·시나리오 식별 목적의 원문 라벨은 본문에서
  전부 year_month 키 + 한글 통칭 + `<원문 라벨: gitignored 정제층 전용>` 플레이스홀더로 대체했다. 운영
  산출물 금지 규칙은 그대로 적용된다(자체 모순 없음).
- **생성물 취급**: `scenario_3<year>.json` 완성본(RTK14 원수치 포함)도 **gitignored 배포 디렉터리**(`SCENARIO_DIR`).
  5스탯 prod 사이드로드와 동일(source secret + checkout 조합 배포).
- **제목·용어**: 역사 용어·고사·지명·격문은 공용. **코에이 고유 조어 회피**(추풍오장원·정시정변·지재천리 →
  유사 사건명). 제목 22종·가상명 전부 **자작 확인**.
- **격리 불변**: leadership/strength/intel의 getStatValue·RNG draw·로그·골든은 이 작업으로 **불변**.

---

## 10. 티켓 분해 **[제안]** (반나절 이하·PR 1개 단위)

| # | 티켓 | 산출물 | 게이트 |
|---|---|---|---|
| T0 | PHP 오라클: 등장 예약 메커니즘 확인 | §7-1 판정 | 스코프(미등장 편입 여부) 확정 |
| T1 | name_kanji→한글 사전 + city_map + location-remap.yaml | 3개 사전 + 미해결 리포트 | 전수 스캔, 미해결 0 목표 |
| T2 | 매니페스트 스키마 확정 + 자동 기본값 규칙 | 스키마 + 파일럿 3 매니페스트 | 스키마 리뷰 승인 |
| T3 | 생성기 스캐폴드 `build_scenario.py` | 정제층+매니페스트 → tuple JSON, 결정적 | 파일럿 3 스키마 유효 |
| T4 | 파일럿 3종 생성·검증 | scenario_3190/3200/3219.json | §6.1 5기준(수·소속·所在·이름·스키마) |
| T5 | 시드 E2E | 파일럿 코드로 `ScenarioImporter` IT | §6.2 3기준(무변경·멱등·정합) |
| T6 | 잔여 19종 매니페스트 + 일괄 생성 | 19 매니페스트 + 19 JSON | §6.3 |
| T7 | 백마장군의 위세(가상 1호) | manifest(`year_month: "199.7"`) + JSON | 파일럿 동일 게이트 |
| T8 | 배포 사이드로드 배선 | `SCENARIO_DIR` 배포 경로 + gitignore 확인 | 5스탯 사이드로드 패턴 재사용 |

**후속 결정 편입(설계노트, 별도 트랙):**
- **전콘 2-파생 티켓** — 초상 업로드 1장 + 크롭 조정 UI(자동 감지는 초기 제안만) + 고급 두-버전 업로드.
  OPENSAM-91/92 확장.
- **구버전 장수 이미지 폐기 티켓** — `icons/` 4167파일 정리. 트리거 = 시나리오 완성 + 라이브 전환. **실행 전
  사용자 목록 재확인**(파기는 사용자 승인).

---

## 11. 불변 (frozen — 변경 시 승인)

- `scenario_*.json` positional tuple 스키마 (G_*/N_* 인덱스).
- `ScenarioImporter.kt` / `ScenarioJson.kt` / `ScenarioSeedRunner.kt` (임포터 무변경 = 게이트).
- `officer-id-registry.tsv` (라이브 컷오버 후 id freeze, 재배정 금지).
- 코에이 IP 미커밋 규칙 (raw·정제층·생성물 gitignore, tracked 층 원문 라벨 금지).
- five-stat divergence 격리 규율 (통무지 불변, 패러티 코어 사정권 밖).
- 사실 22종 제목·슬러그·code(§8.1) / 가상 우선순위(§8.2, 백마장군의 위세 1호).
