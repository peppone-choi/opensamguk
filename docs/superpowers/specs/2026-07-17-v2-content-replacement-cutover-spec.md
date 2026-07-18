# v2 콘텐츠 대체 sanctioned-divergence cutover 스펙 — 2026-07-17

- **status:** `PROPOSED`
- **ticket:** OPENSAM-103 (mirror GitHub #246)
- **decision anchor:** `ADR-LITE-010` (`.ai/decisions.md`)
- **scope:** documentation only — no builder, no scraper, no validator, no runtime code, no `ScenarioJson`/`MapJson`/`PhysicalPlace` 생성·변경, no default scenario/seed 변경, no Jira/GitHub write, no commit/deploy
- **evidence inputs:** `.ai/decisions.md` (ADR-LITE-010/011), `docs/superpowers/research/2026-07-17-opensam-102-rtk14-rtk8r-map-source-coverage.md`, `docs/superpowers/research/2026-07-17-opensam-102-map-coordinate-ledger.csv`
- **precedent style:** `docs/superpowers/specs/2026-06-13-five-stat-rtk14-divergence.md` (기존 sanctioned-divergence 스펙)

> 이 문서는 **제안(PROPOSED)** 이다. 문서가 존재한다는 것 자체가 승인이 아니다. 아래 계약 전체가 `§1`의 정확한 규칙대로 사용자에 의해 `APPROVED`로 전환되기 전에는 어떤 builder·runtime·default scenario·데이터 아티팩트도 만들거나 바꾸지 않는다.

## 1. Status와 승인 규칙 (user-only APPROVED)

1. 이 스펙의 status는 `PROPOSED`로 시작한다.
2. **오직 사용자만** status를 `APPROVED`로 전환할 수 있다. 에이전트·독립 reviewer의 clearance는 **문서 품질 판정**일 뿐 승인이 아니다. reviewer가 required anchor와 105 defer를 모두 통과시켜도 status는 `PROPOSED`로 남는다.
3. `APPROVED`로 전환되기 전에는 §5~§11의 어떤 절차도 실행 authorization을 갖지 않는다. `APPROVED` 이후에도 각 단계는 자체 precondition·abort·rollback 게이트(§10)를 따로 통과해야 한다. `APPROVED`가 여는 것은 v2 대체 콘텐츠 **전 시나리오 집합의 cutover 파이프라인 착수**(§9)이며 — 특정 시나리오를 privileged pilot로 두거나 시나리오 간 승인 게이트를 두지 않는다 — builder·CDN·deploy·rights release는 함께 열지 않는다.
4. 이 문서는 `.ai/decisions.md`의 `ADR-LITE-010`(사용자 승인된 방향 선언)을 **구현 계약 수준으로 형식화**한 것이다. ADR-LITE-010의 방향 승인이 곧 이 cutover 계약의 승인은 아니다 — 방향(“대체한다”)은 승인됐고, 여기서 규정하는 identity/projection/cutover/rollback **메커니즘**의 승인은 별개다.

## 2. ADR-LITE-010 — v2 콘텐츠 정체성: RTK 종합으로 devsam 콘텐츠 대체

`.ai/decisions.md`에 approved로 기록된 결정을 스펙 앵커로 재수록한다. 원본이 정본이며 아래는 이 cutover 계약이 참조하는 요약이다.

- **Date:** 2026-07-17
- **ADR status:** approved (방향 선언) — 단, 본 cutover 스펙 자체는 `PROPOSED`(§1).
- **Context:** 패러티 P0–P6 폐쇄로 엔진 확보 완료. OPENSAM-102 소스 실증(2026-07-17): RTK14/RTK8R wiki·공식 매뉴얼의 도시·지도·세력 계층, 그리고 승인된 `PK.png`에서 추출한 46 city + 55 small-base = **101** native-pixel 좌표. 사용자 방향 선언: “슬슬 기존 devsam(체섭)의 그늘에서 벗어나야”.
- **Decision:** v2의 콘텐츠 정체성은 RTK 시리즈 종합 데이터(맵·시나리오·세력·장수 스탯·초상)로 devsam(체섭) 콘텐츠를 **대체**하는 것이다 — 신규 시나리오 병행이 아니다. devsam 시나리오는 프로덕트 콘텐츠에서 은퇴하고, 패러티 골든 게이트의 **동결 회귀 픽스처**로 강등·보존한다(M-config frozen-baseline과 연계). 엔진 시맨틱스(RNG·반올림·로그·전투·AI)와 골든 게이트 자체는 불변.
- **Alternatives:** RTK 콘텐츠를 신규 시나리오로 병행 추가 (기각 — 사용자: “신규 시나리오보단 대체”). devsam 콘텐츠 유지 (기각 — 방향 선언).
- **Consequences:** v2 맵/시나리오 갈래는 “대체 트랙”으로 재프레임(에픽 OPENSAM-101). OPENSAM-96(초상 소싱)이 선발대. Koei-IP 우려는 사용자 결정으로 현 시점 보류. **본 스펙의 결과:** 대체가 엔진·골든·v1 패러티를 건드리지 않고 오직 **content layer**에서만 일어나도록 identity·projection·cutover·rollback 경계를 고정한다.
- **관련:** `ADR-LITE-011`(에셋 AI 생성 정책 + 비주얼 현대화, 에픽 OPENSAM-112) — 미매칭·신규 캐릭터·배경 asset은 AI 생성으로 보충하되 rights·style 경계는 본 스펙의 `RIGHTS WARN`/UNKNOWN 규칙을 약화하지 않는다.

## 3. Divergence 경계 — content layer ONLY, engine semantics immutable

이 대체는 **content layer**에만 허용된 sanctioned divergence다. 아래 두 계층은 절대 섞이지 않는다.

### 3.1 허용 — content layer (divergence 대상)

- 시나리오가 참조하는 도시/거점 집합, 이름, 좌표 presentation, 세력 배치, 장수 roster의 **데이터 콘텐츠**.
- v2 `PhysicalPlace` projection이 소비하는 stable identity와 versioned 매핑(§5·§6).
- presentation asset(맵 이미지·초상)의 **후보** 정의 — 단, rights가 clear될 때까지 bundle/runtime 승격 없음(§8).

### 3.2 불변 — engine semantics (divergence 금지, immutable)

다음은 콘텐츠 대체와 무관하게 byte 단위로 동결된다. 콘텐츠 교체가 이들 중 하나라도 바꾸면 그 변경은 이 스펙 위반이다.

- **RNG:** `RandUtil(LiteHashDrbg(seed))`의 draw 순서·횟수·method args.
- **Rounding:** `PhpRound`(half-away-from-zero, `Util::round`/`setRound`), `Util::toInt`/`intdiv` truncate, damage clamp `ceil`.
- **Korean log bytes:** 조사(Josa)·color/tag markup·prefix·진격/퇴각/패퇴/전멸/분쟁/정복 등 로그 문자열. 로그 순서 = 실행 순서.
- **Side-effect order & insertion order:** jsonb/conflict-map/trigger-caller key의 `LinkedHashMap` 삽입 순서, PHP 8.0+ stable sort.
- **Flush 경계:** `ChangeRecorder` created/dirty/deleted delta → `JdbcFlushExecutor` JDBC batch. one-daemon-write 규칙(엔진 데몬은 `EntityManager` write 금지) 불변.

> 요약: **엔진은 오라클, 콘텐츠는 divergence.** 콘텐츠 대체는 “엔진이 소비하는 데이터”만 바꾸고, “엔진이 데이터를 처리하는 방식”은 절대 바꾸지 않는다.

## 4. devsam fixtures/goldens — byte baseline freeze

1. devsam 파생 fixtures와 goldens(`logic/src/test/resources/golden/**`, scenario_1010 기반 캡처 등)는 **byte baseline으로 동결**된다. 대체 트랙은 이들을 재생성·수정하지 않는다.
2. 새 v2 콘텐츠는 **기존 golden을 다시 쓰지 않는다.** 대체가 만들어내는 회귀 검증은 devsam-baseline과 **별도의** 새 픽스처/관측으로 두며, ADR-LITE-010대로 devsam 시나리오는 은퇴하되 **동결 회귀 픽스처**로 보존된다(M-config frozen-baseline 메커니즘).
3. devsam fixtures/goldens의 어떤 byte 변경도 **별도 사용자 승인**을 요구한다. 이 스펙의 `APPROVED`가 golden 변경을 함께 승인하지 않는다.
4. 파생 규칙: 대체 콘텐츠의 값이 devsam golden과 충돌하면, golden이 아니라 콘텐츠를 조정하거나 대체를 중단(abort, §10)한다. golden을 약화·재작성하지 않는다(CLAUDE.md parity discipline 5·§3.2).

## 5. v1 `ScenarioJson`/`MapJson` ↔ v2 `PhysicalPlace` projection 계약

### 5.1 사실(현재 코드) — v1 side

`[사실]` v1 loader 모델(현재):

- `MapJson.MapCityCoord(id: Int, name, x: Double, y: Double)` — `infra/.../seed/MapJson.kt:9`.
- `MapJson.MapCityDetail(id: Int, name, level, region: Int, x: Double?, y: Double?, populationMax..wallMax, populationInit..wallInit, connections: List<Int>)` — `MapJson.kt:11-31`.
- `MapJson.loadMap` / `loadCityDetails` — classpath `map/<code>.json`을 `MetaJson`(insertion-order 보존 codec)으로 디코드 — `MapJson.kt:44-97`. `loadMapCities`가 `x: Double? → Int`로 truncate — `ScenarioJson.kt:216-217`.
- `ScenarioJson.ScenarioCity(id: Int, name, level, region: Int, nationId, *Max, x: Int?, y: Int?, *Init)` — `ScenarioJson.kt:372-393`. 주석대로 `x`/`y`는 client-display 좌표이며 `city` 테이블에 **미영속**(`ScenarioJson.kt:369-371`).
- `ScenarioJson.ScenarioGeneral`은 14+2 슬롯 positional tuple을 디코드(정치/매력은 인덱스 14/15, five-stat divergence) — `ScenarioJson.kt:25-44,143-170`.
- `Scenario.map: Map<String, Any?>`은 untyped metadata container일 뿐 city geometry/edge 계약이 아니다 — `ScenarioJson.kt:287`.

핵심 사실: **v1 identity는 `Int` id다.** `MapCityDetail.connections`도 `List<Int>`.

### 5.2 v2 side (projection target — 코드 아님, 설계 스펙 모델)

`[사실]` `PhysicalPlace`/`RouteCorridor`/`EvidenceRef`는 **Kotlin 코드가 아니라** v2 설계 스펙 모델이다 — 정본은 `docs/superpowers/specs/2026-07-13-v2-historical-city-army-terrain-design.md`. 이 스펙은 해당 모델을 **생성·구현하지 않는다.** projection 계약의 방향만 규정한다.

### 5.3 projection 계약

| 항목 | 규칙 |
|---|---|
| **방향** | 단방향 forward projection: `v1 (ScenarioJson/MapJson, Int id)` → `v2 (PhysicalPlace, stable string id)`. 역방향 자동 생성 없음. v1은 동결 baseline으로 남고, v2가 대체 콘텐츠를 소비한다. |
| **버전** | projection은 반드시 **versioned**다. 입력 이미지 version(`PK.png?rev=…` 제거 후 canonical + SHA-256), stable-id map version, projection ruleset version을 각 산출물에 기록한다. version 없는 projection은 실행 금지. |
| **unknown 처리** | 소스에 없는 값은 `[UNKNOWN]`으로 **보존**한다. placeholder/default/역산으로 채우지 않는다. unknown이 필수 필드면 그 레코드는 projection 대상에서 제외(fail-closed)하며 관측 로그에 UNKNOWN 사유를 남긴다. |
| **좌표** | native-pixel은 presentation geometry일 뿐 world coordinate가 아니다(§7). projection은 image version·scaling/crop 계약이 §10 precondition으로 확정되기 전 좌표를 runtime으로 승격하지 않는다. `loadMapCities`의 `Double→Int` truncate(`ScenarioJson.kt:216-217`)는 v1 rounding 사실로 보존하고 임의 변경하지 않는다. |
| **경제/city type** | `MapJson`의 6개 `*Max` + 6개 `*Init` = 도시당 **12개** 경제값은 wiki income/type→v1 cap/init 환산 공식이 없어 `UNAVAILABLE`(research §7). projection이 이 12값을 발명하지 않는다 — OPENSAM-105 defer 대상(§12). |
| **region** | v1 `region: Int` codebook과 RTK region/province/338-area의 의미 대응은 미증명(`[UNKNOWN]`, research §7). 직접 cast 금지. |

## 6. Stable identity와 versioned v1 Int ID map

1. **city stable ID:** `C001`, `C002`, … `C046` (OPENSAM-102 coordinate ledger의 `都市` source order `001..046` 보존).
2. **small-base stable ID:** `B001`, `B002`, … `B055` (ledger의 small-base ordinal `001..055`; native center `(y,x)` 오름차순, 수량 목표를 맞추기 위한 생성 번호 아님).
3. stable ID는 **display name과 분리**된다. `秣陵/建業` 같은 time-scoped alias, `鄴`의 half-width `ｷﾞｮｳ` slug, `下邳`의 `下ヒ` slug, 맵 plaque `羌` ↔ canonical `羌族`처럼 이름 하나를 identity로 승격하지 않는다(research §5·§13). display name은 시간·시나리오별로 바뀌어도 stable ID는 불변.
4. **versioned v1 Int ID map:** v1의 `Int` id(`MapCityCoord.id`/`MapCityDetail.id`/`ScenarioCity.id`/`connections` 원소)와 v2 stable string ID(`C001`/`B001`) 사이 대응은 **버전이 붙은 명시 매핑 테이블**로만 성립한다. 이 매핑은 다음을 만족한다.
   - 각 항목은 `{v1_int_id, v2_stable_id, map_version, source_locator}`를 자체 보유한다.
   - v1 Int id는 시나리오/맵 코드별로 다를 수 있으므로 매핑은 (map version, scenario)에 스코프된다. 전역 단일 매핑을 가정하지 않는다.
   - 이름 기반 자동 join으로 매핑을 만들지 않는다. name→stable-id reconciliation은 OPENSAM-102 46-row acceptance envelope(중복 0·orphan 0·rights clear)를 통과한 뒤에만 매핑 항목으로 승격한다.
   - **이 스펙은 매핑 테이블 자체를 생성하지 않는다.** 매핑의 형식·불변식만 규정한다(구현은 별도 승인).

## 7. 좌표 evidence — 정확히 101개, native-pixel은 world coordinate가 아님

`[사실]` OPENSAM-102 coordinate ledger(`docs/superpowers/research/2026-07-17-opensam-102-map-coordinate-ledger.csv`, SHA-256 `d0a6c9dab6f1233588ff1a753b84f83fc338b20cb87c5a39f2f043d47512c5c5`, header 1 + data **101**):

| entity | rows | classification |
|---|---:|---|
| `CITY` | 46 | yellow city plaque |
| `SMALL_BASE / PORT` | 40 | pink port plaque |
| `SMALL_BASE / GATE` | 10 | enclosed white gate plaque |
| `SMALL_BASE / ETHNIC_STRONGHOLD` | 5 | yellow faction plaque |
| **total** | **101** | 46 city + 55 small-base |

- 좌표의 의미는 **지정된 `PK.png`(native `4181×4191`, SHA-256 `dfe5049245e730ff1f81325157dedfcf1079786e33c6ad8b3970140954910a89`)의 좌상단 원점 native pixels**로 제한된다. **native-pixel은 world coordinate·projection·이동 그래프·역사 위치가 아니다.**
- 좌표를 runtime `MapJson.x/y` 또는 v2 placement로 삽입하려면 image version·scaling/crop 계약·rounding 정책·asset rights가 §10 precondition으로 먼저 확정돼야 한다. 이 스펙은 좌표를 인용(evidence)할 뿐 import하지 않는다.
- `101`은 “기대 행을 채우는 생성 목표”가 아니라 소스의 직접 측정 count다. producer/A2 review invariant: data rows `101`, unique centers `101`, duplicate bbox `0`, image-bounds violation `0`, city ordinal `001..046` contiguous, small-base ordinal `001..055` contiguous.

## 8. Rights와 UNKNOWN 보존

1. **5개 이민족 거점의 `parent_city` = `[UNKNOWN]` 보존.** 烏桓·鮮卑·羌族·山越·南蛮의 세력 페이지는 단일 parent가 아니라 복수 외교 선행 도시를 나열하므로, 근접성만으로 parent를 추정하지 않는다(ledger notes, research §13). 이 5개 row는 `base_kind=ETHNIC_STRONGHOLD`, `parent_city=UNKNOWN`으로 남는다. 46개 CITY row는 `parent_city=NOT_APPLICABLE`(도시 자신이 parent)이고, `地域データ`로 parent가 판정된 것은 PORT 40 + GATE 10 = 50개 small-base row다.
2. **source/asset redistribution = `RIGHTS WARN`.** WIKIWIKI·Koei Tecmo 공식 매뉴얼 어디에서도 opensamguk 재배포 허락이 확인되지 않았다. 텍스트·표·이미지·좌표는 **research metadata only**이며 repo bundle/runtime allowlist/CDN/deploy로 승격하지 않는다. `RIGHTS WARN`은 load-bearing이며 이 스펙의 어떤 절도 이를 해제하지 않는다 — rights release는 `LEGAL` 게이트(사람 legal/release owner)의 별도 명시 판정을 요구한다.
3. `PhysicalPlace`의 historical `placeIdentityKey`·valid time·`RESOLVED_POINT`는 RTK 자료만으로 확정하지 않는다. 모든 RTK claim은 `GAME_REFERENCE`이며 history/evidence 트랙과 confidence를 합치지 않는다(research §8·§9).

## 9. Staged cutover 순서 — v2 전 시나리오 대상

> **범위 수정(사용자 지시 2026-07-17, 계약 §13 item 6):** 원 §9의 `황건의 난 184-02` 단일 pilot 고정은 **해제**된다. 사용자 원문: “그리고 황건의난으로 고정하면 어떡하냐.” 이 수정은 계약 §8 criterion 8을 supersede한다. cutover는 v2 콘텐츠 대체의 **전 시나리오 집합**을 대상으로 하며, 특정 시나리오를 privileged pilot로 두거나 시나리오 간 사용자 승인 게이트를 강제하지 않는다.

1. cutover 대상은 v2 대체 콘텐츠의 **전 시나리오 집합**이다. `황건의 난 184-02`는 절차를 예시하는 worked example로 쓸 수 있으나 privileged pilot이 아니다 — 다른 시나리오보다 앞선 지위를 갖지 않는다.
2. **per-scenario 검증 절차는 종전과 동일하게 엄격하다:** 각 시나리오는 **mapping**(stable ID + versioned v1 Int map, §6), **compare**(dual-read/compare 또는 equivalent observation, §10), **rollback 검증**(§10)을 자체적으로 통과해야 한다. 시나리오별 검증 rigor는 약화되지 않는다.
3. **삭제된 규칙:** (i) `황건의 난 184-02` 1차 고정, (ii) 시나리오 간 사용자 승인 게이트 / “한 번에 하나씩” 확장 규칙. cutover 순서·병렬성은 구현 lane 재량이다.
4. **default scenario는 바꾸지 않는다(불변).** golden harness의 default(devsam scenario_1010)와 seed 기본값은 이 스펙의 어떤 단계에서도 변경되지 않는다. 대체 시나리오는 default가 아니라 새 대체 콘텐츠로서 검증 트랙에 존재한다.

## 10. Cutover preconditions · dual-read/compare · abort · rollback

### 10.1 preconditions (한 시나리오 cutover 착수 전 전부 충족)

- 이 스펙 status `APPROVED`(§1). 시나리오별 개별 사용자 승인은 요구하지 않는다(계약 §13 item 6이 시나리오 간 승인 게이트를 제거). per-scenario 게이트는 아래 기술 조건(mapping·compare·rollback 관측, §6/§10.2/§10.4)만 남는다.
- stable ID map과 versioned v1 Int map이 존재하고 version·source_locator·중복 0·orphan 0을 만족(§6).
- 좌표를 쓰는 경우: image version·SHA-256·scaling/crop 계약·rounding 정책 확정(§7). 미확정이면 좌표 미승격(fail-closed).
- rights: 해당 시나리오가 참조하는 text/coordinate/asset의 redistribution 상태가 `RIGHTS WARN` 이상으로 격상되지 않는 한, bundle/runtime/deploy로 나가지 않는다.
- devsam golden byte baseline이 동결돼 있고 이번 대체가 golden을 건드리지 않음(§4).

### 10.2 dual-read/compare (또는 equivalent observation)

- 대체 콘텐츠 투입 전후를 **관측 가능한 비교**로 남긴다: 동일 시나리오·동일 seed에서 (a) engine semantics 불변(§3.2) — RNG draw stream/로그 byte/flush delta가 baseline과 동일, (b) 콘텐츠 layer만 의도대로 교체됨.
- 완전한 dual-read 스택 실행이 불가하면 equivalent observation(예: 대체 콘텐츠 로드 후 draw-for-draw 게이트 replay + 콘텐츠 diff 관측)으로 대신하되, **관측 실패를 pass로 위장하지 않는다.** 환경 실패는 `environment-failed/inconclusive`로 분리한다.

### 10.3 abort criteria

다음 중 하나라도 발생하면 즉시 abort하고 §10.4 rollback을 실행한다.

- engine semantics(§3.2) drift 관측: RNG draw 순서/횟수/args, `PhpRound` 결과, 한글 로그 byte, side-effect/insertion order, flush delta 중 하나라도 baseline과 불일치.
- devsam golden byte 변경이 요구됨(§4 위반 신호).
- 필수 필드가 `[UNKNOWN]`인데 placeholder/default/reverse-edge로 채워야만 진행되는 상황(§5.3·§12).
- rights 상태가 `RIGHTS WARN`을 넘어 bundle/redistribution을 요구.

### 10.4 rollback source/version

- 각 cutover는 착수 전 **되돌릴 대상**을 명시 고정한다: 이전 content version id + 그 소스 fingerprint(이미지 SHA-256·ledger SHA-256·map version). rollback = 이 이전 version/source로 복귀.
- default scenario·devsam golden은 애초에 바뀌지 않으므로(§4·§9) rollback의 안전망이며, 대체 콘텐츠만 이전 version으로 되돌린다.
- rollback 후 §10.2 관측을 다시 수행해 baseline 복귀를 확인한다. 확인 전에는 완료로 주장하지 않는다.

## 11. Raw/intermediate source artifact 경로 정책

1. raw/intermediate source artifact(원본 이미지, 중간 crop/annotation, 추출 스크래치, 생성 시나리오 원본)는 **repo 밖 경로 또는 gitignored 경로에만** 둔다. tracked 저장 금지(fail-closed).
2. `[사실]` 현재 근거: 원본 `PK.png`와 annotated review derivative는 repo 밖 격리 경로(`/Users/apple/.codex/visualizations/…/opensam-102/…`)에 fingerprint·rights manifest와 함께 보존됨(research §13). 좌표 ledger CSV만 `docs/superpowers/research/` 아래 tracked이며 이는 **측정 metadata**이지 asset이 아니다.
3. 이 스펙은 각 artifact의 **provenance(canonical URL·`?rev` 제거·access date)·fingerprint(SHA-256·dimensions·bytes)·경로 정책(repo 밖/gitignored)** 만 기록한다. raw/intermediate artifact를 이 스펙이 tracked로 만들지 않으며 어떤 시나리오도 실제로 구현하지 않는다.
4. five-stat divergence 선례(`2026-06-13-…`)와 동일 정책: RTK 원본·source JSON·생성 시나리오는 git-ignore/미커밋, 빌더 알고리즘만 버전 관리 대상.

## 12. OPENSAM-105 defer — full adjacency + `MapJson` 12개 값 `[UNKNOWN]`

OPENSAM-105는 이 스펙에서 **deferred**다. 근거:

1. **full adjacency 미확정.** RTK14 46 city page / RTK8R 51 city page를 전수 감사하지 않았다. 표본만 존재(`洛陽`은 `晋陽` 관련 editorial conflict 보존, `呉↔建業`은 표본 한 쌍). 전체 graph·방향성·대칭성·edge count는 `[UNKNOWN]`. **reverse edge 자동 생성 금지** — directed claim을 무조건 symmetric list로 바꾸지 않는다(research §4·§5·§6).
2. **`MapJson` 12개 값 `[UNKNOWN]`.** 도시당 6개 `*Max`(population/agriculture/commerce/security/defence/wall) + 6개 `*Init` = **12개** 경제값은 wiki income/type→v1 cap/init 환산 공식이 없어 `UNAVAILABLE`(research §7, `MapJson.kt:18-29`). 이름·값·기본값을 발명하지 않는다.
3. native-pixel 좌표는 projection·이동 그래프·역사 위치의 증거가 아니다(§7). 따라서 105를 포함하면 미확인 adjacency나 12개 값을 placeholder/default로 날조하게 되어 패러티·데이터 계약과 `[UNKNOWN]` 보존 규칙을 위반한다.
4. OPENSAM-105는 full adjacency와 12개 값의 정본, stable identity, rights-cleared presentation asset을 갖춘 **별도 계약과 별도 승인**을 요구한다. 본 스펙은 105의 builder·`MapJson` 생성·도시 runtime wiring·route 활성화를 하지 않는다.

## 13. 범위 수정 — 지도 기준 = RTK14 (사용자 지시 2026-07-17)

이 절은 계약 §13(범위 수정, 2026-07-17 A0 이후 사용자 지시)에 기록된 지시를 반영한다. §1~§12의 모든 원 요건(status `PROPOSED`, user-only `APPROVED` 전환 포함)은 그대로 유지된다.

- **(a) 지도 콘텐츠 기준 = RTK14.** 사용자 지시(2026-07-17) — 원문: “이참에 지도도 RTK14로 맞춰 … 모든 RTK 시리즈의 지도를 가지고와서 비교하고 보충해” — 로 v2 지도 콘텐츠의 기준 소스는 RTK14로 확정한다. “실제/역사 지도가 아니다”라는 유보는 **해제**된다 — 이는 RTK14를 지도 기준으로 채택하는 결정에 대한 유보 해제이지, native-pixel이 곧 world coordinate라는 뜻은 아니다(§7의 측정 사실은 불변). 근거: 계약 `docs/superpowers/plans/2026-07-17-opensam-92-93-94-97-103-execution-contract.md` §13, ADR-LITE-010.
- **(b) 교차 RTK 시리즈 비교 = unknown 보충의 sanctioned 증거 경로.** 현재 `[UNKNOWN]`인 full adjacency와 `MapJson` 12개 값(§12)은 별도 리서치 레인(`lane-map-rtk-series`)이 전 RTK 시리즈 지도를 수집·비교해 **per-series provenance**와 함께 보충한다. 예정 입력(모두 forthcoming): 비교 리서치 `docs/superpowers/research/2026-07-17-rtk-series-map-comparison.md`와 인접 원장 `docs/superpowers/research/2026-07-17-rtk-series-adjacency-ledger.csv`, 그리고 RTK14 hexmap datafication(빌더 `tools/rtk14/build_rtk14_hexmap.py`, 리서치 `docs/superpowers/research/2026-07-17-rtk14-hexmap-datafication.md`)을 content-layer terrain 증거로 삼는다. hexmap datafication은 사용자 지시(“일단 RTK14 지도 원본 이미지는 데이터화 시켜. 헥스맵이니까”, 계약 §13 item 5)에 따라 RTK14 원본 지도 이미지를 헥스 셀로 데이터화하며, 셀 지형 색은 wiki legend에서 확인된다. 이 비교·데이터화 증거가 unknown을 채우는 승인된 유일 경로다.
- **(c) UNKNOWN 보존은 계속 적용.** 어떤 시리즈도 증거하지 못하는 값은 `[UNKNOWN]`으로 남긴다 — placeholder/default 발명 금지(§5.3·§12). OPENSAM-105는 이 비교 증거가 도착하고 사용자가 별도 승인하기 전까지 **deferred** 유지(§12).
- **(d) 권리 보수성 불변.** raw 지도 이미지와 redistribution은 `RIGHTS WARN`로 유지된다(§8). 시리즈 비교가 rights를 해제하지 않는다.

## 14. 비범위 (이 스펙이 하지 않는 것)

- builder·scraper·validator executable 또는 runtime code.
- `ScenarioJson`/`MapJson`/`PhysicalPlace`의 실제 생성·변경·wiring.
- default scenario/seed 변경 또는 content 활성화(§9).
- devsam fixtures/goldens 변경(§4).
- full adjacency 생성·reverse edge 자동 보충, `MapJson` 12개 값·5개 이민족 거점 `parent_city`·pixel-to-world projection의 placeholder/default 발명(§12).
- raw/intermediate source artifact의 tracked 저장, 시나리오 실제 구현(§11).
- raw image/source table bundle, CDN, Jira/GitHub 변경, commit/push/PR/deploy, rights release.

## 15. Acceptance criteria 매핑 (계약 §8의 11개 G/W/T)

| # | 기준 | 충족 근거 |
|---:|---|---|
| 1 | status `PROPOSED` + ADR-LITE-010 context/decision/alternatives/consequences/cutover/rollback 존재 | §1, §2, §9, §10 |
| 2 | divergence는 content layer로 한정, engine semantics immutable | §3 (특히 §3.2) |
| 3 | devsam fixtures/goldens baseline freeze + 변경 금지/별도 승인, 새 content가 기존 golden 재작성 금지 | §4 |
| 4 | `ScenarioJson`/`MapJson` ↔ `PhysicalPlace`, `C001`/`B001`, versioned v1 Int map의 방향·version·unknown 규칙 | §5, §6 |
| 5 | 정확히 101 coordinates, 5개 이민족 `parent_city` `[UNKNOWN]`, `RIGHTS WARN`, native-pixel ≠ world coordinate | §7, §8 |
| 6 | full adjacency·`MapJson` 12개 값 미확인 → 105 deferred, placeholder/default/reverse edge 금지 | §12 |
| 7 | cutover 실패/parity mismatch 시 이전 content version/source rollback 절차와 관측 지점 | §10.3, §10.4 |
| 8 | **superseded** — 계약 §13 item 6이 원 criterion 8(1차 `황건의 난 184-02` 고정 + 한 번에 하나씩 확장)을 대체. 새 규칙: cutover는 전 시나리오 집합 대상, pilot 고정·시나리오 간 승인 게이트 없음, per-scenario 검증 rigor 유지, default scenario 불변 | §9 |
| 9 | raw/intermediate source artifact는 repo 밖/gitignored만, tracked artifact·구현 결과 없음 | §11 |
| 10 | `PROPOSED` → agent/reviewer 검토로도 `APPROVED` 전환 불가, 사용자 명시 승인만 근거 | §1 |
| 11 | 이 티켓 diff는 지정 spec 외 builder/runtime/default scenario/Jira 변경 0개 | 문서 only (§scope), 검증은 `git diff` |
